package semantic.harness.presentation

import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class InferTypeBatchServiceSuite extends munit.FunSuite:
  private val workspace = Path.of(".").toAbsolutePath.normalize()
  private val fixture =
    "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala"
  private val erroneous =
    "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/ErroneousFixture.scala"

  test("batch public enums and schema markers use explicit stable strings"):
    assertEquals(InferTypeBatchItemStatus.Resolved.asJson.noSpaces, "\"Resolved\"")
    assertEquals(InferTypeBatchItemStatus.Unresolved.asJson.noSpaces, "\"Unresolved\"")
    assertEquals(InferTypeBatchItemStatus.InvalidRequest.asJson.noSpaces, "\"InvalidRequest\"")
    assertEquals(InferTypeBatchItemStatus.QueryFailure.asJson.noSpaces, "\"QueryFailure\"")
    assertEquals(InferTypeBatchRequest.SchemaVersion, "semantic-scala.infer-type-batch-request.v1")
    assertEquals(InferTypeBatchReport.SchemaVersion, "semantic-scala.infer-type-batch-result.v1")
    assert(decode[InferTypeBatchItemStatus]("\"Failure\"").isLeft)

  test("strict request contract rejects unknown fields, duplicates, invalid IDs, and bounds"):
    val valid =
      """{"schemaVersion":"semantic-scala.infer-type-batch-request.v1","requests":[{"id":"one","file":"Main.scala","line":1,"column":1}]}"""
    assert(InferTypeBatchRequest.decodeAndValidate(valid).isRight)
    assert(InferTypeBatchRequest.decodeAndValidate(valid.dropRight(1) + ""","extra":true}""").isLeft)
    assert(
      InferTypeBatchRequest
        .decodeAndValidate(valid.replace("\"column\":1", "\"column\":1,\"extra\":true"))
        .isLeft
    )
    assert(
      InferTypeBatchRequest
        .validate(
          InferTypeBatchRequest(
            requests = List.tabulate(InferTypeBatchBounds.MaxRequests + 1)(index =>
              InferTypeBatchRequestItem(s"id-$index", "Main.scala", 1, 1)
            )
          )
        )
        .isLeft
    )
    assert(
      !InferTypeBatchBounds.withinUtf8(
        "r" * (InferTypeBatchBounds.MaxRenderingBytes + 1),
        InferTypeBatchBounds.MaxRenderingBytes
      )
    )
    assert(
      !InferTypeBatchBounds.withinUtf8(
        "m" * (InferTypeBatchBounds.MaxMessageBytes + 1),
        InferTypeBatchBounds.MaxMessageBytes
      )
    )
    assert(
      InferTypeBatchRequest
        .validate(
          InferTypeBatchRequest(
            requests = List(
              InferTypeBatchRequestItem("one", "Main.scala", 1, 1),
              InferTypeBatchRequestItem("one", "Other.scala", 1, 1)
            )
          )
        )
        .left
        .exists(_.contains("Duplicate"))
    )
    assert(InferTypeBatchRequest.decodeAndValidate(valid.replace("\"one\"", "\"bad id\"")).isLeft)
    assert(InferTypeBatchRequest.decodeAndValidate(valid.replace("\"line\":1", "\"line\":0")).isLeft)
    assert(
      InferTypeBatchRequest
        .validate(
          InferTypeBatchRequest(
            requests = List(
              InferTypeBatchRequestItem(
                "x",
                "x" * (InferTypeBatchBounds.MaxFilePathBytes + 1),
                1,
                1
              )
            )
          )
        )
        .isLeft
    )

  test("ordered mixed outcomes preserve identity and continue after invalid and unresolved items"):
    val requests = List(
      itemAt("resolved-first", fixture, "optional =", 1),
      InferTypeBatchRequestItem("missing", "missing/Missing.scala", 1, 1),
      itemAt("unresolved", fixture, "42", 0),
      InferTypeBatchRequestItem("invalid-position", fixture, 9999, 1),
      itemAt("resolved-last", fixture, "function =", 1)
    )
    val results = run(requests)

    assertEquals(results.map(_.index), List(0, 1, 2, 3, 4))
    assertEquals(results.map(_.id), requests.map(_.id))
    assertEquals(
      results.map(_.status),
      List(
        InferTypeBatchItemStatus.Resolved,
        InferTypeBatchItemStatus.InvalidRequest,
        InferTypeBatchItemStatus.Unresolved,
        InferTypeBatchItemStatus.InvalidRequest,
        InferTypeBatchItemStatus.Resolved
      )
    )
    assertEquals(results(2).renderingKind, InferTypeRenderingKind.NoRendering)
    assert(results(2).warnings.exists(_.contains("cannot determine why")))
    assert(results.last.rendering.exists(_.contains("Int => String")))

  test("shared sequential compiler matches one compiler per valid item across files and duplicate positions"):
    val duplicate = itemAt("duplicate-a", fixture, "optional.map", "optional.".length + 1)
    val requests = List(
      itemAt("generic", fixture, "inferred =", 1),
      duplicate,
      duplicate.copy(id = "duplicate-b"),
      itemAt("erroneous-local", erroneous, "locallyResolved", 2),
      itemAt("erroneous-unresolved", erroneous, "MissingDependency", 2)
    )
    val shared = InferTypeBatchService(InferTypeBatchCompilerStrategy.SharedSequential)
      .infer(workspace, requests, PresentationCompilerContext(workspace = Some(workspace)))
      .fold(message => fail(message), identity)
    val perItem = InferTypeBatchService(InferTypeBatchCompilerStrategy.PerItem)
      .infer(workspace, requests, PresentationCompilerContext(workspace = Some(workspace)))
      .fold(message => fail(message), identity)

    assertEquals(shared, perItem)
    assertEquals(shared(1).copy(index = 2, id = "duplicate-b"), shared(2))
    assertEquals(shared.last.status, InferTypeBatchItemStatus.Unresolved)

  test("shared compiler lifecycle creates and closes exactly once"):
    var created = 0
    var closed = 0
    val service = InferTypeBatchService(
      strategy = InferTypeBatchCompilerStrategy.SharedSequential,
      onCompilerCreated = () => created += 1,
      onCompilerClosed = () => closed += 1
    )
    val results = service
      .infer(
        workspace,
        List(
          itemAt("one", fixture, "explicit: Int", 1),
          InferTypeBatchRequestItem("invalid", "Missing.scala", 1, 1),
          itemAt("two", fixture, "optional =", 1)
        ),
        PresentationCompilerContext(workspace = Some(workspace))
      )
      .fold(message => fail(message), identity)

    assertEquals(created, 1)
    assertEquals(closed, 1)
    assertEquals(results.last.status, InferTypeBatchItemStatus.Resolved)

  test("one QueryFailure does not cancel or poison a later valid item"):
    val service = InferTypeBatchService(
      strategy = InferTypeBatchCompilerStrategy.SharedSequential,
      afterCompilerQuery = item =>
        if item.id == "forced-failure" then throw new IllegalStateException("injected")
    )
    val results = service
      .infer(
        workspace,
        List(
          itemAt("before", fixture, "explicit: Int", 1),
          itemAt("forced-failure", fixture, "optional =", 1),
          itemAt("after", fixture, "function =", 1)
        ),
        PresentationCompilerContext(workspace = Some(workspace))
      )
      .fold(message => fail(message), identity)

    assertEquals(
      results.map(_.status),
      List(
        InferTypeBatchItemStatus.Resolved,
        InferTypeBatchItemStatus.QueryFailure,
        InferTypeBatchItemStatus.Resolved
      )
    )
    assertEquals(results(1).message, Some("Presentation compiler query failed for this item"))
    assert(results.last.rendering.exists(_.contains("Int => String")))

  test("UTF-16 positions and Unicode IDs remain stable"):
    val unicode = itemAt("unicode.id-1", fixture, "unicodeOptional", 2)
    val result = run(List(unicode)).head
    assertEquals(result.id, "unicode.id-1")
    assertEquals(result.position.encoding, "UTF-16")
    assertEquals(result.status, InferTypeBatchItemStatus.Resolved)
    assert(result.rendering.exists(_.contains("Option[String]")))

  test("source size bound is an item-local InvalidRequest"):
    val directory = Files.createTempDirectory(workspace.resolve("target"), "batch-source-bound-")
    val source = directory.resolve("TooLarge.scala")
    try
      Files.write(
        source,
        Array.fill((InferTypeBatchBounds.MaxSourceFileBytes + 1).toInt)('a'.toByte)
      )
      val relative = workspace.relativize(source).toString
      val result = run(List(InferTypeBatchRequestItem("large", relative, 1, 1))).head
      assertEquals(result.status, InferTypeBatchItemStatus.InvalidRequest)
      assert(result.message.exists(_.contains("exceeds")))
    finally
      Files.deleteIfExists(source)
      Files.deleteIfExists(directory)

  test("invalid platform path is an item-local InvalidRequest"):
    val result = run(List(InferTypeBatchRequestItem("invalid-path", "\u0000.scala", 1, 1))).head
    assertEquals(result.status, InferTypeBatchItemStatus.InvalidRequest)
    assertEquals(result.message, Some("Source path is invalid"))

  test("batch report encoding is deterministic and excludes raw compiler data"):
    val results = run(List(itemAt("one", fixture, "optional =", 1)))
    val report = InferTypeBatchReport(
      requestCount = 1,
      context = InferTypeContextSummary(
        kind = InferTypeContextKind.SbtClasspath,
        classpathEntryCount = 1,
        workspaceProvided = true
      ),
      contextWarnings = Nil,
      results = results
    )
    val first = InferTypeBatchReport.encodeBounded(report).fold(message => fail(message), identity)
    val second = InferTypeBatchReport.encodeBounded(report).fold(message => fail(message), identity)
    assertEquals(first, second)
    assert(!first.contains("rawCompilerRendering"))
    assert(!first.contains(System.getProperty("user.home")))

    val overBound = report.copy(
      results = List(
        results.head.copy(
          rendering = Some("x" * (InferTypeBatchBounds.MaxEncodedOutputBytes.toInt + 1))
        )
      )
    )
    assert(InferTypeBatchReport.encodeBounded(overBound).isLeft)

  private def run(requests: List[InferTypeBatchRequestItem]): List[InferTypeBatchItemResult] =
    InferTypeBatchService()
      .infer(workspace, requests, PresentationCompilerContext(workspace = Some(workspace)))
      .fold(message => fail(message), identity)

  private def itemAt(
      id: String,
      file: String,
      marker: String,
      delta: Int
  ): InferTypeBatchRequestItem =
    val source = Files.readString(workspace.resolve(file), StandardCharsets.UTF_8)
    val offset = source.indexOf(marker)
    assert(offset >= 0, clue(marker))
    val target = offset + delta
    val lineStart = source.lastIndexOf('\n', math.max(0, target - 1)) + 1
    val line = source.substring(0, lineStart).count(_ == '\n') + 1
    InferTypeBatchRequestItem(id, file, line, target - lineStart + 1)
