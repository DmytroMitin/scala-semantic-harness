package semantic.harness.semanticdb_reader

import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class SemanticdbReaderSuite extends munit.FunSuite:
  test("reads checked-in SemanticDB fixture into repo-owned summary"):
    val summary = readFixture()

    assertEquals(summary.uri, "Main.scala")
    assert(summary.symbols.nonEmpty)
    assert(summary.occurrences.nonEmpty)
    assert(summary.occurrences.exists(_.range.nonEmpty))
    assert(summary.symbols.exists(_.symbol == "example/Main."))
    assert(summary.symbols.exists(symbol => symbol.symbol == "example/Main." && symbol.displayName == "Main"))
    assert(summary.occurrences.exists(_.symbol == "example/Main."))
    assertEquals(summary.schemaVersion, SemanticFileSummary.SchemaVersion)

  test("SemanticFileSummary encodes schemaVersion and decodes legacy JSON"):
    val summary = SemanticFileSummary(
      uri = "Main.scala",
      symbols = Nil,
      occurrences = Nil
    )

    val json = summary.asJson.noSpaces
    assert(json.contains(""""schemaVersion":"semantic-scala.symbols-result.v1""""))
    assertEquals(decode[SemanticFileSummary](json), Right(summary))

    val legacy =
      """{
        |  "uri": "Main.scala",
        |  "symbols": [],
        |  "occurrences": []
        |}""".stripMargin

    assertEquals(decode[SemanticFileSummary](legacy).map(_.schemaVersion), Right(SemanticFileSummary.SchemaVersion))

  test("returns clear error for missing SemanticDB file"):
    val result = SemanticdbReader.read(Path.of("modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/missing.semanticdb"))

    assert(result.left.exists(_.contains("does not exist")))

  test("semanticdb status reports unavailable workspace with no SemanticDB files"):
    val workspace = Files.createTempDirectory("semanticdb-status-empty")

    val report = SemanticdbStatus.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(report.schemaVersion, SemanticdbStatusReport.SchemaVersion)
    assertEquals(report.status, SemanticdbStatus.StatusUnavailable)
    assertEquals(report.semanticdbFiles, 0)
    assertEquals(report.parseableFiles, 0)
    assertEquals(report.unparseableFiles, 0)
    assertEquals(report.candidates, Nil)

  test("semanticdb status reports parseable files in deterministic order"):
    val workspace = Files.createTempDirectory("semanticdb-status-available")
    val second = workspace.resolve("target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Z.scala.semanticdb")
    val first = workspace.resolve("target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/A.scala.semanticdb")
    Files.createDirectories(second.getParent)
    Files.createDirectories(first.getParent)
    Files.copy(fixturePath, second)
    Files.copy(fixturePath, first)

    val report = SemanticdbStatus.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbStatus.StatusAvailable)
    assertEquals(report.semanticdbFiles, 2)
    assertEquals(report.parseableFiles, 2)
    assertEquals(report.unparseableFiles, 0)
    assertEquals(
      report.candidates.map(_.semanticdb),
      List(
        "target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/A.scala.semanticdb",
        "target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Z.scala.semanticdb"
      )
    )
    assert(report.candidates.forall(_.parseStatus == "Parsed"))
    assert(report.candidates.forall(_.uri.contains("Main.scala")))
    assert(report.sourceRoots.contains("src/main/scala"))

  test("semanticdb status preserves partial results for unparseable files"):
    val workspace = Files.createTempDirectory("semanticdb-status-partial")
    val valid = workspace.resolve("target/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb")
    val invalid = workspace.resolve("target/classes/META-INF/semanticdb/src/main/scala/example/Broken.scala.semanticdb")
    Files.createDirectories(valid.getParent)
    Files.copy(fixturePath, valid)
    Files.writeString(invalid, "not a semanticdb payload")

    val report = SemanticdbStatus.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbStatus.StatusPartial)
    assertEquals(report.semanticdbFiles, 2)
    assertEquals(report.parseableFiles, 1)
    assertEquals(report.unparseableFiles, 1)
    assert(report.candidates.exists(candidate => candidate.semanticdb.endsWith("Broken.scala.semanticdb") && candidate.parseStatus == "Unparseable"))
    assert(report.candidates.exists(candidate => candidate.error.exists(_.nonEmpty)))

  test("semanticdb status reports unparseable when no candidate parses"):
    val workspace = Files.createTempDirectory("semanticdb-status-unparseable")
    val invalid = workspace.resolve("target/classes/META-INF/semanticdb/Broken.scala.semanticdb")
    Files.createDirectories(invalid.getParent)
    Files.writeString(invalid, "not a semanticdb payload")

    val report = SemanticdbStatus.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbStatus.StatusUnparseable)
    assertEquals(report.semanticdbFiles, 1)
    assertEquals(report.parseableFiles, 0)
    assertEquals(report.unparseableFiles, 1)

  test("semanticdb status v2 reports all documents while v1 preserves first-document semantics"):
    val workspace = Files.createTempDirectory("semanticdb-status-v2-documents")
    val semanticdb = workspace.resolve("target/classes/Multi.scala.semanticdb")
    writeSemanticdb(
      semanticdb,
      Seq(
        TextDocument(
          uri = "src/main/scala/example/First.scala",
          symbols = Seq(SymbolInformation()),
          occurrences = Seq(SymbolOccurrence())
        ),
        TextDocument(
          uri = "src/main/scala/example/Second.scala",
          symbols = Seq(SymbolInformation(), SymbolInformation()),
          occurrences = Seq(SymbolOccurrence(), SymbolOccurrence(), SymbolOccurrence())
        )
      )
    )

    val v1 = SemanticdbStatus.inspect(workspace).fold(message => fail(message), identity)
    val v2 = SemanticdbStatusV2.inspect(workspace).fold(message => fail(message), identity)
    val candidate = v2.candidates.head

    assertEquals(v1.schemaVersion, SemanticdbStatusReport.SchemaVersion)
    assertEquals(v1.candidates.head.uri, Some("src/main/scala/example/First.scala"))
    assertEquals(v1.candidates.head.symbols, Some(1))
    assertEquals(v1.candidates.head.occurrences, Some(1))
    assertEquals(v2.schemaVersion, SemanticdbStatusReportV2.SchemaVersion)
    assertEquals(candidate.documentCount, 2)
    assertEquals(candidate.documentsParsed, 2)
    assertEquals(candidate.documentsIgnored, 0)
    assertEquals(
      candidate.documentUris,
      List("src/main/scala/example/First.scala", "src/main/scala/example/Second.scala")
    )
    assertEquals(candidate.documents.map(_.symbols), List(1, 2))
    assertEquals(candidate.documents.map(_.occurrences), List(1, 3))
    assertEquals(candidate.totalSymbols, 3)
    assertEquals(candidate.totalOccurrences, 4)

  test("semanticdb status v2 reports raw metadata and exact duplicate groups"):
    val workspace = Files.createTempDirectory("semanticdb-status-v2-duplicates")
    val first = workspace.resolve("a/Main.scala.semanticdb")
    val second = workspace.resolve("b/Main.scala.semanticdb")
    Files.createDirectories(first.getParent)
    Files.createDirectories(second.getParent)
    Files.copy(fixturePath, first)
    Files.copy(fixturePath, second)

    val report = SemanticdbStatusV2.inspect(workspace).fold(message => fail(message), identity)
    val expectedHash = "sha256:37e7fb7f4217fed1f1cd4b188d59b8b0deca1801376ab50f0dd5fa0270e212d2"

    assertEquals(report.artifactStatus, SemanticdbStatus.StatusAvailable)
    assertEquals(report.coverageStatus, SemanticdbStatusV2.CoverageNotAssessed)
    assertEquals(report.semanticdbFiles, 2)
    assertEquals(report.uniqueContentFiles, 1)
    assertEquals(report.duplicateFiles, 1)
    assertEquals(report.duplicateGroupCount, 1)
    assertEquals(report.candidates.map(_.semanticdb), List("a/Main.scala.semanticdb", "b/Main.scala.semanticdb"))
    assert(report.candidates.forall(_.sizeBytes.contains(73L)))
    assert(report.candidates.forall(_.contentHash.contains(expectedHash)))
    assert(report.candidates.forall(_.duplicateGroupId.contains(expectedHash)))
    assert(report.candidates.forall(_.duplicateCount.contains(2)))
    assert(report.candidates.forall(_.duplicateRepresentative.contains("a/Main.scala.semanticdb")))
    assertEquals(report.duplicateGroups.map(_.representative), List("a/Main.scala.semanticdb"))
    assertEquals(report.duplicateGroups.head.samplePaths, List("a/Main.scala.semanticdb", "b/Main.scala.semanticdb"))

  test("semanticdb status v2 does not group similar parsed content with different raw bytes"):
    val workspace = Files.createTempDirectory("semanticdb-status-v2-distinct")
    val first = workspace.resolve("a/Same.scala.semanticdb")
    val second = workspace.resolve("b/Same.scala.semanticdb")
    writeSemanticdb(first, Seq(TextDocument(uri = "Same.scala", text = "first")))
    writeSemanticdb(second, Seq(TextDocument(uri = "Same.scala", text = "second")))

    val report = SemanticdbStatusV2.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(report.semanticdbFiles, 2)
    assertEquals(report.uniqueContentFiles, 2)
    assertEquals(report.duplicateFiles, 0)
    assertEquals(report.duplicateGroupCount, 0)
    assertEquals(report.duplicateGroups, Nil)
    assertEquals(report.candidates.map(_.documentUris), List(List("Same.scala"), List("Same.scala")))
    assertEquals(report.candidates.flatMap(_.contentHash).distinct.size, 2)

  test("semanticdb status v2 bounds candidates without truncating aggregate duplicate facts"):
    val workspace = Files.createTempDirectory("semanticdb-status-v2-bounded")
    List("c", "a", "b").foreach { directory =>
      val path = workspace.resolve(s"$directory/Main.scala.semanticdb")
      Files.createDirectories(path.getParent)
      Files.copy(fixturePath, path)
    }

    val report = SemanticdbStatusV2
      .inspect(workspace, candidateLimit = 2, duplicatePathLimit = 2)
      .fold(message => fail(message), identity)

    assertEquals(report.semanticdbFiles, 3)
    assertEquals(report.totalCandidates, 3)
    assertEquals(report.returnedCandidates, 2)
    assertEquals(report.candidateLimit, 2)
    assert(report.candidatesTruncated)
    assertEquals(report.candidates.map(_.semanticdb), List("a/Main.scala.semanticdb", "b/Main.scala.semanticdb"))
    assertEquals(report.uniqueContentFiles, 1)
    assertEquals(report.duplicateFiles, 2)
    assertEquals(report.duplicateGroupCount, 1)
    assertEquals(report.duplicateGroups.head.fileCount, 3)
    assertEquals(
      report.duplicateGroups.head.samplePaths,
      List("a/Main.scala.semanticdb", "b/Main.scala.semanticdb")
    )
    assertEquals(report.duplicateGroups.head.returnedPaths, 2)
    assert(report.duplicateGroups.head.pathsTruncated)

  test("semanticdb status v2 preserves raw metadata for unparseable candidates"):
    val workspace = Files.createTempDirectory("semanticdb-status-v2-partial")
    val valid = workspace.resolve("a/Main.scala.semanticdb")
    val invalid = workspace.resolve("b/Broken.scala.semanticdb")
    Files.createDirectories(valid.getParent)
    Files.createDirectories(invalid.getParent)
    Files.copy(fixturePath, valid)
    Files.writeString(invalid, "not a semanticdb payload")

    val report = SemanticdbStatusV2.inspect(workspace).fold(message => fail(message), identity)
    val broken = report.candidates.find(_.semanticdb == "b/Broken.scala.semanticdb").getOrElse(fail("missing broken candidate"))

    assertEquals(report.artifactStatus, SemanticdbStatus.StatusPartial)
    assertEquals(report.parseableFiles, 1)
    assertEquals(report.unparseableFiles, 1)
    assert(broken.sizeBytes.exists(_ > 0))
    assert(broken.contentHash.exists(_.matches("sha256:[0-9a-f]{64}")))
    assert(broken.error.exists(error => error.nonEmpty && error.length <= 240))
    assertEquals(broken.documents, Nil)

  test("semanticdb status v2 reports unavailable for an empty workspace"):
    val workspace = Files.createTempDirectory("semanticdb-status-v2-empty")

    val report = SemanticdbStatusV2.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(report.artifactStatus, SemanticdbStatus.StatusUnavailable)
    assertEquals(report.coverageStatus, SemanticdbStatusV2.CoverageNotAssessed)
    assertEquals(report.semanticdbFiles, 0)
    assertEquals(report.uniqueContentFiles, 0)
    assertEquals(report.duplicateFiles, 0)
    assertEquals(report.duplicateGroupCount, 0)
    assertEquals(report.totalCandidates, 0)
    assertEquals(report.returnedCandidates, 0)
    assertEquals(report.candidates, Nil)
    assertEquals(report.duplicateGroups, Nil)

  test("semanticdb for source reports unavailable for an empty workspace"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-empty")
    val source = writeSource(workspace, "src/main/scala/example/Main.scala")

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.schemaVersion, SemanticdbForSourceReport.SchemaVersion)
    assertEquals(report.status, SemanticdbForSource.StatusUnavailable)
    assertEquals(report.semanticdbFiles, 0)
    assertEquals(report.matches, Nil)

  test("semanticdb for source finds a unique parsed URI match"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-uri")
    val relativeSource = "src/main/scala/example/Main.scala"
    val source = writeSource(workspace, relativeSource)
    val semanticdb = workspace.resolve("target/classes/semanticdb/Main.scala.semanticdb")
    writeSemanticdb(semanticdb, relativeSource)

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbForSource.StatusUniqueMatch)
    assertEquals(report.sourceRelativePath, Some(relativeSource))
    assertEquals(report.matches.map(_.matchKind), List(SemanticdbForSource.MatchUriExact))
    assertEquals(report.matches.head.uri, Some(relativeSource))

  test("semanticdb for source finds a unique META-INF suffix match"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-meta-inf")
    val relativeSource = "src/main/scala/example/Main.scala"
    val source = writeSource(workspace, relativeSource)
    val semanticdb = workspace.resolve(s"target/classes/META-INF/semanticdb/$relativeSource.semanticdb")
    Files.createDirectories(semanticdb.getParent)
    Files.copy(fixturePath, semanticdb)

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbForSource.StatusUniqueMatch)
    assertEquals(report.matches.map(_.matchKind), List(SemanticdbForSource.MatchMetaInfSuffix))

  test("semanticdb for source matches a common source-root suffix"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-root")
    val relativeSource = "module-a/src/test/scala/example/MainSuite.scala"
    val source = writeSource(workspace, relativeSource)
    val semanticdb = workspace.resolve("target/generated/src/test/scala/example/MainSuite.scala.semanticdb")
    writeSemanticdb(semanticdb, "generated-prefix/src/test/scala/example/MainSuite.scala")

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbForSource.StatusUniqueMatch)
    assertEquals(report.matches.map(_.matchKind), List(SemanticdbForSource.MatchSourceRootSuffix))

  test("semanticdb for source reports ambiguous matches in deterministic order"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-ambiguous")
    val relativeSource = "src/main/scala/example/Main.scala"
    val source = writeSource(workspace, relativeSource)
    val second = workspace.resolve("z-target/Main.scala.semanticdb")
    val first = workspace.resolve("a-target/Main.scala.semanticdb")
    writeSemanticdb(second, relativeSource)
    writeSemanticdb(first, relativeSource)

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbForSource.StatusAmbiguous)
    assertEquals(report.matches.map(_.semanticdb), List("a-target/Main.scala.semanticdb", "z-target/Main.scala.semanticdb"))
    assert(report.matches.forall(_.matchKind == SemanticdbForSource.MatchUriExact))

  test("semanticdb for source reports no match without using filenames alone"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-no-match")
    val source = writeSource(workspace, "src/main/scala/example/Main.scala")
    val semanticdb = workspace.resolve("target/classes/Other.scala.semanticdb")
    writeSemanticdb(semanticdb, "src/main/scala/example/Other.scala")

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbForSource.StatusNoMatch)
    assertEquals(report.matches, Nil)
    assertEquals(report.parseableFiles, 1)

  test("semanticdb for source preserves bounded unparseable path matches"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-unparseable")
    val relativeSource = "src/main/scala/example/Main.scala"
    val source = writeSource(workspace, relativeSource)
    val semanticdb = workspace.resolve(s"target/classes/META-INF/semanticdb/$relativeSource.semanticdb")
    Files.createDirectories(semanticdb.getParent)
    Files.writeString(semanticdb, "not a semanticdb payload")

    val report = SemanticdbForSource.inspect(workspace, source).fold(message => fail(message), identity)

    assertEquals(report.status, SemanticdbForSource.StatusPartial)
    assertEquals(report.unparseableFiles, 1)
    assertEquals(report.matches.map(_.parseStatus), List("Unparseable"))
    assertEquals(report.matches.map(_.matchKind), List(SemanticdbForSource.MatchMetaInfSuffix))
    assert(report.matches.head.error.exists(error => error.nonEmpty && error.length <= 240))
    assert(report.warnings.nonEmpty)

  test("semanticdb for source rejects invalid workspace and source paths"):
    val workspace = Files.createTempDirectory("semanticdb-for-source-invalid")
    val source = writeSource(workspace, "src/main/scala/example/Main.scala")

    assert(SemanticdbForSource.inspect(workspace.resolve("missing"), source).left.exists(_.contains("Workspace does not exist")))
    assert(SemanticdbForSource.inspect(workspace, workspace.resolve("missing.scala")).left.exists(_.contains("Source file does not exist")))

  private def readFixture(): SemanticFileSummary =
    SemanticdbReader
      .read(fixturePath)
      .fold(message => fail(message), identity)

  private def fixturePath: Path =
    Path.of("modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb")

  private def writeSource(workspace: Path, relative: String): Path =
    val source = workspace.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, "package example\nobject Main\n")
    source

  private def writeSemanticdb(path: Path, uri: String): Path =
    writeSemanticdb(path, Seq(TextDocument(uri = uri)))

  private def writeSemanticdb(path: Path, documents: Seq[TextDocument]): Path =
    Files.createDirectories(path.getParent)
    Files.write(path, TextDocuments(documents = documents).toByteArray)
    path
