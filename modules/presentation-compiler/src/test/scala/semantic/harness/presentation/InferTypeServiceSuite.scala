package semantic.harness.presentation

import io.circe.syntax.*
import io.circe.parser.decode
import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.presentation.fixture_api.DomainId

class InferTypeServiceSuite extends munit.FunSuite:
  private val service = PresentationCompilerService()

  test("public infer-type enums use explicit stable JSON strings"):
    assertEquals(InferTypeStatus.Unresolved.asJson.noSpaces, "\"Unresolved\"")
    assertEquals(InferTypeRenderingKind.NoRendering.asJson.noSpaces, "\"NoRendering\"")
    assertEquals(InferTypeContextKind.ExplicitClasspath.asJson.noSpaces, "\"ExplicitClasspath\"")
    assertEquals(InferTypeContextKind.SbtClasspath.asJson.noSpaces, "\"SbtClasspath\"")
    assertEquals(InferTypeAcquisitionOrigin.FreshSbt.asJson.noSpaces, "\"FreshSbt\"")
    assertEquals(
      InferTypeAcquisitionOrigin.CachedExplicitReuse.asJson.noSpaces,
      "\"CachedExplicitReuse\""
    )
    assertEquals(
      InferTypeFreshnessAssessment.FreshBySbtEvaluation.asJson.noSpaces,
      "\"FreshBySbtEvaluation\""
    )
    assertEquals(
      InferTypeFreshnessAssessment.ReusedWithMatchingEvidence.asJson.noSpaces,
      "\"ReusedWithMatchingEvidence\""
    )
    assertEquals(decode[InferTypeStatus]("\"NoTypeAtPosition\"").isLeft, true)
    assertEquals(decode[InferTypeRenderingKind]("\"None\"").isLeft, true)

  test("hover extraction prefers an explicitly labelled expression type"):
    val raw =
      """**Expression type**:
        |```scala
        |Option[Int]
        |```
        |**Symbol signature**:
        |```scala
        |def map[B](f: String => B): Option[B]
        |```""".stripMargin

    assertEquals(
      CompilerHoverRendering.select(rawMarkup = Some(raw), symbolSignature = Some("def map[B](f: String => B): Option[B]")),
      Some(CompilerHoverRendering.Extracted("Option[Int]", InferTypeRenderingKind.ExpressionType))
    )

  test("hover extraction falls back to public symbol signature and then a leading Scala code block"):
    assertEquals(
      CompilerHoverRendering.select(Some("unstructured"), Some("val inferred: List[Int]")),
      Some(CompilerHoverRendering.Extracted("val inferred: List[Int]", InferTypeRenderingKind.SymbolSignature))
    )
    assertEquals(
      CompilerHoverRendering.select(Some("```scala\nInt\n```"), None),
      Some(CompilerHoverRendering.Extracted("Int", InferTypeRenderingKind.HoverCode))
    )

  test("inferType resolves explicit, inferred, generic, call, chained, and function cases"):
    val cases = List(
      ("explicit: Int", 1, "Int"),
      ("inferred =", 1, "List[Int]"),
      ("optional =", 1, "Option[String]"),
      ("add(explicit, 1)", 1, "Int"),
      ("optional.map", "optional.".length + 1, "Option[Int]"),
      ("function =", 1, "Int => String")
    )

    cases.foreach { case (marker, delta, expected) =>
      val result = query(fixture, marker, delta)
      assertEquals(result.status, InferTypeStatus.Resolved, clue(marker))
      assert(result.rendering.exists(_.contains(expected)), clue(s"$marker -> ${result.rendering}"))
      assert(result.rawCompilerRendering.exists(_.nonEmpty), clue(marker))
      assert(result.warnings.exists(_.contains("not canonical type identity")), clue(marker))
    }

    assertEquals(query(fixture, "inferred =", 1).rendering, Some("val inferred: List[Int]"))
    assertEquals(query(fixture, "optional.map", "optional.".length + 1).rendering, Some("Option[Int]"))

  test("literal point hover is explicitly unresolved with the pinned public API"):
    val result = query(fixture, "42", 0)

    assertEquals(result.status, InferTypeStatus.Unresolved)
    assertEquals(result.rendering, None)
    assert(result.warnings.exists(_.contains("cannot determine why")))

  test("method-name hover remains a compiler-rendered method signature"):
    val result = query(fixture, "def add", "def ".length + 1)

    assertEquals(result.status, InferTypeStatus.Resolved)
    assertEquals(result.renderingKind, InferTypeRenderingKind.SymbolSignature)
    assert(result.rendering.exists(value => value.contains("def add") && value.contains(": Int")))

  test("inferType returns Unresolved for whitespace and comments"):
    val source = Files.readString(fixture)
    val blankOffset = source.indexOf("\n\n  // no type comment marker") + 1
    val blank = queryAt(fixture, positionAt(source, blankOffset))
    val comment = query(fixture, "no type comment marker", 3)

    List(blank, comment).foreach { result =>
      assertEquals(result.status, InferTypeStatus.Unresolved)
      assertEquals(result.rendering, None)
      assertEquals(result.renderingKind, InferTypeRenderingKind.NoRendering)
      assert(result.warnings.exists(_.contains("cannot determine why")))
    }

  test("inferType resolves a test-support type only with explicit classpath"):
    val classpath = Path.of(classOf[DomainId].getProtectionDomain.getCodeSource.getLocation.toURI)
    val position = markerPosition(externalFixture, "domain =", 1)
    val request = InferTypeRequest(
      externalFixture,
      position.line,
      position.column,
      PresentationCompilerContext.explicit(List(classpath, classpath))
    )

    val result = service.inferType(request).fold(message => fail(message), identity)
    assertEquals(result.status, InferTypeStatus.Resolved)
    assertEquals(result.contextKind, InferTypeContextKind.ExplicitClasspath)
    assertEquals(result.classpathEntryCount, 1)
    assertEquals(result.rendering, Some("val domain: DomainId"))
    assert(
      result.warnings.contains(
        "Beyond the built-in Scala runtime, only the supplied compiled classpath entries are available."
      )
    )

  test("missing classpath and erroneous source return explicit ambiguous no-type results"):
    val missingContext = query(externalFixture, "domain =", 1)
    val erroneous = query(erroneousFixture, "MissingDependency", 2)

    List(missingContext, erroneous).foreach { result =>
      assertEquals(result.status, InferTypeStatus.Unresolved)
      assertEquals(result.rendering, None)
      assert(result.warnings.exists(_.contains("cannot determine why")))
    }

    val locallyResolved = query(erroneousFixture, "locallyResolved", 2)
    assertEquals(locallyResolved.status, InferTypeStatus.Resolved)
    assertEquals(locallyResolved.rendering, Some("val locallyResolved: Int"))
    assert(locallyResolved.warnings.exists(_.contains("does not prove whole-project compilation")))

  test("one-based positions use UTF-16 code units and support line and file boundaries"):
    assertEquals(SourcePosition.offset("😀target\n", 1, 3), Right(2))
    assertEquals(SourcePosition.offset("abc\n", 1, 4), Right(3))
    assertEquals(SourcePosition.offset("abc\n", 2, 1), Right(4))
    assertEquals(SourcePosition.offset("abc", 1, 4), Right(3))
    assertEquals(SourcePosition.offset("a\r\nb", 2, 1), Right(3))

    val afterSurrogatePair = query(fixture, "unicodeOptional", 2)
    val unicodeIdentifier = query(fixture, "λ =", 0)
    assertEquals(afterSurrogatePair.status, InferTypeStatus.Resolved)
    assert(afterSurrogatePair.rendering.exists(_.contains("Option[String]")))
    assertEquals(unicodeIdentifier.status, InferTypeStatus.Resolved)

  test("identifier start, middle, and final UTF-16 unit select the same compiler hover"):
    val first = query(fixture, "inferred =", 0)
    val middle = query(fixture, "inferred =", 3)
    val last = query(fixture, "inferred =", "inferred".length - 1)

    List(middle, last).foreach { result =>
      assertEquals(result.status, first.status)
      assertEquals(result.rendering, first.rendering)
      assertEquals(result.renderingKind, first.renderingKind)
      assertEquals(result.range, first.range)
      assertEquals(result.warnings, first.warnings)
    }

  test("end-of-line and EOF boundaries are valid query positions"):
    val source = Files.readString(fixture)
    val lineEndOffset = source.indexOf('\n', source.indexOf("val explicit"))
    val lineEnd = queryAt(fixture, positionAt(source, lineEndOffset))
    val eof = queryAt(fixture, positionAt(source, source.length))

    assertEquals(lineEnd.status, InferTypeStatus.Unresolved)
    assertEquals(eof.status, InferTypeStatus.Unresolved)

  test("input and context failures stay separate from valid no-type results"):
    assert(service.inferType(InferTypeRequest(fixture, 0, 1)).left.exists(_.contains("Line must be positive")))
    assert(service.inferType(InferTypeRequest(fixture, 1, 0)).left.exists(_.contains("Column must be positive")))
    assert(service.inferType(InferTypeRequest(fixture, 200, 1)).left.exists(_.contains("outside the source file")))
    assert(service.inferType(InferTypeRequest(fixture, 1, 200)).left.exists(_.contains("outside line")))
    assert(service.inferType(InferTypeRequest(fixture.resolveSibling("Missing.scala"), 1, 1)).left.exists(_.contains("does not exist")))
    assert(service.inferType(InferTypeRequest(Path.of("README.md"), 1, 1)).left.exists(_.contains("must point to a .scala file")))

    val missingClasspath = PresentationCompilerContext.explicit(List(Path.of("target/missing-infer-type-classpath")))
    assert(service.inferType(InferTypeRequest(fixture, 1, 1, missingClasspath)).left.exists(_.contains("Classpath entry does not exist")))

  test("repeated fresh-compiler queries are deterministic"):
    val position = markerPosition(fixture, "optional.map", "optional.".length + 1)
    val request = InferTypeRequest(fixture, position.line, position.column)

    val firstStarted = System.nanoTime()
    val first = service.inferType(request).fold(message => fail(message), identity)
    val firstMillis = (System.nanoTime() - firstStarted) / 1000000
    val secondStarted = System.nanoTime()
    val second = service.inferType(request).fold(message => fail(message), identity)
    val secondMillis = (System.nanoTime() - secondStarted) / 1000000

    assertEquals(second, first)
    assert(!first.asJson.noSpaces.contains(System.getProperty("user.home")))
    println(s"infer-type fresh compiler observations: first=${firstMillis}ms second=${secondMillis}ms")

  private def query(file: Path, marker: String, delta: Int): InferTypeResult =
    queryAt(file, markerPosition(file, marker, delta))

  private def queryAt(file: Path, position: InferTypeQueryPosition): InferTypeResult =
    service.inferType(InferTypeRequest(file, position.line, position.column)).fold(message => fail(message), identity)

  private def markerPosition(file: Path, marker: String, delta: Int): InferTypeQueryPosition =
    val source = Files.readString(file)
    val markerOffset = source.indexOf(marker)
    assert(markerOffset >= 0, clue(marker))
    assertEquals(source.indexOf(marker, markerOffset + 1), -1, clue(s"marker must be unique: $marker"))
    positionAt(source, markerOffset + delta)

  private def positionAt(source: String, offset: Int): InferTypeQueryPosition =
    val lineStart = source.lastIndexOf('\n', math.max(0, offset - 1)) + 1
    val line = source.substring(0, lineStart).count(_ == '\n') + 1
    InferTypeQueryPosition(line, offset - lineStart + 1)

  private def fixture: Path =
    Path.of("modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala")

  private def externalFixture: Path =
    Path.of("modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/ExternalTypeFixture.scala")

  private def erroneousFixture: Path =
    Path.of("modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/ErroneousFixture.scala")
