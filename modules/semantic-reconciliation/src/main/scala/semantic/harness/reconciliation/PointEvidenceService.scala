package semantic.harness.reconciliation

import java.nio.file.Path
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReport
import semantic.harness.semanticdb_reader.SemanticdbReader
import semantic.harness.semanticdb_reader.SemanticdbSourceMatch

final class PointEvidenceService private (
  pointQuery: (Path, Int, Int) => Either[String, SymbolAtResult]
):
  def inspect(request: SemanticPointEvidenceRequest): Either[String, SemanticPointEvidence] =
    validatePosition(request).flatMap { _ =>
      SemanticdbForSource.inspect(request.workspace, request.sourceFile).map { discovery =>
        val selected = selectedArtifact(discovery)
        val livePoint = pointQuery(request.sourceFile, request.line, request.column) match
          case Right(result) if result.symbol.nonEmpty => LivePointEvidence.Resolved(result)
          case Right(result)                           => LivePointEvidence.Unresolved(result)
          case Left(reason)                            => LivePointEvidence.Unavailable(reason)
        val reconciliation = reconcile(request, discovery, selected, livePoint)

        SemanticPointEvidence(
          request = request,
          discovery = discovery,
          selectedArtifact = selected,
          livePoint = livePoint,
          reconciliation = reconciliation
        )
      }
    }

  private def validatePosition(request: SemanticPointEvidenceRequest): Either[String, Unit] =
    if request.line <= 0 then Left(s"Line must be positive: ${request.line}")
    else if request.column <= 0 then Left(s"Column must be positive: ${request.column}")
    else Right(())

  private def selectedArtifact(report: SemanticdbForSourceReport): Option[SemanticdbSourceMatch] =
    Option.when(
      report.status == SemanticdbForSource.StatusUniqueMatch &&
        report.matches.size == 1 &&
        report.matches.head.parseStatus == "Parsed"
    )(report.matches.head)

  private def reconcile(
    request: SemanticPointEvidenceRequest,
    discovery: SemanticdbForSourceReport,
    selected: Option[SemanticdbSourceMatch],
    livePoint: LivePointEvidence
  ): PointReconciliation =
    (selected, livePoint) match
      case (_, LivePointEvidence.Unavailable(reason)) =>
        PointReconciliation.NotAttempted(
          ReconciliationNotAttemptedReason.LivePointUnavailable,
          reason
        )
      case (Some(artifact), LivePointEvidence.Resolved(result)) =>
        reconcileSelected(request, artifact, result)
      case (Some(artifact), LivePointEvidence.Unresolved(result)) =>
        reconcileSelected(request, artifact, result)
      case (None, _) => notAttemptedForDiscovery(discovery)

  private def reconcileSelected(
    request: SemanticPointEvidenceRequest,
    artifact: SemanticdbSourceMatch,
    liveResult: SymbolAtResult
  ): PointReconciliation =
    val artifactPath = request.workspace.toAbsolutePath.normalize().resolve(artifact.semanticdb).normalize()
    SemanticdbReader.read(artifactPath) match
      case Right(summary) =>
        PointReconciliation.Completed(
          SemanticReconciler.reconcile(
            file = request.sourceFile.toString,
            line = request.line,
            column = request.column,
            compilerResult = liveResult,
            summary = summary
          )
        )
      case Left(reason) =>
        PointReconciliation.NotAttempted(
          ReconciliationNotAttemptedReason.SelectedArtifactUnreadable,
          reason
        )

  private def notAttemptedForDiscovery(
    discovery: SemanticdbForSourceReport
  ): PointReconciliation =
    val (reason, detail) = discovery.status match
      case SemanticdbForSource.StatusNoMatch =>
        ReconciliationNotAttemptedReason.NoArtifactCandidate ->
          "No SemanticDB candidate matched the source"
      case SemanticdbForSource.StatusAmbiguous =>
        ReconciliationNotAttemptedReason.AmbiguousArtifactCandidates ->
          s"${discovery.matches.size} SemanticDB candidates matched the source"
      case SemanticdbForSource.StatusPartial | SemanticdbForSource.StatusUnparseable =>
        ReconciliationNotAttemptedReason.PartialOrUnparseableArtifactEvidence ->
          "SemanticDB candidate evidence was partial or unparseable"
      case _ =>
        ReconciliationNotAttemptedReason.ArtifactEvidenceUnavailable ->
          s"SemanticDB discovery status was ${discovery.status}"

    PointReconciliation.NotAttempted(reason, detail)

object PointEvidenceService:
  def apply(): PointEvidenceService =
    new PointEvidenceService(PresentationCompilerService().symbolAt)

  private[reconciliation] def withPointQuery(
    pointQuery: (Path, Int, Int) => Either[String, SymbolAtResult]
  ): PointEvidenceService =
    new PointEvidenceService(pointQuery)
