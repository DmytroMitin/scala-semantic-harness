package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class SemanticdbCoverageSuite extends munit.FunSuite:
  test("empty workspace has no inventory sources"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-empty")

    val result = inspect(workspace)

    assertEquals(result.schemaVersion, SemanticdbCoverageReport.SchemaVersion)
    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusNoInventorySources)
    assertEquals(result.sourceFiles, 0)
    assertEquals(result.semanticdbArtifactFiles, 0)
    assertEquals(result.rawDocumentEntries, 0)
    assertEquals(result.uniqueDocumentEvidence, 0)
    assertEquals(result.sources, Nil)
    assertEquals(result.unmatchedDocuments, Nil)

  test("source inventory without SemanticDB reports all sources uncovered"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-no-documents")
    writeSource(workspace, "src/main/scala/example/Main.scala")
    writeSource(workspace, "src/main/java/example/Helper.java")

    val result = inspect(workspace)

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusNoSemanticdbDocuments)
    assertEquals(result.sourceFiles, 2)
    assertEquals(result.coveredSourceFiles, 0)
    assertEquals(result.uncoveredSourceFiles, 2)
    assertEquals(result.sources.map(_.language), List("Java", "Scala"))
    assert(result.sources.forall(_.coverage == SemanticdbCoverage.SourceUncovered))

  test("exact multi-segment URI uniquely covers a source"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-exact")
    val source = "src/main/scala/example/Main.scala"
    writeSource(workspace, source)
    writeSemanticdb(
      workspace.resolve("target/classes/Main.scala.semanticdb"),
      Seq(
        TextDocument(
          uri = source,
          symbols = Seq(SymbolInformation()),
          occurrences = Seq(SymbolOccurrence(), SymbolOccurrence())
        )
      )
    )

    val result = inspect(workspace)
    val entry = result.sources.head

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusCompleteWithinInventory)
    assertEquals(result.coveredSourceFiles, 1)
    assertEquals(entry.coverage, SemanticdbCoverage.SourceCovered)
    assertEquals(entry.matchKind, Some(SemanticdbCoverage.MatchUriExact))
    assertEquals(entry.matches.map(_.symbols), List(1))
    assertEquals(entry.matches.map(_.occurrences), List(2))

  test("unique multi-segment URI suffix covers a source"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-suffix")
    val source = "module-a/src/main/scala/example/Main.scala"
    writeSource(workspace, source)
    writeSemanticdb(
      workspace.resolve("target/classes/Main.scala.semanticdb"),
      Seq(TextDocument(uri = "src/main/scala/example/Main.scala"))
    )

    val result = inspect(workspace)

    assertEquals(result.sources.head.coverage, SemanticdbCoverage.SourceCovered)
    assertEquals(result.sources.head.matchKind, Some(SemanticdbCoverage.MatchUriSuffix))

  test("basename-only URI does not cover sources"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-basename")
    writeSource(workspace, "module-a/src/main/scala/example/Main.scala")
    writeSource(workspace, "module-b/src/test/scala/example/Main.scala")
    writeSemanticdb(
      workspace.resolve("target/classes/Main.scala.semanticdb"),
      Seq(TextDocument(uri = "Main.scala"))
    )

    val result = inspect(workspace)

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusNoCoveredSources)
    assertEquals(result.uncoveredSourceFiles, 2)
    assertEquals(result.matchedDocumentEvidence, 0)
    assertEquals(result.unmatchedDocumentEvidence, 1)
    assert(result.sources.forall(_.matches.isEmpty))
    assert(result.warnings.exists(_.contains("basename-only identity is not used")))

  test("one URI suffix matching two sources is explicit ambiguity"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-ambiguous-suffix")
    val uri = "src/main/scala/example/Main.scala"
    writeSource(workspace, s"module-a/$uri")
    writeSource(workspace, s"module-b/$uri")
    writeSemanticdb(
      workspace.resolve("target/classes/Main.scala.semanticdb"),
      Seq(TextDocument(uri = uri))
    )

    val result = inspect(workspace)

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusNoCoveredSources)
    assertEquals(result.ambiguousSourceFiles, 2)
    assertEquals(result.ambiguousDocumentEvidence, 1)
    assertEquals(result.matchedDocumentEvidence, 1)
    assertEquals(result.unmatchedDocumentEvidence, 0)
    assert(result.sources.forall(_.coverage == SemanticdbCoverage.SourceAmbiguous))
    assert(result.sources.forall(_.matches.map(_.matchKind) == List(SemanticdbCoverage.MatchUriSuffix)))

  test("exact duplicate artifacts count once as unique document evidence"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-duplicates")
    val source = "src/main/scala/example/Main.scala"
    writeSource(workspace, source)
    val first = writeSemanticdb(
      workspace.resolve("target/a/Main.scala.semanticdb"),
      Seq(TextDocument(uri = source))
    )
    val second = workspace.resolve("target/b/Main.scala.semanticdb")
    Files.createDirectories(second.getParent)
    Files.copy(first, second)

    val result = inspect(workspace)
    val evidence = result.sources.head.matches.head

    assertEquals(result.semanticdbArtifactFiles, 2)
    assertEquals(result.uniqueArtifactContents, 1)
    assertEquals(result.rawDocumentEntries, 2)
    assertEquals(result.uniqueDocumentEvidence, 1)
    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusCompleteWithinInventory)
    assertEquals(evidence.artifactCopies, 2)
    assertEquals(
      evidence.artifactPathSamples,
      List("target/a/Main.scala.semanticdb", "target/b/Main.scala.semanticdb")
    )

  test("same URI in byte-different artifacts remains ambiguous evidence"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-distinct")
    val source = "src/main/scala/example/Main.scala"
    writeSource(workspace, source)
    writeSemanticdb(
      workspace.resolve("target/a/Main.scala.semanticdb"),
      Seq(TextDocument(uri = source, text = "first"))
    )
    writeSemanticdb(
      workspace.resolve("target/b/Main.scala.semanticdb"),
      Seq(TextDocument(uri = source, text = "second"))
    )

    val result = inspect(workspace)
    val entry = result.sources.head

    assertEquals(result.uniqueArtifactContents, 2)
    assertEquals(result.uniqueDocumentEvidence, 2)
    assertEquals(result.matchedDocumentEvidence, 2)
    assertEquals(result.ambiguousSourceFiles, 1)
    assertEquals(entry.coverage, SemanticdbCoverage.SourceAmbiguous)
    assertEquals(entry.matches.map(_.contentHash).distinct.size, 2)
    assertEquals(entry.matchKind, None)

  test("covered and uncovered sources produce partial coverage"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-partial")
    val covered = "src/main/scala/example/Covered.scala"
    writeSource(workspace, covered)
    writeSource(workspace, "src/main/scala/example/Uncovered.scala")
    writeSemanticdb(
      workspace.resolve("target/classes/Covered.scala.semanticdb"),
      Seq(TextDocument(uri = covered))
    )

    val result = inspect(workspace)

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusPartial)
    assertEquals(result.coveredSourceFiles, 1)
    assertEquals(result.uncoveredSourceFiles, 1)
    assertEquals(result.ambiguousSourceFiles, 0)

  test("all-document evidence can complete the explicit inventory"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-complete")
    val first = "src/main/scala/example/First.scala"
    val second = "src/test/java/example/Second.java"
    writeSource(workspace, first)
    writeSource(workspace, second)
    writeSemanticdb(
      workspace.resolve("target/classes/Multi.semanticdb"),
      Seq(TextDocument(uri = first), TextDocument(uri = second))
    )

    val result = inspect(workspace)

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusCompleteWithinInventory)
    assertEquals(result.sourceFiles, 2)
    assertEquals(result.coveredSourceFiles, 2)
    assertEquals(result.rawDocumentEntries, 2)
    assertEquals(result.uniqueDocumentEvidence, 2)
    assertEquals(result.unmatchedDocumentEvidence, 0)

  test("Scala under test resources is inventoried but basename evidence stays unmatched"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-test-resources")
    val source = "src/test/resources/semanticdb-fixtures/Main.scala"
    writeSource(workspace, source)
    writeSemanticdb(
      workspace.resolve("target/test-classes/Main.scala.semanticdb"),
      Seq(TextDocument(uri = "Main.scala"))
    )

    val result = inspect(workspace)

    assertEquals(result.sources.map(_.source), List(source))
    assertEquals(result.sources.head.coverage, SemanticdbCoverage.SourceUncovered)
    assertEquals(result.unmatchedDocumentEvidence, 1)

  test("generated and metadata directories are excluded while custom hidden roots are included"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-exclusions")
    writeSource(workspace, "target/Generated.scala")
    writeSource(workspace, ".git/TrackedLooking.scala")
    writeSource(workspace, ".metals/Indexed.scala")
    writeSource(workspace, "custom/Included.scala")
    writeSource(workspace, ".custom/Hidden.scala")

    val result = inspect(workspace)

    assertEquals(
      result.sources.map(_.source),
      List(".custom/Hidden.scala", "custom/Included.scala")
    )
    assertEquals(result.inventoryBasis.excludedDirectoryNames, SemanticdbCoverage.ExcludedDirectoryNames)
    assertEquals(result.inventoryBasis.followsSymbolicLinks, false)
    assertEquals(result.inventoryBasis.includesFilesOutsideConventionalRoots, true)

  test("low limits bound arrays without changing complete counts or status"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-bounds")
    List("A.scala", "B.scala", "C.scala").foreach(name => writeSource(workspace, s"custom/$name"))
    val duplicate = writeSemanticdb(
      workspace.resolve("target/a/One.semanticdb"),
      Seq(TextDocument(uri = "One.scala"))
    )
    val duplicateCopy = workspace.resolve("target/b/One.semanticdb")
    Files.createDirectories(duplicateCopy.getParent)
    Files.copy(duplicate, duplicateCopy)
    writeSemanticdb(
      workspace.resolve("target/c/Two.semanticdb"),
      Seq(TextDocument(uri = "Two.scala"))
    )

    val result = inspect(
      workspace,
      sourceEntryLimit = 2,
      documentEntryLimit = 1,
      artifactPathSampleLimit = 1
    )

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusNoCoveredSources)
    assertEquals(result.sourceFiles, 3)
    assertEquals(result.totalSourceEntries, 3)
    assertEquals(result.returnedSourceEntries, 2)
    assert(result.sourceEntriesTruncated)
    assertEquals(result.sources.map(_.source), List("custom/A.scala", "custom/B.scala"))
    assertEquals(result.uniqueDocumentEvidence, 2)
    assertEquals(result.totalUnmatchedDocumentEntries, 2)
    assertEquals(result.returnedUnmatchedDocumentEntries, 1)
    assert(result.unmatchedDocumentEntriesTruncated)
    assert(result.unmatchedDocuments.head.artifactPathsTruncated || result.warnings.exists(_.contains("Artifact path samples were truncated")))
    assert(result.warnings.exists(_.contains("Source entries were truncated")))
    assert(result.warnings.exists(_.contains("Unmatched document entries were truncated")))

  test("unchanged workspace inspection is deterministic"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-deterministic")
    val source = "module/src/main/scala/example/Main.scala"
    writeSource(workspace, source)
    writeSemanticdb(
      workspace.resolve("target/classes/Main.scala.semanticdb"),
      Seq(TextDocument(uri = "src/main/scala/example/Main.scala"))
    )

    val first = inspect(workspace)
    val second = inspect(workspace)

    assertEquals(second, first)

  test("unparseable artifacts do not prevent factual coverage"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-unparseable")
    val source = "src/main/scala/example/Main.scala"
    writeSource(workspace, source)
    writeSemanticdb(
      workspace.resolve("target/a/Main.scala.semanticdb"),
      Seq(TextDocument(uri = source))
    )
    val broken = workspace.resolve("target/b/Broken.scala.semanticdb")
    Files.createDirectories(broken.getParent)
    Files.writeString(broken, "not a semanticdb payload")

    val result = inspect(workspace)

    assertEquals(result.coverageStatus, SemanticdbCoverage.StatusCompleteWithinInventory)
    assertEquals(result.semanticdbArtifactFiles, 2)
    assertEquals(result.uniqueArtifactContents, 2)
    assertEquals(result.rawDocumentEntries, 1)
    assertEquals(result.uniqueDocumentEvidence, 1)
    assert(result.warnings.exists(_.contains("has no parseable document evidence")))
    assert(result.warnings.forall(_.length <= 240))

  test("invalid workspace and negative limits fail clearly"):
    val workspace = Files.createTempDirectory("semanticdb-coverage-invalid")

    assert(SemanticdbCoverage.inspect(workspace.resolve("missing")).left.exists(_.contains("Workspace does not exist")))
    assert(SemanticdbCoverage.inspect(workspace, sourceEntryLimit = -1).left.exists(_.contains("non-negative")))

  private def inspect(
    workspace: Path,
    sourceEntryLimit: Int = SemanticdbCoverage.DefaultSourceEntryLimit,
    documentEntryLimit: Int = SemanticdbCoverage.DefaultDocumentEntryLimit,
    artifactPathSampleLimit: Int = SemanticdbCoverage.DefaultArtifactPathSampleLimit
  ): SemanticdbCoverageReport =
    SemanticdbCoverage
      .inspect(workspace, sourceEntryLimit, documentEntryLimit, artifactPathSampleLimit)
      .fold(message => fail(message), identity)

  private def writeSource(workspace: Path, relative: String): Path =
    val source = workspace.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, "package example\nobject Example\n")
    source

  private def writeSemanticdb(path: Path, documents: Seq[TextDocument]): Path =
    Files.createDirectories(path.getParent)
    Files.write(path, TextDocuments(documents = documents).toByteArray)
    path
