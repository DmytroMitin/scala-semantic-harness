package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import semantic.harness.presentation.SourceRange

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
