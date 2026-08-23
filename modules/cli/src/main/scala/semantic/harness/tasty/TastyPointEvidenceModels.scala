package semantic.harness.tasty

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

private[tasty] object StringEnumCodec:
  def encoder[A]: Encoder[A] = Encoder.encodeString.contramap(_.toString)

  def decoder[A](values: List[A], label: String): Decoder[A] =
    Decoder.decodeString.emap { value =>
      values.find(_.toString == value).toRight(s"unknown $label '$value'")
    }

enum TastyPointEvidenceStatus:
  case Resolved
  case NoTypedTreeAtPoint
  case CompileFailed
  case ArtifactUnavailable
  case SourceChangedDuringRequest
  case UnsupportedTargetScala
  case InspectorUnavailable
  case InspectorFailed

object TastyPointEvidenceStatus:
  given Encoder[TastyPointEvidenceStatus] = StringEnumCodec.encoder
  given Decoder[TastyPointEvidenceStatus] =
    StringEnumCodec.decoder(TastyPointEvidenceStatus.values.toList, "TASTy point evidence status")

enum TastyCompileStatus:
  case Succeeded
  case Failed

object TastyCompileStatus:
  given Encoder[TastyCompileStatus] = StringEnumCodec.encoder
  given Decoder[TastyCompileStatus] =
    StringEnumCodec.decoder(TastyCompileStatus.values.toList, "TASTy compile status")

enum TastyArtifactState:
  case NotInspected
  case Unavailable
  case Available
  case RejectedByBounds

object TastyArtifactState:
  given Encoder[TastyArtifactState] = StringEnumCodec.encoder
  given Decoder[TastyArtifactState] =
    StringEnumCodec.decoder(TastyArtifactState.values.toList, "TASTy artifact state")

enum TastyFreshnessDisposition:
  case SameRequestSourceStable
  case SourceChanged
  case NotEstablished

object TastyFreshnessDisposition:
  given Encoder[TastyFreshnessDisposition] = StringEnumCodec.encoder
  given Decoder[TastyFreshnessDisposition] =
    StringEnumCodec.decoder(TastyFreshnessDisposition.values.toList, "TASTy freshness disposition")

final case class TastyPointPosition(
    line: Int,
    column: Int,
    encoding: String = "UTF-16"
)
object TastyPointPosition:
  given Encoder[TastyPointPosition] = deriveEncoder
  given Decoder[TastyPointPosition] = deriveDecoder

final case class TastyPointRequest(
    source: String,
    position: TastyPointPosition,
    sbtProject: String,
    configuration: String = "Compile"
)
object TastyPointRequest:
  given Encoder[TastyPointRequest] = deriveEncoder
  given Decoder[TastyPointRequest] = deriveDecoder

final case class TastyCompileEvidence(status: TastyCompileStatus)
object TastyCompileEvidence:
  given Encoder[TastyCompileEvidence] = deriveEncoder
  given Decoder[TastyCompileEvidence] = deriveDecoder

final case class TastyArtifactDigest(kind: String, byteSize: Long, sha256: String)
object TastyArtifactDigest:
  given Encoder[TastyArtifactDigest] = deriveEncoder
  given Decoder[TastyArtifactDigest] = deriveDecoder

final case class TastyArtifactEvidence(
    state: TastyArtifactState,
    candidateCount: Int,
    inspectedCount: Int,
    selectedArtifact: Option[TastyArtifactDigest]
)
object TastyArtifactEvidence:
  given Encoder[TastyArtifactEvidence] = deriveEncoder
  given Decoder[TastyArtifactEvidence] = deriveDecoder

final case class TastySourceRange(
    startLine: Int,
    startColumn: Int,
    endLine: Int,
    endColumn: Int,
    encoding: String = "UTF-16"
)
object TastySourceRange:
  given Encoder[TastySourceRange] = deriveEncoder
  given Decoder[TastySourceRange] = deriveDecoder

final case class TastySelectedTree(
    kind: String,
    range: TastySourceRange,
    symbol: Option[String],
    displayName: Option[String],
    signature: Option[String],
    renderedType: Option[String]
)
object TastySelectedTree:
  given Encoder[TastySelectedTree] = deriveEncoder
  given Decoder[TastySelectedTree] = deriveDecoder

final case class TastyFreshnessEvidence(
    disposition: TastyFreshnessDisposition,
    sourceSha256Before: String,
    sourceSha256After: String
)
object TastyFreshnessEvidence:
  given Encoder[TastyFreshnessEvidence] = deriveEncoder
  given Decoder[TastyFreshnessEvidence] = deriveDecoder

final case class TastyInspectorProvenance(
    protocolVersion: String,
    implementation: String,
    scalaVersion: String,
    workerSourceSha256: String,
    toolchainSha256: String,
    targetCompilerOptionsReplayed: Boolean,
    targetPluginsReplayed: Boolean
)
object TastyInspectorProvenance:
  given Encoder[TastyInspectorProvenance] = deriveEncoder
  given Decoder[TastyInspectorProvenance] = deriveDecoder

final case class TastyPointEvidenceReport(
    schemaVersion: String = TastyPointEvidenceReport.SchemaVersion,
    status: TastyPointEvidenceStatus,
    request: TastyPointRequest,
    compile: TastyCompileEvidence,
    targetScalaVersion: Option[String],
    artifactEvidence: TastyArtifactEvidence,
    selectedTree: Option[TastySelectedTree],
    freshness: TastyFreshnessEvidence,
    inspector: Option[TastyInspectorProvenance],
    warnings: List[String]
)
object TastyPointEvidenceReport:
  val SchemaVersion = "semantic-scala.tasty-point-evidence.v1"

  given Encoder[TastyPointEvidenceReport] = deriveEncoder
  given Decoder[TastyPointEvidenceReport] = deriveDecoder
