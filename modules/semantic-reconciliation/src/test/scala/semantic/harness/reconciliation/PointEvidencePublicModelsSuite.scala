package semantic.harness.reconciliation

import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.file.Path
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReport
import semantic.harness.semanticdb_reader.SemanticdbSourceMatch

class PointEvidencePublicModelsSuite extends munit.FunSuite:
  test("projects complete internal point evidence into the frozen public schema"):
    val internal = evidence(
      discoveryStatus = SemanticdbForSource.StatusUniqueMatch,
      selected = Some(artifact),
      live = LivePointEvidence.Resolved(liveResult),
      reconciliation = PointReconciliation.Completed(reconciliationResult)
    )

    val report = PointEvidenceReport.fromInternal(internal).fold(fail(_), identity)

    assertEquals(report.schemaVersion, PointEvidenceReport.SchemaVersion)
    assertEquals(report.position, PointEvidencePosition(6, 16, "UTF-16"))
    assertEquals(report.selection.status, PointArtifactSelectionStatus.SelectedUniqueParsed)
    assertEquals(report.selection.artifact, Some(artifact))
    assertEquals(report.livePoint.status, PointLiveStatus.Resolved)
    assertEquals(report.livePoint.result, Some(liveResult))
    assertEquals(report.reconciliation.status, PointReconciliationStatus.Completed)
    assertEquals(report.reconciliation.result, Some(reconciliationResult))
    assertEquals(report.reconciliation.notAttemptedReason, None)
    assertEquals(decode[PointEvidenceReport](report.asJson.noSpaces), Right(report))

  test("projects every typed not-attempted reason without dropping its detail"):
    ReconciliationNotAttemptedReason.values.foreach { internalReason =>
      val report = PointEvidenceReport.fromInternal(
        evidence(
          discoveryStatus = SemanticdbForSource.StatusNoMatch,
          selected = None,
          live = LivePointEvidence.Unresolved(liveResult.copy(symbol = None)),
          reconciliation = PointReconciliation.NotAttempted(internalReason, s"detail-$internalReason")
        )
      ).fold(fail(_), identity)

      assertEquals(report.reconciliation.status, PointReconciliationStatus.NotAttempted)
      assertEquals(
        report.reconciliation.notAttemptedReason.map(_.toString),
        Some(internalReason.toString)
      )
      assertEquals(report.reconciliation.detail, Some(s"detail-$internalReason"))
      assertEquals(report.reconciliation.result, None)
    }

  test("maps discovery and live absence into explicit public states"):
    val cases = List(
      SemanticdbForSource.StatusNoMatch -> PointArtifactSelectionStatus.NotSelectedNoCandidate,
      SemanticdbForSource.StatusAmbiguous -> PointArtifactSelectionStatus.NotSelectedAmbiguous,
      SemanticdbForSource.StatusPartial -> PointArtifactSelectionStatus.NotSelectedPartialOrUnparseable,
      SemanticdbForSource.StatusUnparseable -> PointArtifactSelectionStatus.NotSelectedPartialOrUnparseable,
      SemanticdbForSource.StatusUnavailable -> PointArtifactSelectionStatus.NotSelectedUnavailable
    )

    cases.foreach { case (discoveryStatus, expected) =>
      val report = PointEvidenceReport.fromInternal(
        evidence(
          discoveryStatus = discoveryStatus,
          selected = None,
          live = LivePointEvidence.Unavailable("presentation compiler unavailable"),
          reconciliation = PointReconciliation.NotAttempted(
            ReconciliationNotAttemptedReason.LivePointUnavailable,
            "presentation compiler unavailable"
          )
        )
      ).fold(fail(_), identity)

      assertEquals(report.selection.status, expected)
      assert(report.selection.reason.nonEmpty)
      assertEquals(report.livePoint.status, PointLiveStatus.Unavailable)
      assertEquals(report.livePoint.result, None)
      assertEquals(report.livePoint.reason, Some("presentation compiler unavailable"))
    }

  test("public enum decoders reject unknown contract values"):
    assert(decode[PointArtifactSelectionStatus]("\"FutureSelection\"").isLeft)
    assert(decode[PointLiveStatus]("\"FutureLiveState\"").isLeft)
    assert(decode[PointReconciliationStatus]("\"FutureReconciliation\"").isLeft)
    assert(decode[PointReconciliationNotAttemptedReason]("\"FutureReason\"").isLeft)

  private val workspace = Path.of("/workspace")
  private val source = workspace.resolve("src/main/scala/example/Main.scala")
  private val artifact = SemanticdbSourceMatch(
    semanticdb = "target/classes/Main.scala.semanticdb",
    uri = Some("src/main/scala/example/Main.scala"),
    parseStatus = "Parsed",
    matchKind = SemanticdbForSource.MatchUriExact,
    symbols = Some(2),
    occurrences = Some(3),
    mtimeMillis = 123L,
    error = None
  )
  private val liveResult = SymbolAtResult(
    symbol = Some("example/Main.answer."),
    displayName = Some("answer"),
    range = Some(SourceRange(5, 2, 5, 8)),
    source = source.toString
  )
  private val reconciliationResult = ReconciliationResult(
    file = source.toString,
    queryPosition = SourceRange(5, 15, 5, 15),
    result = ReconciledSymbol(
      semanticdbSymbol = Some("example/Main.answer."),
      compilerSymbol = Some("example/Main.answer."),
      displayName = Some("answer"),
      range = Some(SourceRange(5, 2, 5, 8)),
      status = ReconciliationStatus.ExactMatch
    )
  )

  private def evidence(
    discoveryStatus: String,
    selected: Option[SemanticdbSourceMatch],
    live: LivePointEvidence,
    reconciliation: PointReconciliation
  ): SemanticPointEvidence =
    SemanticPointEvidence(
      request = SemanticPointEvidenceRequest(workspace, source, line = 6, column = 16),
      discovery = SemanticdbForSourceReport(
        workspace = workspace.toString,
        sourceFile = source.toString,
        sourceRelativePath = Some("src/main/scala/example/Main.scala"),
        status = discoveryStatus,
        semanticdbFiles = 1,
        parseableFiles = 1,
        unparseableFiles = 0,
        matches = selected.toList,
        candidatesConsidered = 1,
        warnings = Nil,
        errors = Nil
      ),
      selectedArtifact = selected,
      livePoint = live,
      reconciliation = reconciliation
    )
