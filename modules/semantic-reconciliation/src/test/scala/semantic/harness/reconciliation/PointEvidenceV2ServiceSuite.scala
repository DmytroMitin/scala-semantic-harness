package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SourceArtifactFreshness

class PointEvidenceV2ServiceSuite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val base = "package example\nobject Main:\n  val answer = 42\n"
  private val symbol = "example/Main.answer."

  test("fresh unique point evidence selects Fresh and may complete"):
    val fixture = createFixture("point-v2-fresh", base, md5(base))
    val report = inspect(fixture, Some(symbol))

    assertEquals(report.schemaVersion, PointEvidenceReportV2.SchemaVersion)
    assertEquals(report.selection.status, PointArtifactSelectionStatusV2.SelectedFresh)
    assert(report.reconciliation.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Fresh]))
    assert(report.reconciliation.outcome.isInstanceOf[ReconciliationOutcomeV2.CompletedFresh])

  test("Task-187-like State A keeps live evidence but cannot complete RangeMatchOnly against stale artifact"):
    val fixture = createFixture("point-v2-stale-a", base + "val broken: String = 1\n", md5(base))
    val report = inspect(fixture, None)

    assertEquals(report.selection.status, PointArtifactSelectionStatusV2.NotSelectedStale)
    assertEquals(report.livePoint.status, PointLiveStatus.Unresolved)
    assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.StaleArtifact))

  test("Task-187-like State B cannot complete NoMatch against stale artifact"):
    val fixture = createFixture("point-v2-stale-b", base.replace("answer", "missingAnswer"), md5(base))
    val report = inspect(fixture, None)

    assertEquals(report.selection.status, PointArtifactSelectionStatusV2.NotSelectedStale)
    assertEquals(report.livePoint.status, PointLiveStatus.Unresolved)
    assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.StaleArtifact))

  test("unique Unverifiable selection can only complete qualified"):
    val fixture = createFixture("point-v2-unverifiable", base, "")
    val report = inspect(fixture, Some(symbol))

    assertEquals(report.selection.status, PointArtifactSelectionStatusV2.SelectedUnverifiable)
    assert(report.reconciliation.outcome.isInstanceOf[ReconciliationOutcomeV2.CompletedQualifiedUnverifiable])

  test("ambiguity is preserved before mixed freshness filtering"):
    val fixture = createFixture("point-v2-ambiguous", base, md5(base))
    writeArtifact(fixture.root.resolve("z/Main.scala.semanticdb"), md5("older"))

    val report = inspect(fixture, Some(symbol))

    assertEquals(report.discovery.status, semantic.harness.semanticdb_reader.SemanticdbForSource.StatusAmbiguous)
    assertEquals(report.selection.status, PointArtifactSelectionStatusV2.NotSelectedAmbiguous)
    assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.AmbiguousArtifactCandidates))

  test("no-candidate and partial evidence retain typed not-attempted branches"):
    val noCandidate = createFixture("point-v2-no-candidate", base, md5(base), uri = "src/main/scala/example/Other.scala")
    val noCandidateReport = inspect(noCandidate, Some(symbol))
    assertEquals(noCandidateReport.selection.status, PointArtifactSelectionStatusV2.NotSelectedNoCandidate)
    assertEquals(noCandidateReport.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.NoArtifactCandidate))

    val partial = createFixture("point-v2-partial", base, md5(base), artifactRelative = s"target/META-INF/semanticdb/$relative.semanticdb")
    Files.writeString(partial.artifact, "not semanticdb")
    val partialReport = inspect(partial, Some(symbol))
    assertEquals(partialReport.selection.status, PointArtifactSelectionStatusV2.NotSelectedPartialOrUnparseable)
    assertEquals(partialReport.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.PartialOrUnparseableArtifactEvidence))

  test("source mutation supersedes a computed point reconciliation"):
    val fixture = createFixture("point-v2-source-change", base, md5(base))
    val service = PointEvidenceServiceV2.withPointQueryAndHook(
      (path, _, _, _) => Right(SymbolAtResult(
        symbol = Some(symbol),
        displayName = Some("answer"),
        range = Some(SourceRange(2, 2, 2, 12)),
        source = path.toString
      )),
      () => Files.writeString(fixture.source, base + "// changed\n")
    )

    val report = service.inspect(SemanticPointEvidenceRequest(fixture.root, fixture.source, 3, 7)).fold(fail(_), identity)

    assert(report.reconciliation.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.SourceChangedDuringRequest]))
    assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest))

  private def inspect(fixture: Fixture, liveSymbol: Option[String]): PointEvidenceReportV2 =
    PointEvidenceServiceV2.withPointQuery((path, _, _, _) => Right(SymbolAtResult(
      symbol = liveSymbol,
      displayName = liveSymbol.map(_ => "answer"),
      range = Option.when(liveSymbol.nonEmpty)(SourceRange(2, 2, 2, 12)),
      source = path.toString
    ))).inspect(SemanticPointEvidenceRequest(fixture.root, fixture.source, 3, 7)).fold(fail(_), identity)

  private def createFixture(
    label: String,
    currentSource: String,
    artifactMd5: String,
    uri: String = relative,
    artifactRelative: String = "target/Main.scala.semanticdb"
  ): Fixture =
    val root = Files.createTempDirectory(label)
    val source = root.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, currentSource, StandardCharsets.UTF_8)
    val artifact = root.resolve(artifactRelative)
    writeArtifact(artifact, artifactMd5, uri)
    Fixture(root, source, artifact)

  private def writeArtifact(path: Path, artifactMd5: String, uri: String = relative): Unit =
    Files.createDirectories(path.getParent)
    val occurrence = SymbolOccurrence(
      range = Some(Range(2, 2, 2, 12)),
      symbol = symbol,
      role = SymbolOccurrence.Role.REFERENCE
    )
    Files.write(path, TextDocuments(documents = Seq(TextDocument(
      uri = uri,
      md5 = artifactMd5,
      occurrences = Seq(occurrence)
    ))).toByteArray)

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)).map(byte => f"${byte & 0xff}%02x").mkString

  private final case class Fixture(root: Path, source: Path, artifact: Path)
