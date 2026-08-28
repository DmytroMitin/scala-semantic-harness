package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import semantic.harness.presentation.SourceRange
import semantic.harness.semanticdb_reader.SourceArtifactFreshness
import semantic.harness.semanticdb_reader.UnverifiableReason

enum ReconciliationStatus:
  case ExactMatch
  case RangeMatchOnly
  case SymbolMismatch
  case NoMatch

object ReconciliationStatus:
  given Encoder[ReconciliationStatus] =
    Encoder.encodeString.contramap(_.toString)

  given Decoder[ReconciliationStatus] =
    Decoder.decodeString.emap { value =>
      ReconciliationStatus.values
        .find(_.toString == value)
        .toRight(s"Invalid ReconciliationStatus: $value")
    }

final case class ReconciledSymbol(
  semanticdbSymbol: Option[String],
  compilerSymbol: Option[String],
  displayName: Option[String],
  range: Option[SourceRange],
  status: ReconciliationStatus
)

object ReconciledSymbol:
  given Encoder[ReconciledSymbol] = deriveEncoder
  given Decoder[ReconciledSymbol] = deriveDecoder

final case class ReconciliationResult(
  schemaVersion: String = ReconciliationResult.SchemaVersion,
  file: String,
  queryPosition: SourceRange,
  result: ReconciledSymbol
)

object ReconciliationResult:
  val SchemaVersion: String = "semantic-scala.reconcile-symbol-result.v1"

  given Encoder[ReconciliationResult] = deriveEncoder
  given Decoder[ReconciliationResult] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      file <- cursor.downField("file").as[String]
      queryPosition <- cursor.downField("queryPosition").as[SourceRange]
      result <- cursor.downField("result").as[ReconciledSymbol]
    yield ReconciliationResult(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      file = file,
      queryPosition = queryPosition,
      result = result
    )
  }

enum ReconciliationNotAttemptedReasonV2:
  case StaleArtifact
  case SourceChangedDuringRequest
  case NoArtifactCandidate
  case AmbiguousArtifactCandidates
  case PartialOrUnparseableArtifactEvidence
  case ArtifactEvidenceUnavailable
  case LivePointUnavailable
  case SelectedArtifactUnreadable
  case SelectedArtifactChangedOrRemapped

object ReconciliationNotAttemptedReasonV2:
  given Encoder[ReconciliationNotAttemptedReasonV2] = Encoder.encodeString.contramap(_.toString)
  given Decoder[ReconciliationNotAttemptedReasonV2] = Decoder.decodeString.emap(value =>
    ReconciliationNotAttemptedReasonV2.values.find(_.toString == value).toRight(s"Invalid ReconciliationNotAttemptedReasonV2: $value")
  )

enum ReconciliationOutcomeV2:
  case CompletedFresh(result: ReconciledSymbol)
  case CompletedQualifiedUnverifiable(result: ReconciledSymbol, reason: UnverifiableReason)
  case NotAttempted(reason: ReconciliationNotAttemptedReasonV2)

object ReconciliationOutcomeV2:
  given Encoder[ReconciliationOutcomeV2] = Encoder.instance {
    case CompletedFresh(result) => io.circe.Json.obj(
      "status" -> io.circe.Json.fromString("CompletedFresh"),
      "result" -> summon[Encoder[ReconciledSymbol]].apply(result),
      "qualificationReason" -> io.circe.Json.Null,
      "notAttemptedReason" -> io.circe.Json.Null
    )
    case CompletedQualifiedUnverifiable(result, reason) => io.circe.Json.obj(
      "status" -> io.circe.Json.fromString("CompletedQualifiedUnverifiable"),
      "result" -> summon[Encoder[ReconciledSymbol]].apply(result),
      "qualificationReason" -> summon[Encoder[UnverifiableReason]].apply(reason),
      "notAttemptedReason" -> io.circe.Json.Null
    )
    case NotAttempted(reason) => io.circe.Json.obj(
      "status" -> io.circe.Json.fromString("NotAttempted"),
      "result" -> io.circe.Json.Null,
      "qualificationReason" -> io.circe.Json.Null,
      "notAttemptedReason" -> summon[Encoder[ReconciliationNotAttemptedReasonV2]].apply(reason)
    )
  }

  given Decoder[ReconciliationOutcomeV2] = Decoder.instance { cursor =>
    cursor.downField("status").as[String].flatMap {
      case "CompletedFresh" => cursor.downField("result").as[ReconciledSymbol].map(CompletedFresh.apply)
      case "CompletedQualifiedUnverifiable" =>
        for
          result <- cursor.downField("result").as[ReconciledSymbol]
          reason <- cursor.downField("qualificationReason").as[UnverifiableReason]
        yield CompletedQualifiedUnverifiable(result, reason)
      case "NotAttempted" => cursor.downField("notAttemptedReason").as[ReconciliationNotAttemptedReasonV2].map(NotAttempted.apply)
      case other => Left(io.circe.DecodingFailure(s"Invalid reconciliation outcome status: $other", cursor.history))
    }
  }

final case class ReconciliationResultV2(
  schemaVersion: String = ReconciliationResultV2.SchemaVersion,
  file: String,
  semanticdb: Option[String],
  queryPosition: SourceRange,
  freshness: Option[SourceArtifactFreshness],
  outcome: ReconciliationOutcomeV2
)

object ReconciliationResultV2:
  val SchemaVersion: String = "semantic-scala.reconcile-symbol-result.v2"

  given Encoder[ReconciliationResultV2] = deriveEncoder
  given Decoder[ReconciliationResultV2] = deriveDecoder[ReconciliationResultV2].emap(validate)

  private def validate(value: ReconciliationResultV2): Either[String, ReconciliationResultV2] =
    if value.schemaVersion != SchemaVersion then Left(s"reconcile-symbol schemaVersion must be $SchemaVersion")
    else (value.freshness, value.outcome) match
      case (Some(SourceArtifactFreshness.Fresh(_)), ReconciliationOutcomeV2.CompletedFresh(_)) => Right(value)
      case (Some(SourceArtifactFreshness.Stale(_)), ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.StaleArtifact)) => Right(value)
      case (Some(SourceArtifactFreshness.Unverifiable(reason, _)), ReconciliationOutcomeV2.CompletedQualifiedUnverifiable(_, qualification)) if reason == qualification => Right(value)
      case (Some(SourceArtifactFreshness.SourceChangedDuringRequest(_, _, _)), ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest)) => Right(value)
      case (_, ReconciliationOutcomeV2.NotAttempted(_)) => Right(value)
      case _ => Left("reconcile-symbol freshness and outcome branches are inconsistent")
