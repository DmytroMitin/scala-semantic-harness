package semantic.harness.reconciliation

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticdbForSource

class PointEvidenceServiceSuite extends munit.FunSuite:
  test("composes a unique parsed artifact and resolved live point into exact agreement"):
    val fixture = uniqueFixture("point-evidence-exact", staticSymbol = "example/Main.answer.")
    val result = inspect(
      fixture,
      Right(live(fixture.source, Some("example/Main.answer.")))
    )

    assertEquals(result.discovery.status, SemanticdbForSource.StatusUniqueMatch)
    assertEquals(result.selectedArtifact.map(_.semanticdb), Some(fixture.artifactRelative))
    result.livePoint match
      case LivePointEvidence.Resolved(value) =>
        assertEquals(value.symbol, Some("example/Main.answer."))
      case other => fail(s"expected resolved live evidence, got $other")
    result.reconciliation match
      case PointReconciliation.Completed(value) =>
        assertEquals(value.result.status, ReconciliationStatus.ExactMatch)
        assertEquals(value.result.semanticdbSymbol, Some("example/Main.answer."))
        assertEquals(value.result.compilerSymbol, Some("example/Main.answer."))
      case other => fail(s"expected completed reconciliation, got $other")

  test("preserves a unique static and live symbol mismatch without a freshness claim"):
    val fixture = uniqueFixture("point-evidence-mismatch", staticSymbol = "example/Static.answer.")
    val result = inspect(
      fixture,
      Right(live(fixture.source, Some("example/Live.answer.")))
    )

    result.reconciliation match
      case PointReconciliation.Completed(value) =>
        assertEquals(value.result.status, ReconciliationStatus.SymbolMismatch)
        assertEquals(value.result.semanticdbSymbol, Some("example/Static.answer."))
        assertEquals(value.result.compilerSymbol, Some("example/Live.answer."))
      case other => fail(s"expected completed reconciliation, got $other")

  test("preserves no matching artifact while still returning resolved live evidence"):
    val workspace = Files.createTempDirectory("point-evidence-no-candidate")
    val source = writeSource(workspace)
    writeSemanticdb(
      workspace.resolve("target/classes/Other.scala.semanticdb"),
      uri = "src/main/scala/example/Other.scala",
      symbol = "example/Other.answer."
    )

    val result = inspect(Fixture(workspace, source, ""), Right(live(source, Some("example/Main.answer."))))

    assertEquals(result.discovery.status, SemanticdbForSource.StatusNoMatch)
    assertEquals(result.selectedArtifact, None)
    assert(result.livePoint.isInstanceOf[LivePointEvidence.Resolved])
    assertNotAttempted(result, ReconciliationNotAttemptedReason.NoArtifactCandidate)

  test("preserves ambiguous candidates without silently selecting one"):
    val workspace = Files.createTempDirectory("point-evidence-ambiguous")
    val source = writeSource(workspace)
    val uri = "src/main/scala/example/Main.scala"
    writeSemanticdb(workspace.resolve("a/Main.scala.semanticdb"), uri, "example/Main.answer.")
    writeSemanticdb(workspace.resolve("z/Main.scala.semanticdb"), uri, "example/Main.answer.")

    val result = inspect(Fixture(workspace, source, ""), Right(live(source, Some("example/Main.answer."))))

    assertEquals(result.discovery.status, SemanticdbForSource.StatusAmbiguous)
    assertEquals(result.discovery.matches.size, 2)
    assertEquals(result.selectedArtifact, None)
    assertNotAttempted(result, ReconciliationNotAttemptedReason.AmbiguousArtifactCandidates)

  test("preserves partial unparseable path evidence without selecting it"):
    val workspace = Files.createTempDirectory("point-evidence-partial")
    val source = writeSource(workspace)
    val artifact = workspace.resolve(
      "target/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb"
    )
    Files.createDirectories(artifact.getParent)
    Files.writeString(artifact, "not a semanticdb payload")

    val result = inspect(Fixture(workspace, source, ""), Right(live(source, Some("example/Main.answer."))))

    assertEquals(result.discovery.status, SemanticdbForSource.StatusPartial)
    assertEquals(result.discovery.matches.map(_.parseStatus), List("Unparseable"))
    assertEquals(result.selectedArtifact, None)
    assertNotAttempted(result, ReconciliationNotAttemptedReason.PartialOrUnparseableArtifactEvidence)

  test("preserves a live unresolved result and still reconciles a unique usable artifact"):
    val fixture = uniqueFixture("point-evidence-live-unresolved", staticSymbol = "example/Main.answer.")
    val result = inspect(fixture, Right(live(fixture.source, None)))

    result.livePoint match
      case LivePointEvidence.Unresolved(value) => assertEquals(value.symbol, None)
      case other => fail(s"expected unresolved live evidence, got $other")
    result.reconciliation match
      case PointReconciliation.Completed(value) =>
        assertEquals(value.result.status, ReconciliationStatus.RangeMatchOnly)
        assertEquals(value.result.semanticdbSymbol, Some("example/Main.answer."))
        assertEquals(value.result.compilerSymbol, None)
      case other => fail(s"expected completed reconciliation, got $other")

  test("preserves live point unavailability and explains why reconciliation was not attempted"):
    val fixture = uniqueFixture("point-evidence-live-unavailable", staticSymbol = "example/Main.answer.")
    val result = inspect(fixture, Left("presentation compiler unavailable"))

    result.livePoint match
      case LivePointEvidence.Unavailable(reason) =>
        assertEquals(reason, "presentation compiler unavailable")
      case other => fail(s"expected unavailable live evidence, got $other")
    assertNotAttempted(result, ReconciliationNotAttemptedReason.LivePointUnavailable)

  private def inspect(
    fixture: Fixture,
    pointResult: Either[String, SymbolAtResult]
  ): SemanticPointEvidence =
    PointEvidenceService
      .withPointQuery((_, _, _) => pointResult)
      .inspect(
        SemanticPointEvidenceRequest(
          workspace = fixture.workspace,
          sourceFile = fixture.source,
          line = 2,
          column = 7
        )
      )
      .fold(message => fail(message), identity)

  private def assertNotAttempted(
    result: SemanticPointEvidence,
    expected: ReconciliationNotAttemptedReason
  ): Unit =
    result.reconciliation match
      case PointReconciliation.NotAttempted(reason, detail) =>
        assertEquals(reason, expected)
        assert(detail.nonEmpty)
      case other => fail(s"expected reconciliation not attempted, got $other")

  private def uniqueFixture(label: String, staticSymbol: String): Fixture =
    val workspace = Files.createTempDirectory(label)
    val source = writeSource(workspace)
    val relative = "target/classes/Main.scala.semanticdb"
    writeSemanticdb(
      workspace.resolve(relative),
      uri = "src/main/scala/example/Main.scala",
      symbol = staticSymbol
    )
    Fixture(workspace, source, relative)

  private def writeSource(workspace: Path): Path =
    val source = workspace.resolve("src/main/scala/example/Main.scala")
    Files.createDirectories(source.getParent)
    Files.writeString(source, "package example\nobject Main:\n  val answer = 42\n")
    source

  private def writeSemanticdb(path: Path, uri: String, symbol: String): Path =
    val occurrence = SymbolOccurrence(
      range = Some(Range(1, 0, 1, 12)),
      symbol = symbol,
      role = SymbolOccurrence.Role.REFERENCE
    )
    Files.createDirectories(path.getParent)
    Files.write(
      path,
      TextDocuments(documents = Seq(TextDocument(uri = uri, occurrences = Seq(occurrence)))).toByteArray
    )
    path

  private def live(source: Path, symbol: Option[String]): SymbolAtResult =
    SymbolAtResult(
      symbol = symbol,
      displayName = symbol.map(_ => "answer"),
      range = Some(SourceRange(1, 0, 1, 12)),
      source = source.toString
    )

  private final case class Fixture(
    workspace: Path,
    source: Path,
    artifactRelative: String
  )
