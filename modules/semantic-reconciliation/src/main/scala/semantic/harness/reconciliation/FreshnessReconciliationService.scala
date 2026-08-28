package semantic.harness.reconciliation

import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.ArtifactSnapshot
import semantic.harness.semanticdb_reader.FreshnessAssessor
import semantic.harness.semanticdb_reader.SourceArtifactFreshness
import semantic.harness.semanticdb_reader.SourceSnapshot
import semantic.harness.semanticdb_reader.SemanticdbReader

final case class FreshnessReconciliationComputation(
  report: ReconciliationResultV2,
  liveResult: Option[Either[String, SymbolAtResult]]
)

final class FreshnessReconciliationService private (
  pointQuery: (Path, String, Int, Int) => Either[String, SymbolAtResult],
  beforeFinalCheck: () => Unit
):
  def reconcile(
    sourceFile: Path,
    line: Int,
    column: Int,
    semanticdb: Path
  ): Either[String, ReconciliationResultV2] =
    validate(sourceFile, line, column).flatMap { _ =>
      val source = SourceSnapshot.capture(sourceFile)
      SemanticdbReader.readSnapshot(semanticdb).map { artifact =>
        reconcileSnapshots(
          sourceFile = sourceFile,
          source = source,
          sourceIdentityPath = sourceFile.toAbsolutePath.normalize().toString,
          line = line,
          column = column,
          artifact = artifact,
          queryWhenStale = false
        ).report
      }
    }

  def reconcileSnapshots(
    sourceFile: Path,
    source: SourceSnapshot,
    sourceIdentityPath: String,
    line: Int,
    column: Int,
    artifact: ArtifactSnapshot,
    queryWhenStale: Boolean
  ): FreshnessReconciliationComputation =
    val assessment = FreshnessAssessor.assess(source, artifact, sourceIdentityPath)
    val initialFreshness = assessment.freshness
    val shouldQuery = initialFreshness match
      case SourceArtifactFreshness.Stale(_) => queryWhenStale
      case _ => true
    val live = Option.when(shouldQuery)(source.content match
      case Some(content) => pointQuery(sourceFile, content, line, column)
      case None => Left("The captured source snapshot has no supported UTF-8 content")
    )
    val initialOutcome = initialFreshness match
      case SourceArtifactFreshness.Stale(_) =>
        ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.StaleArtifact)
      case SourceArtifactFreshness.Fresh(_) =>
        completed(assessment.document.map(_.summary), live, sourceFile, line, column, qualified = None)
      case SourceArtifactFreshness.Unverifiable(reason, _) =>
        completed(assessment.document.map(_.summary), live, sourceFile, line, column, qualified = Some(reason))
      case SourceArtifactFreshness.SourceChangedDuringRequest(_, _, _) =>
        ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest)

    beforeFinalCheck()
    val (freshness, outcome) = source.sha256.flatMap(before => SourceSnapshot.recaptureSha256(sourceFile).map(before -> _)) match
      case Some((before, after)) if before != after =>
        SourceArtifactFreshness.SourceChangedDuringRequest(
          before,
          after,
          SourceArtifactFreshness.evidence(initialFreshness)
        ) -> ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest)
      case _ => initialFreshness -> initialOutcome

    FreshnessReconciliationComputation(
      report = ReconciliationResultV2(
        file = sourceFile.toAbsolutePath.normalize().toString,
        semanticdb = Some(artifact.path.toString),
        queryPosition = pointRange(line, column),
        freshness = Some(freshness),
        outcome = outcome
      ),
      liveResult = live
    )

  private def completed(
    summary: Option[semantic.harness.semanticdb_reader.SemanticFileSummary],
    live: Option[Either[String, SymbolAtResult]],
    sourceFile: Path,
    line: Int,
    column: Int,
    qualified: Option[semantic.harness.semanticdb_reader.UnverifiableReason]
  ): ReconciliationOutcomeV2 =
    (summary, live) match
      case (None, _) =>
        ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SelectedArtifactChangedOrRemapped)
      case (_, Some(Left(_))) | (_, None) =>
        ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.LivePointUnavailable)
      case (Some(document), Some(Right(liveResult))) =>
        val result = SemanticReconciler.reconcile(
          sourceFile.toAbsolutePath.normalize().toString,
          line,
          column,
          liveResult,
          document
        ).result
        qualified match
          case Some(reason) => ReconciliationOutcomeV2.CompletedQualifiedUnverifiable(result, reason)
          case None => ReconciliationOutcomeV2.CompletedFresh(result)

  private def validate(sourceFile: Path, line: Int, column: Int): Either[String, Unit] =
    if line <= 0 then Left(s"Line must be positive: $line")
    else if column <= 0 then Left(s"Column must be positive: $column")
    else if !Files.exists(sourceFile) then Left(s"Source file does not exist: $sourceFile")
    else if !Files.isRegularFile(sourceFile) then Left(s"Source path is not a file: $sourceFile")
    else if sourceFile.getFileName == null || !sourceFile.getFileName.toString.endsWith(".scala") then
      Left(s"Source path must point to a .scala file: $sourceFile")
    else Right(())

  private def pointRange(line: Int, column: Int): SourceRange =
    SourceRange(line - 1, column - 1, line - 1, column - 1)

object FreshnessReconciliationService:
  def apply(): FreshnessReconciliationService =
    val compiler = PresentationCompilerService()
    new FreshnessReconciliationService(compiler.symbolAtSnapshot, () => ())

  private[reconciliation] def withPointQuery(
    pointQuery: (Path, String, Int, Int) => Either[String, SymbolAtResult]
  ): FreshnessReconciliationService = new FreshnessReconciliationService(pointQuery, () => ())

  private[reconciliation] def withPointQueryAndHook(
    pointQuery: (Path, String, Int, Int) => Either[String, SymbolAtResult],
    beforeFinalCheck: () => Unit
  ): FreshnessReconciliationService = new FreshnessReconciliationService(pointQuery, beforeFinalCheck)
