package semantic.harness.fp

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder

final case class EffectSourceRange(
  startLine: Int,
  startCharacter: Int,
  endLine: Int,
  endCharacter: Int
)

object EffectSourceRange:
  given Encoder[EffectSourceRange] = deriveEncoder
  given Decoder[EffectSourceRange] = deriveDecoder

final case class EffectMethodSummary(
  name: String,
  range: Option[EffectSourceRange],
  declaredReturnType: Option[String],
  inferredReturnType: Option[String],
  effectCategory: String,
  confidence: String,
  notes: List[String],
  ownerName: Option[String] = None,
  qualifiedName: Option[String] = None,
  enclosingKind: Option[String] = None,
  packageName: Option[String] = None,
  packageQualifiedName: Option[String] = None,
  sourceFile: Option[String] = None
)

object EffectMethodSummary:
  given Encoder[EffectMethodSummary] = deriveEncoder
  given Decoder[EffectMethodSummary] = deriveDecoder

final case class EffectSummaryReport(
  schemaVersion: String = EffectSummaryReport.SchemaVersion,
  source: String,
  methods: List[EffectMethodSummary]
)

object EffectSummaryReport:
  val SchemaVersion: String = "semantic-scala.effect-summary.v1"

  given Encoder[EffectSummaryReport] = deriveEncoder
  given Decoder[EffectSummaryReport] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      source <- cursor.downField("source").as[String]
      methods <- cursor.downField("methods").as[List[EffectMethodSummary]]
    yield EffectSummaryReport(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      source = source,
      methods = methods
    )
  }
