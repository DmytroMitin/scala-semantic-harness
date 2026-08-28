package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.FreshnessBasis
import semantic.harness.semanticdb_reader.FreshnessEvidence
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReportV2
import semantic.harness.semanticdb_reader.SemanticdbSourceMatchV2
import semantic.harness.semanticdb_reader.SourceArtifactFreshness
import semantic.harness.semanticdb_reader.SourceSnapshot

enum PointArtifactSelectionStatusV2:
  case SelectedFresh
  case SelectedUnverifiable
  case NotSelectedStale
  case NotSelectedNoCandidate
  case NotSelectedAmbiguous
  case NotSelectedPartialOrUnparseable
  case NotSelectedUnavailable

object PointArtifactSelectionStatusV2:
  given Encoder[PointArtifactSelectionStatusV2] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointArtifactSelectionStatusV2] = Decoder.decodeString.emap(value =>
    PointArtifactSelectionStatusV2.values.find(_.toString == value).toRight(s"Invalid PointArtifactSelectionStatusV2: $value")
  )

final case class PointArtifactSelectionV2(
  status: PointArtifactSelectionStatusV2,
  artifact: Option[SemanticdbSourceMatchV2],
  reason: String
)

object PointArtifactSelectionV2:
  given Encoder[PointArtifactSelectionV2] = deriveEncoder
  given Decoder[PointArtifactSelectionV2] = deriveDecoder

final case class PointEvidenceReportV2(
  schemaVersion: String = PointEvidenceReportV2.SchemaVersion,
  workspace: String,
  sourceFile: String,
  position: PointEvidencePosition,
  discovery: SemanticdbForSourceReportV2,
  selection: PointArtifactSelectionV2,
  livePoint: PointLiveEvidence,
  reconciliation: ReconciliationResultV2
)

object PointEvidenceReportV2:
  val SchemaVersion: String = "semantic-scala.point-evidence-result.v2"

  given Encoder[PointEvidenceReportV2] = deriveEncoder
  given Decoder[PointEvidenceReportV2] = deriveDecoder[PointEvidenceReportV2].emap { report =>
    if report.schemaVersion == SchemaVersion then Right(report)
    else Left(s"point-evidence schemaVersion must be $SchemaVersion")
  }

final class PointEvidenceServiceV2 private (
  pointQuery: (Path, String, Int, Int) => Either[String, SymbolAtResult],
  beforeFinalCheck: () => Unit
):
  def inspect(request: SemanticPointEvidenceRequest): Either[String, PointEvidenceReportV2] =
    validate(request).flatMap { _ =>
      val source = SourceSnapshot.capture(request.sourceFile)
      SemanticdbForSource.inspectV2WithSnapshots(request.workspace, request.sourceFile, source).map { inspection =>
        val discovery = inspection.report
        val unique = Option.when(
          discovery.status == SemanticdbForSource.StatusUniqueMatch &&
            discovery.matches.size == 1 && discovery.matches.head.parseStatus == "Parsed"
        )(discovery.matches.head)
        unique match
          case Some(candidate) =>
            inspection.artifactSnapshots.get(candidate.semanticdb) match
              case Some(artifact) =>
                val reconciler = FreshnessReconciliationService.withPointQueryAndHook(pointQuery, beforeFinalCheck)
                val computation = reconciler.reconcileSnapshots(
                  request.sourceFile,
                  source,
                  discovery.sourceRelativePath.getOrElse(request.sourceFile.toAbsolutePath.normalize().toString),
                  request.line,
                  request.column,
                  artifact,
                  queryWhenStale = true
                )
                report(
                  request,
                  discovery,
                  selection(candidate),
                  live(computation.liveResult),
                  computation.report
                )
              case None =>
                withoutArtifact(
                  request,
                  source,
                  discovery,
                  PointArtifactSelectionV2(
                    PointArtifactSelectionStatusV2.NotSelectedUnavailable,
                    Some(candidate),
                    "The uniquely matched SemanticDB artifact snapshot was unavailable"
                  ),
                  ReconciliationNotAttemptedReasonV2.SelectedArtifactUnreadable
                )
          case None =>
            val (selection, reason) = notSelected(discovery)
            withoutArtifact(request, source, discovery, selection, reason)
      }
    }

  private def withoutArtifact(
    request: SemanticPointEvidenceRequest,
    source: SourceSnapshot,
    discovery: SemanticdbForSourceReportV2,
    selection: PointArtifactSelectionV2,
    reason: ReconciliationNotAttemptedReasonV2
  ): PointEvidenceReportV2 =
    val queried = source.content match
      case Some(content) => Some(pointQuery(request.sourceFile, content, request.line, request.column))
      case None => Some(Left("The captured source snapshot has no supported UTF-8 content"))
    beforeFinalCheck()
    val changed = source.sha256
      .flatMap(before => SourceSnapshot.recaptureSha256(request.sourceFile).map(before -> _))
      .filter { case (before, after) => before != after }
    val (freshness, outcome) = changed match
      case Some((before, after)) =>
        val evidence = FreshnessEvidence(
          FreshnessBasis.None,
          None,
          None,
          None,
          source.md5,
          source.sha256,
          None,
          source.mtimeMillis,
          None,
          Some("SourceChangedDuringRequest")
        )
        Some(SourceArtifactFreshness.SourceChangedDuringRequest(before, after, evidence)) ->
          ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest)
      case None => None -> ReconciliationOutcomeV2.NotAttempted(reason)
    report(
      request,
      discovery,
      selection,
      live(queried),
      ReconciliationResultV2(
        file = request.sourceFile.toAbsolutePath.normalize().toString,
        semanticdb = selection.artifact.map(_.semanticdb),
        queryPosition = pointRange(request.line, request.column),
        freshness = freshness,
        outcome = outcome
      )
    )

  private def selection(candidate: SemanticdbSourceMatchV2): PointArtifactSelectionV2 =
    candidate.freshness match
      case Some(SourceArtifactFreshness.Fresh(_)) =>
        PointArtifactSelectionV2(PointArtifactSelectionStatusV2.SelectedFresh, Some(candidate), "Exactly one Fresh SemanticDB candidate matched the source")
      case Some(SourceArtifactFreshness.Stale(_)) =>
        PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedStale, Some(candidate), "The unique SemanticDB candidate is Stale")
      case Some(SourceArtifactFreshness.Unverifiable(_, _)) =>
        PointArtifactSelectionV2(PointArtifactSelectionStatusV2.SelectedUnverifiable, Some(candidate), "Exactly one SemanticDB candidate matched with Unverifiable freshness")
      case Some(SourceArtifactFreshness.SourceChangedDuringRequest(_, _, _)) =>
        PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedUnavailable, Some(candidate), "The source changed during candidate inspection")
      case None =>
        PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedUnavailable, Some(candidate), "The candidate has no freshness evidence")

  private def notSelected(
    discovery: SemanticdbForSourceReportV2
  ): (PointArtifactSelectionV2, ReconciliationNotAttemptedReasonV2) = discovery.status match
    case SemanticdbForSource.StatusNoMatch =>
      PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedNoCandidate, None, "No SemanticDB candidate matched the source") ->
        ReconciliationNotAttemptedReasonV2.NoArtifactCandidate
    case SemanticdbForSource.StatusAmbiguous =>
      PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedAmbiguous, None, s"${discovery.matches.size} SemanticDB candidates matched the source") ->
        ReconciliationNotAttemptedReasonV2.AmbiguousArtifactCandidates
    case SemanticdbForSource.StatusPartial | SemanticdbForSource.StatusUnparseable =>
      PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedPartialOrUnparseable, None, "SemanticDB candidate evidence was partial or unparseable") ->
        ReconciliationNotAttemptedReasonV2.PartialOrUnparseableArtifactEvidence
    case _ =>
      PointArtifactSelectionV2(PointArtifactSelectionStatusV2.NotSelectedUnavailable, None, "SemanticDB artifact evidence was unavailable") ->
        ReconciliationNotAttemptedReasonV2.ArtifactEvidenceUnavailable

  private def live(value: Option[Either[String, SymbolAtResult]]): PointLiveEvidence = value match
    case Some(Right(result)) if result.symbol.nonEmpty => PointLiveEvidence(PointLiveStatus.Resolved, Some(result), None)
    case Some(Right(result)) => PointLiveEvidence(PointLiveStatus.Unresolved, Some(result), None)
    case Some(Left(reason)) => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some(reason))
    case None => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some("Live point evidence was not requested"))

  private def report(
    request: SemanticPointEvidenceRequest,
    discovery: SemanticdbForSourceReportV2,
    selection: PointArtifactSelectionV2,
    livePoint: PointLiveEvidence,
    reconciliation: ReconciliationResultV2
  ): PointEvidenceReportV2 = PointEvidenceReportV2(
    workspace = request.workspace.toAbsolutePath.normalize().toString,
    sourceFile = request.sourceFile.toAbsolutePath.normalize().toString,
    position = PointEvidencePosition(request.line, request.column, PointEvidencePosition.Encoding),
    discovery = discovery,
    selection = selection,
    livePoint = livePoint,
    reconciliation = reconciliation
  )

  private def validate(request: SemanticPointEvidenceRequest): Either[String, Unit] =
    if request.line <= 0 then Left(s"Line must be positive: ${request.line}")
    else if request.column <= 0 then Left(s"Column must be positive: ${request.column}")
    else if !Files.exists(request.sourceFile) then Left(s"Source file does not exist: ${request.sourceFile}")
    else if !Files.isRegularFile(request.sourceFile) then Left(s"Source path is not a file: ${request.sourceFile}")
    else Right(())

  private def pointRange(line: Int, column: Int): SourceRange =
    SourceRange(line - 1, column - 1, line - 1, column - 1)

object PointEvidenceServiceV2:
  def apply(): PointEvidenceServiceV2 =
    val compiler = PresentationCompilerService()
    new PointEvidenceServiceV2(compiler.symbolAtSnapshot, () => ())

  private[reconciliation] def withPointQuery(
    pointQuery: (Path, String, Int, Int) => Either[String, SymbolAtResult]
  ): PointEvidenceServiceV2 = new PointEvidenceServiceV2(pointQuery, () => ())

  private[reconciliation] def withPointQueryAndHook(
    pointQuery: (Path, String, Int, Int) => Either[String, SymbolAtResult],
    beforeFinalCheck: () => Unit
  ): PointEvidenceServiceV2 = new PointEvidenceServiceV2(pointQuery, beforeFinalCheck)
