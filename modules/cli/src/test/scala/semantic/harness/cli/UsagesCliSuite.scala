package semantic.harness.cli

import io.circe.ACursor
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.collection.mutable.ListBuffer
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class UsagesCliSuite extends munit.FunSuite:
  private val Target = "task086/api/Service#run()."
  private val OtherOwner = "task086/other/Service#run()."
  private val Overload = "task086/api/Service#run(+1)."

  test("real CLI reproduces the frozen Task 086 exact-symbol duplicate-aware decision case"):
    val fixture = Fixture("frozen")
    val serviceText = "run\n"
    val mainText = "run other overload\n"
    val testText = "run\n"
    fixture.source("api/src/main/scala/task086/Service.scala", serviceText, "api", "main")
    fixture.source("app/src/main/scala/task086/Main.scala", mainText, "app", "main")
    fixture.source("app/src/test/scala/task086/MainSuite.scala", testText, "app", "test")
    fixture.artifact(
      "out/api.semanticdb",
      List(document("api/src/main/scala/task086/Service.scala", serviceText, List(occ(Target, SymbolOccurrence.Role.DEFINITION, 0, 0, 3)))),
      "api",
      "main"
    )
    val artifact = fixture.artifact(
      "out/main.semanticdb",
      List(document(
        "app/src/main/scala/task086/Main.scala",
        mainText,
        List(
          occ(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 3),
          occ(OtherOwner, SymbolOccurrence.Role.REFERENCE, 0, 4, 9),
          occ(Overload, SymbolOccurrence.Role.REFERENCE, 0, 10, 18)
        )
      )),
      "app",
      "main"
    )
    fixture.copyArtifact(artifact, "out/copy.semanticdb")
    fixture.artifact(
      "out/test.semanticdb",
      List(document("app/src/test/scala/task086/MainSuite.scala", testText, List(occ(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 3)))),
      "app",
      "test"
    )
    fixture.writeManifest(closed = true)

    val result = runJson(fixture, List("--symbol", Target), List("--include-definitions"))
    assertEquals(string(result, "state"), "EvidenceFound")
    assertEquals(array(result, "occurrences").size, 3)
    assertEquals(int(result, "coverage", "matchingOccurrences"), 3)
    assertEquals(int(result, "coverage", "freshDocuments"), 3)
    assertEquals(int(result, "coverage", "duplicateCopies"), 1)
    assertEquals(int(result, "coverage", "uniqueArtifactContents"), 3)
    assertEquals(array(result, "limits", "hits"), Vector.empty)
    val encoded = result.noSpaces
    assert(!encoded.contains(OtherOwner))
    assert(!encoded.contains(Overload))
    assert(!encoded.contains(fixture.root.toString))

  test("real CLI point mode resolves global and document-local identity without raw local IDs"):
    val global = Fixture("point-global")
    val globalText = "run\nrun\n"
    global.source("src/Global.scala", globalText)
    global.artifact(
      "out/global.semanticdb",
      List(document("src/Global.scala", globalText, List(
        occ(Target, SymbolOccurrence.Role.DEFINITION, 0, 0, 3),
        occ(Target, SymbolOccurrence.Role.REFERENCE, 1, 0, 3)
      )))
    )
    global.writeManifest()
    val globalResult = runJson(
      global,
      List("--file", "src/Global.scala", "--line", "1", "--col", "1", "--semanticdb", "out/global.semanticdb")
    )
    assertEquals(string(globalResult, "state"), "EvidenceFound")
    assertEquals(string(globalResult, "targetMode"), "PointSelected")
    assertEquals(string(globalResult, "target", "identityKind"), "Global")
    assertEquals(string(globalResult, "target", "stableSymbol"), Target)

    val local = Fixture("point-local")
    val localText = "local\nlocal\n"
    local.source("src/Local.scala", localText)
    local.artifact(
      "out/local.semanticdb",
      List(document("src/Local.scala", localText, List(
        occ("local0", SymbolOccurrence.Role.DEFINITION, 0, 0, 5),
        occ("local0", SymbolOccurrence.Role.REFERENCE, 1, 0, 5)
      )))
    )
    local.writeManifest()
    val localResult = runJson(
      local,
      List("--file", "src/Local.scala", "--line", "1", "--col", "1", "--semanticdb", "out/local.semanticdb")
    )
    assertEquals(string(localResult, "state"), "EvidenceFound")
    assertEquals(string(localResult, "target", "identityKind"), "LocalDocumentOnly")
    assertEquals(string(localResult, "target", "localSymbolMarker"), "DocumentLocal")
    assert(localResult.hcursor.downField("target").downField("stableSymbol").focus.exists(_.isNull))
    assert(!localResult.noSpaces.contains("local0"))

  test("real CLI preserves unsupported ambiguous and unresolved target states"):
    val explicit = oneOccurrenceFixture("unsupported")
    val unsupported = runJson(explicit, List("--symbol", "local0"))
    assertEquals(string(unsupported, "state"), "UnsupportedConstruct")
    assertEquals(array(unsupported, "occurrences"), Vector.empty)
    assertEquals(string(unsupported, "reasons", 0, "code"), "ExplicitLocalSymbolUnsupported")

    val ambiguous = Fixture("ambiguous")
    val text = "abc\n"
    ambiguous.source("src/A.scala", text)
    ambiguous.artifact(
      "out/a.semanticdb",
      List(document("src/A.scala", text, List(
        occ("a/A#one().", SymbolOccurrence.Role.REFERENCE, 0, 0, 3),
        occ("a/A#two().", SymbolOccurrence.Role.REFERENCE, 0, 0, 3)
      )))
    )
    ambiguous.writeManifest()
    val target = List("--file", "src/A.scala", "--line", "1", "--col", "1", "--semanticdb", "out/a.semanticdb")
    val ambiguousResult = runJson(ambiguous, target)
    assertEquals(string(ambiguousResult, "state"), "TargetAmbiguous")
    assert(ambiguousResult.hcursor.downField("target").focus.exists(_.isNull))

    val unresolvedResult = runJson(
      ambiguous,
      List("--file", "src/A.scala", "--line", "1", "--col", "4", "--semanticdb", "out/a.semanticdb")
    )
    assertEquals(string(unresolvedResult, "state"), "TargetUnresolved")
    assertEquals(array(unresolvedResult, "occurrences"), Vector.empty)

  test("real CLI distinguishes closed zero open incomplete and stale readable evidence"):
    val closed = oneOccurrenceFixture("zero")
    val zero = runJson(closed, List("--symbol", "missing/Symbol#value."))
    assertEquals(string(zero, "state"), "NoUsagesObserved")
    assertEquals(int(zero, "coverage", "matchingOccurrences"), 0)

    closed.writeManifest(closed = false)
    val open = runJson(closed, List("--symbol", "missing/Symbol#value."))
    assertEquals(string(open, "state"), "CoverageIncomplete")
    assertEquals(string(open, "reasons", 0, "code"), "OpenInventory")

    val stale = oneOccurrenceFixture("stale")
    Files.writeString(stale.root.resolve("src/A.scala"), "changed\n", StandardCharsets.UTF_8)
    val staleResult = runJson(stale, List("--symbol", Target))
    assertEquals(string(staleResult, "state"), "ArtifactStaleOrInconsistent")
    assertEquals(array(staleResult, "occurrences").size, 1)
    assert(staleResult.noSpaces.contains("StaleSourceDigest"))

  test("real CLI preserves metadata conflict incomplete mapping and generated selection"):
    val conflict = oneOccurrenceFixture("conflict", writeManifest = false)
    conflict.copyArtifact("out/a.semanticdb", "out/copy.semanticdb", module = "other")
    conflict.writeManifest()
    val conflictResult = runJson(conflict, List("--symbol", Target))
    assertEquals(string(conflictResult, "state"), "ArtifactStaleOrInconsistent")
    assert(conflictResult.noSpaces.contains("ConflictingDuplicateMetadata"))

    val unmapped = Fixture("unmapped")
    val text = "run\n"
    unmapped.source("src/A.scala", text)
    unmapped.artifact(
      "out/a.semanticdb",
      List(document("other/A.scala", text, List(occ(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 3))))
    )
    unmapped.writeManifest()
    val incomplete = runJson(unmapped, List("--symbol", Target))
    assertEquals(string(incomplete, "state"), "CoverageIncomplete")
    assertEquals(array(incomplete, "occurrences").size, 1)
    assert(incomplete.noSpaces.contains("UnmappedDocument"))

    val generated = oneOccurrenceFixture("generated", generated = true)
    val excluded = runJson(generated, List("--symbol", Target))
    assertEquals(string(excluded, "state"), "CoverageIncomplete")
    assert(excluded.noSpaces.contains("SelectorExcludedScope"))
    val included = runJson(generated, List("--symbol", Target), List("--include-generated"))
    assertEquals(string(included, "state"), "EvidenceFound")

  test("real CLI applies definition module source-set and returned-occurrence selectors"):
    val definitions = Fixture("selectors")
    val text = "run\nrun\n"
    definitions.source("src/A.scala", text, "core", "main")
    definitions.artifact(
      "out/a.semanticdb",
      List(document("src/A.scala", text, List(
        occ(Target, SymbolOccurrence.Role.DEFINITION, 0, 0, 3),
        occ(Target, SymbolOccurrence.Role.REFERENCE, 1, 0, 3)
      ))),
      "core",
      "main"
    )
    definitions.writeManifest()
    val defaultResult = runJson(definitions, List("--symbol", Target))
    assertEquals(array(defaultResult, "occurrences").size, 1)
    val included = runJson(definitions, List("--symbol", Target), List("--include-definitions"))
    assertEquals(array(included, "occurrences").size, 2)
    val excluded = runJson(definitions, List("--symbol", Target), List("--module", "other"))
    assertEquals(string(excluded, "state"), "CoverageIncomplete")
    val selected = runJson(definitions, List("--symbol", Target), List("--module", "core", "--source-set", "main"))
    assertEquals(string(selected, "state"), "EvidenceFound")
    val truncated = runJson(definitions, List("--symbol", Target), List("--include-definitions", "--limit", "1"))
    assertEquals(string(truncated, "state"), "Truncated")
    assert(truncated.noSpaces.contains("ReturnedOccurrenceLimit"))

  test("real CLI returns typed JSON and human failures without mixed streams or path leaks"):
    val fixture = oneOccurrenceFixture("failures")
    Files.writeString(fixture.root.resolve("bad.json"), "{\"schemaVersion\":", StandardCharsets.UTF_8)
    val jsonFailure = runRaw(fixture, List("--symbol", Target), json = true, manifest = "bad.json")
    assertEquals(jsonFailure.exitCode, 1)
    assertEquals(jsonFailure.stderr, None)
    assert(jsonFailure.stdout.exists(_.contains("semantic-scala.usages-failure.v1")))
    assert(jsonFailure.stdout.exists(_.contains("InvalidInput")))
    assert(!jsonFailure.stdout.exists(_.contains(fixture.root.toString)))

    val humanFailure = runRaw(fixture, List("--symbol", Target), json = false, manifest = "bad.json")
    assertEquals(humanFailure.exitCode, 1)
    assertEquals(humanFailure.stdout, None)
    assert(humanFailure.stderr.exists(_.startsWith("InvalidInput:")))
    assert(!humanFailure.stderr.exists(_.contains(fixture.root.toString)))

    Files.write(fixture.root.resolve("out/a.semanticdb"), Array[Byte](1, 2, 3, 4))
    val parseFailure = runRaw(fixture, List("--symbol", Target), json = true)
    assertEquals(parseFailure.exitCode, 1)
    assert(parseFailure.stdout.exists(_.contains("ParseFailure")))
    assertEquals(parseFailure.stderr, None)

  test("real CLI human and JSON success agree and repeated output is deterministic and private"):
    val fixture = oneOccurrenceFixture("rendering")
    val first = runRaw(fixture, List("--symbol", Target), json = true)
    val second = runRaw(fixture, List("--symbol", Target), json = true)
    assertEquals(first, second)
    assertEquals(first.exitCode, 0)
    assertEquals(first.stderr, None)
    assert(!first.stdout.exists(_.contains(fixture.root.toString)))

    val human = runRaw(fixture, List("--symbol", Target), json = false)
    assertEquals(human.exitCode, 0)
    assertEquals(human.stderr, None)
    assert(human.stdout.exists(_.contains("EvidenceFound")))
    assert(human.stdout.exists(_.contains("exact ordinary occurrence")))
    assert(!human.stdout.exists(_.contains(fixture.root.toString)))

    val zero = runRaw(fixture, List("--symbol", "missing/Symbol#value."), json = false)
    assert(zero.stdout.exists(_.contains("never means globally unused")))

  test("production schemas are exact promoted copies and usages help preserves exclusions"):
    List("manifest", "result", "failure").foreach { name =>
      val accepted = repositoryFile(
        s"benchmarks/runs/v0/usages-by-symbol-public-cli-contract-schema-design-gate/schemas/usages-$name.v1.schema.json"
      )
      val promoted = repositoryFile(
        s"modules/cli/src/main/resources/semantic-scala/schemas/usages-$name.v1.schema.json"
      )
      assertEquals(Files.readAllBytes(promoted).toList, Files.readAllBytes(accepted).toList, name)
    }
    val help = CliApp.run(List("help", "usages"))
    assertEquals(help.exitCode, 0)
    val text = help.stdout.getOrElse(fail("Missing help"))
    List(
      "exact ordinary", "does not discover artifacts", "does not generate SemanticDB",
      "does not run a build", "does not refresh existing artifacts", "synthetics",
      "inferred/desugared", "selected contexts", "override/dispatch", "never means globally unused",
      "one-based UTF-16", "zero-based UTF-16", "workspace-relative"
    ).foreach(value => assert(text.contains(value), value))

  private def oneOccurrenceFixture(
    label: String,
    generated: Boolean = false,
    writeManifest: Boolean = true
  ): Fixture =
    val fixture = Fixture(label)
    val text = "run\n"
    fixture.source("src/A.scala", text, generated = generated)
    fixture.artifact(
      "out/a.semanticdb",
      List(document("src/A.scala", text, List(occ(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 3)))),
      generated = generated
    )
    if writeManifest then fixture.writeManifest()
    fixture

  private def runJson(
    fixture: Fixture,
    target: List[String],
    selectors: List[String] = Nil
  ): Json =
    val result = runRaw(fixture, target, selectors, json = true)
    assertEquals(result.exitCode, 0, clue(result))
    assertEquals(result.stderr, None)
    parse(result.stdout.getOrElse(fail("Missing stdout"))).fold(error => fail(error.message), identity)

  private def runRaw(
    fixture: Fixture,
    target: List[String],
    selectors: List[String] = Nil,
    json: Boolean,
    manifest: String = "usages.json"
  ): CliResult =
    CliApp.run(
      List("usages", "--workspace", fixture.root.toString, "--manifest", manifest) ++
        target ++ selectors ++ Option.when(json)("--json").toList
    )

  private def string(json: Json, fields: String*): String =
    fields.foldLeft[ACursor](json.hcursor)((cursor, field) => cursor.downField(field))
      .as[String].fold(error => fail(error.message), identity)

  private def string(json: Json, arrayField: String, index: Int, field: String): String =
    json.hcursor.downField(arrayField).downN(index).downField(field).as[String]
      .fold(error => fail(error.message), identity)

  private def int(json: Json, fields: String*): Int =
    fields.foldLeft[ACursor](json.hcursor)((cursor, field) => cursor.downField(field))
      .as[Int].fold(error => fail(error.message), identity)

  private def array(json: Json, fields: String*): Vector[Json] =
    fields.foldLeft[ACursor](json.hcursor)((cursor, field) => cursor.downField(field))
      .as[Vector[Json]].fold(error => fail(error.message), identity)

  private def document(
    uri: String,
    text: String,
    occurrences: List[SymbolOccurrence],
    digest: Option[String] = None
  ): TextDocument =
    TextDocument(
      uri = uri,
      md5 = digest.getOrElse(md5(text.getBytes(StandardCharsets.UTF_8))),
      occurrences = occurrences
    )

  private def occ(
    symbol: String,
    role: SymbolOccurrence.Role,
    line: Int,
    start: Int,
    end: Int
  ): SymbolOccurrence =
    SymbolOccurrence(
      range = Some(Range(line, start, line, end)),
      symbol = symbol,
      role = role
    )

  private def md5(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def repositoryFile(relative: String): Path =
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.exists(_))
      .getOrElse(fail(s"Unable to locate repository file: $relative"))

  private final case class Entry(path: String, module: String, sourceSet: String, generated: Boolean)

  private final class Fixture private (val root: Path):
    private val sources = ListBuffer.empty[Entry]
    private val artifacts = ListBuffer.empty[Entry]

    def source(
      relative: String,
      content: String,
      module: String = "core",
      sourceSet: String = "main",
      generated: Boolean = false
    ): Unit =
      val path = root.resolve(relative)
      Files.createDirectories(path.getParent)
      Files.writeString(path, content, StandardCharsets.UTF_8)
      sources += Entry(relative, module, sourceSet, generated)

    def artifact(
      relative: String,
      documents: List[TextDocument],
      module: String = "core",
      sourceSet: String = "main",
      generated: Boolean = false
    ): String =
      val path = root.resolve(relative)
      Files.createDirectories(path.getParent)
      Files.write(path, TextDocuments(documents = documents).toByteArray)
      artifacts += Entry(relative, module, sourceSet, generated)
      relative

    def copyArtifact(
      source: String,
      relative: String,
      module: String = "",
      sourceSet: String = "",
      generated: Option[Boolean] = None
    ): Unit =
      val original = artifacts.find(_.path == source).getOrElse(fail(s"Missing artifact $source"))
      val path = root.resolve(relative)
      Files.createDirectories(path.getParent)
      Files.copy(root.resolve(source), path)
      artifacts += Entry(
        relative,
        Option(module).filter(_.nonEmpty).getOrElse(original.module),
        Option(sourceSet).filter(_.nonEmpty).getOrElse(original.sourceSet),
        generated.getOrElse(original.generated)
      )

    def writeManifest(closed: Boolean = true): Unit =
      def entries(values: ListBuffer[Entry]): String =
        values.map(value =>
          s"""{"path":"${value.path}","module":"${value.module}","sourceSet":"${value.sourceSet}","generated":${value.generated}}"""
        ).mkString("[", ",", "]")
      val json =
        s"""{"schemaVersion":"semantic-scala.usages-manifest.v1","inventoryClosed":$closed,"sources":${entries(sources)},"artifacts":${entries(artifacts)}}"""
      Files.writeString(root.resolve("usages.json"), json, StandardCharsets.UTF_8)

  private object Fixture:
    def apply(label: String): Fixture = new Fixture(Files.createTempDirectory(s"usages-cli-$label-"))
