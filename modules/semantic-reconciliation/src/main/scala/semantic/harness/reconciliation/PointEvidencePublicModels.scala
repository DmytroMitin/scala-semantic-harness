package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReport
import semantic.harness.semanticdb_reader.SemanticdbSourceMatch

final case class PointEvidencePosition(line: Int, column: Int, encoding: String)

object PointEvidencePosition:
  val Encoding: String = "UTF-16"
  given Encoder[PointEvidencePosition] = deriveEncoder
  given Decoder[PointEvidencePosition] = deriveDecoder[PointEvidencePosition].emap { value =>
    if value.line <= 0 then Left("point-evidence line must be positive")
    else if value.column <= 0 then Left("point-evidence column must be positive")
    else if value.encoding != Encoding then Left(s"point-evidence encoding must be $Encoding")
    else Right(value)
  }

enum PointArtifactSelectionStatus:
  case SelectedUniqueParsed
  case NotSelectedNoCandidate
  case NotSelectedAmbiguous
  case NotSelectedPartialOrUnparseable
  case NotSelectedUnavailable

object PointArtifactSelectionStatus:
  given Encoder[PointArtifactSelectionStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointArtifactSelectionStatus] = Decoder.decodeString.emap { value =>
    PointArtifactSelectionStatus.values.find(_.toString == value).toRight(s"Invalid PointArtifactSelectionStatus: $value")
  }

final case class PointArtifactSelection(
  status: PointArtifactSelectionStatus,
  artifact: Option[SemanticdbSourceMatch],
  reason: String
)

object PointArtifactSelection:
  given Encoder[PointArtifactSelection] = deriveEncoder
  given Decoder[PointArtifactSelection] = deriveDecoder

enum PointLiveStatus:
  case Resolved
  case Unresolved
  case Unavailable

object PointLiveStatus:
  given Encoder[PointLiveStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointLiveStatus] = Decoder.decodeString.emap { value =>
    PointLiveStatus.values.find(_.toString == value).toRight(s"Invalid PointLiveStatus: $value")
  }

final case class PointLiveEvidence(
  status: PointLiveStatus,
  result: Option[SymbolAtResult],
  reason: Option[String]
)

object PointLiveEvidence:
  given Encoder[PointLiveEvidence] = deriveEncoder
  given Decoder[PointLiveEvidence] = deriveDecoder

enum PointReconciliationStatus:
  case Completed
  case NotAttempted

object PointReconciliationStatus:
  given Encoder[PointReconciliationStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointReconciliationStatus] = Decoder.decodeString.emap { value =>
    PointReconciliationStatus.values.find(_.toString == value).toRight(s"Invalid PointReconciliationStatus: $value")
  }

enum PointReconciliationNotAttemptedReason:
  case NoArtifactCandidate
  case AmbiguousArtifactCandidates
  case PartialOrUnparseableArtifactEvidence
  case ArtifactEvidenceUnavailable
  case LivePointUnavailable
  case SelectedArtifactUnreadable

object PointReconciliationNotAttemptedReason:
  given Encoder[PointReconciliationNotAttemptedReason] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointReconciliationNotAttemptedReason] = Decoder.decodeString.emap { value =>
    PointReconciliationNotAttemptedReason.values.find(_.toString == value).toRight(s"Invalid PointReconciliationNotAttemptedReason: $value")
  }

final case class PointReconciliationEvidence(
  status: PointReconciliationStatus,
  result: Option[ReconciliationResult],
  notAttemptedReason: Option[PointReconciliationNotAttemptedReason],
  detail: Option[String]
)

object PointReconciliationEvidence:
  given Encoder[PointReconciliationEvidence] = deriveEncoder
  given Decoder[PointReconciliationEvidence] = deriveDecoder

final case class PointEvidenceReport(
  schemaVersion: String = PointEvidenceReport.SchemaVersion,
  workspace: String,
  sourceFile: String,
  position: PointEvidencePosition,
  discovery: SemanticdbForSourceReport,
  selection: PointArtifactSelection,
  livePoint: PointLiveEvidence,
  reconciliation: PointReconciliationEvidence
)

object PointEvidenceReport:
  val SchemaVersion: String = "semantic-scala.point-evidence-result.v1"

  given Encoder[PointEvidenceReport] = deriveEncoder
  given Decoder[PointEvidenceReport] = deriveDecoder[PointEvidenceReport].emap { report =>
    if report.schemaVersion == SchemaVersion then Right(report)
    else Left(s"point-evidence schemaVersion must be $SchemaVersion")
  }

  def fromInternal(evidence: SemanticPointEvidence): Either[String, PointEvidenceReport] =
    selection(evidence).map { selected =>
      PointEvidenceReport(
        workspace = evidence.request.workspace.toAbsolutePath.normalize().toString,
        sourceFile = evidence.request.sourceFile.toAbsolutePath.normalize().toString,
        position = PointEvidencePosition(evidence.request.line, evidence.request.column, PointEvidencePosition.Encoding),
        discovery = evidence.discovery,
        selection = selected,
        livePoint = live(evidence.livePoint),
        reconciliation = reconciliation(evidence.reconciliation)
      )
    }

  private def selection(evidence: SemanticPointEvidence): Either[String, PointArtifactSelection] =
    evidence.selectedArtifact match
      case Some(artifact) =>
        Right(PointArtifactSelection(
          PointArtifactSelectionStatus.SelectedUniqueParsed,
          Some(artifact),
          "Exactly one parsed SemanticDB candidate matched the source"
        ))
      case None =>
        evidence.discovery.status match
          case SemanticdbForSource.StatusNoMatch =>
            Right(PointArtifactSelection(PointArtifactSelectionStatus.NotSelectedNoCandidate, None, "No SemanticDB candidate matched the source"))
          case SemanticdbForSource.StatusAmbiguous =>
            Right(PointArtifactSelection(PointArtifactSelectionStatus.NotSelectedAmbiguous, None, s"${evidence.discovery.matches.size} SemanticDB candidates matched the source"))
          case SemanticdbForSource.StatusPartial | SemanticdbForSource.StatusUnparseable =>
            Right(PointArtifactSelection(PointArtifactSelectionStatus.NotSelectedPartialOrUnparseable, None, "SemanticDB candidate evidence was partial or unparseable"))
          case SemanticdbForSource.StatusUnavailable =>
            Right(PointArtifactSelection(PointArtifactSelectionStatus.NotSelectedUnavailable, None, "SemanticDB artifact evidence was unavailable"))
          case SemanticdbForSource.StatusUniqueMatch =>
            Left("UniqueMatch point evidence did not contain one selectable parsed artifact")
          case other => Left(s"Unsupported SemanticDB discovery status: $other")

  private def live(evidence: LivePointEvidence): PointLiveEvidence =
    evidence match
      case LivePointEvidence.Resolved(result) => PointLiveEvidence(PointLiveStatus.Resolved, Some(result), None)
      case LivePointEvidence.Unresolved(result) => PointLiveEvidence(PointLiveStatus.Unresolved, Some(result), None)
      case LivePointEvidence.Unavailable(reason) => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some(reason))

  private def reconciliation(evidence: PointReconciliation): PointReconciliationEvidence =
    evidence match
      case PointReconciliation.Completed(result) =>
        PointReconciliationEvidence(PointReconciliationStatus.Completed, Some(result), None, None)
      case PointReconciliation.NotAttempted(reason, detail) =>
        PointReconciliationEvidence(
          PointReconciliationStatus.NotAttempted,
          None,
          Some(PointReconciliationNotAttemptedReason.valueOf(reason.toString)),
          Some(detail)
        )
