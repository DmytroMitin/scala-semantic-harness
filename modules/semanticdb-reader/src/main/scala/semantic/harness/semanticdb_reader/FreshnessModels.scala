package semantic.harness.semanticdb_reader

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

enum FreshnessBasis:
  case SemanticdbMd5Utf8
  case EmbeddedTextExact
  case None

object FreshnessBasis:
  given Encoder[FreshnessBasis] = Encoder.encodeString.contramap(_.toString)
  given Decoder[FreshnessBasis] = Decoder.decodeString.emap(value =>
    FreshnessBasis.values.find(_.toString == value).toRight(s"Invalid FreshnessBasis: $value")
  )

enum UnverifiableReason:
  case MissingDocumentIdentity
  case MalformedDocumentDigest
  case InconsistentDocumentIdentity
  case InvalidSourceEncoding
  case UnsupportedSourceEncodingOrBom
  case NoUniqueDocumentForSource
  case AmbiguousDocumentIdentity
  case SourceUnreadable

object UnverifiableReason:
  given Encoder[UnverifiableReason] = Encoder.encodeString.contramap(_.toString)
  given Decoder[UnverifiableReason] = Decoder.decodeString.emap(value =>
    UnverifiableReason.values.find(_.toString == value).toRight(s"Invalid UnverifiableReason: $value")
  )

final case class FreshnessEvidence(
  basis: FreshnessBasis,
  documentUri: Option[String],
  documentIndex: Option[Int],
  semanticdbMd5: Option[String],
  sourceMd5: Option[String],
  sourceSnapshotSha256: Option[String],
  artifactSnapshotSha256: Option[String],
  sourceMtimeMillis: Option[Long],
  artifactMtimeMillis: Option[Long],
  detailCode: Option[String]
)

object FreshnessEvidence:
  given Encoder[FreshnessEvidence] = deriveEncoder
  given Decoder[FreshnessEvidence] = deriveDecoder

enum SourceArtifactFreshness:
  case Fresh(evidence: FreshnessEvidence)
  case Stale(evidence: FreshnessEvidence)
  case Unverifiable(reason: UnverifiableReason, evidence: FreshnessEvidence)
  case SourceChangedDuringRequest(
    beforeSha256: String,
    afterSha256: String,
    evidence: FreshnessEvidence
  )

object SourceArtifactFreshness:
  given Encoder[SourceArtifactFreshness] = Encoder.instance {
    case Fresh(evidence) => encoded("Fresh", evidence, None, None, None)
    case Stale(evidence) => encoded("Stale", evidence, None, None, None)
    case Unverifiable(reason, evidence) => encoded("Unverifiable", evidence, Some(reason), None, None)
    case SourceChangedDuringRequest(before, after, evidence) =>
      encoded("SourceChangedDuringRequest", evidence, None, Some(before), Some(after))
  }

  given Decoder[SourceArtifactFreshness] = Decoder.instance { cursor =>
    for
      status <- cursor.downField("status").as[String]
      evidence <- cursor.downField("evidence").as[FreshnessEvidence]
      value <- status match
        case "Fresh" => Right(Fresh(evidence))
        case "Stale" => Right(Stale(evidence))
        case "Unverifiable" => cursor.downField("reason").as[UnverifiableReason].map(Unverifiable(_, evidence))
        case "SourceChangedDuringRequest" =>
          for
            before <- cursor.downField("beforeSha256").as[String]
            after <- cursor.downField("afterSha256").as[String]
          yield SourceChangedDuringRequest(before, after, evidence)
        case other => Left(io.circe.DecodingFailure(s"Invalid freshness status: $other", cursor.history))
    yield value
  }

  def evidence(value: SourceArtifactFreshness): FreshnessEvidence = value match
    case Fresh(evidence) => evidence
    case Stale(evidence) => evidence
    case Unverifiable(_, evidence) => evidence
    case SourceChangedDuringRequest(_, _, evidence) => evidence

  private def encoded(
    status: String,
    evidence: FreshnessEvidence,
    reason: Option[UnverifiableReason],
    before: Option[String],
    after: Option[String]
  ): Json = Json.obj(
    "status" -> Json.fromString(status),
    "reason" -> reason.fold(Json.Null)(summon[Encoder[UnverifiableReason]].apply),
    "beforeSha256" -> before.fold(Json.Null)(Json.fromString),
    "afterSha256" -> after.fold(Json.Null)(Json.fromString),
    "evidence" -> summon[Encoder[FreshnessEvidence]].apply(evidence)
  )

final case class SourceSnapshot private (
  path: Path,
  private[harness] val content: Option[String],
  sha256: Option[String],
  md5: Option[String],
  mtimeMillis: Option[Long],
  unverifiableReason: Option[UnverifiableReason]
)

object SourceSnapshot:
  def capture(path: Path): SourceSnapshot =
    val normalized = path.toAbsolutePath.normalize()
    try
      val bytes = Files.readAllBytes(normalized)
      val sha256 = Some(Digests.sha256(bytes))
      val mtime = Some(Files.getLastModifiedTime(normalized).toMillis)
      if hasBom(bytes) then
        SourceSnapshot(normalized, None, sha256, None, mtime, Some(UnverifiableReason.UnsupportedSourceEncodingOrBom))
      else
        decodeStrict(bytes) match
          case Right(content) =>
            SourceSnapshot(normalized, Some(content), sha256, Some(Digests.md5Utf8(content)), mtime, None)
          case Left(_) =>
            SourceSnapshot(normalized, None, sha256, None, mtime, Some(UnverifiableReason.InvalidSourceEncoding))
    catch
      case _: Exception =>
        SourceSnapshot(normalized, None, None, None, None, Some(UnverifiableReason.SourceUnreadable))

  def recaptureSha256(path: Path): Option[String] =
    try Some(Digests.sha256(Files.readAllBytes(path.toAbsolutePath.normalize())))
    catch case _: Exception => None

  private def decodeStrict(bytes: Array[Byte]): Either[Throwable, String] =
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch case error: Exception => Left(error)

  private def hasBom(bytes: Array[Byte]): Boolean =
    bytes.startsWith(Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)) ||
      bytes.startsWith(Array(0xff.toByte, 0xfe.toByte)) ||
      bytes.startsWith(Array(0xfe.toByte, 0xff.toByte)) ||
      bytes.startsWith(Array(0x00.toByte, 0x00.toByte, 0xfe.toByte, 0xff.toByte)) ||
      bytes.startsWith(Array(0xff.toByte, 0xfe.toByte, 0x00.toByte, 0x00.toByte))

private[semanticdb_reader] object Digests:
  def sha256(bytes: Array[Byte]): String = hexadecimal(MessageDigest.getInstance("SHA-256").digest(bytes))
  def md5Utf8(value: String): String = hexadecimal(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)))

  private def hexadecimal(bytes: Array[Byte]): String =
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString
