package semantic.harness.semanticdb_reader

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder

final case class SemanticRange(
  startLine: Int,
  startCharacter: Int,
  endLine: Int,
  endCharacter: Int
)

object SemanticRange:
  given Encoder[SemanticRange] = deriveEncoder
  given Decoder[SemanticRange] = deriveDecoder

final case class SemanticSymbol(
  symbol: String,
  displayName: String,
  kind: Option[String],
  language: Option[String]
)

object SemanticSymbol:
  given Encoder[SemanticSymbol] = deriveEncoder
  given Decoder[SemanticSymbol] = deriveDecoder

final case class SemanticOccurrence(
  symbol: String,
  role: String,
  range: Option[SemanticRange]
)

object SemanticOccurrence:
  given Encoder[SemanticOccurrence] = deriveEncoder
  given Decoder[SemanticOccurrence] = deriveDecoder

final case class SemanticFileSummary(
  schemaVersion: String = SemanticFileSummary.SchemaVersion,
  uri: String,
  symbols: List[SemanticSymbol],
  occurrences: List[SemanticOccurrence]
)

object SemanticFileSummary:
  val SchemaVersion: String = "semantic-scala.symbols-result.v1"

  given Encoder[SemanticFileSummary] = deriveEncoder
  given Decoder[SemanticFileSummary] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      uri <- cursor.downField("uri").as[String]
      symbols <- cursor.downField("symbols").as[List[SemanticSymbol]]
      occurrences <- cursor.downField("occurrences").as[List[SemanticOccurrence]]
    yield SemanticFileSummary(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      uri = uri,
      symbols = symbols,
      occurrences = occurrences
    )
  }

final case class SemanticdbStatusCandidate(
  semanticdb: String,
  uri: Option[String],
  parseStatus: String,
  symbols: Option[Int],
  occurrences: Option[Int],
  mtimeMillis: Long,
  error: Option[String]
)

object SemanticdbStatusCandidate:
  given Encoder[SemanticdbStatusCandidate] = deriveEncoder
  given Decoder[SemanticdbStatusCandidate] = deriveDecoder

final case class SemanticdbStatusReport(
  schemaVersion: String = SemanticdbStatusReport.SchemaVersion,
  workspace: String,
  status: String,
  semanticdbFiles: Int,
  parseableFiles: Int,
  unparseableFiles: Int,
  sourceRoots: List[String],
  candidates: List[SemanticdbStatusCandidate],
  errors: List[String]
)

object SemanticdbStatusReport:
  val SchemaVersion: String = "semantic-scala.semanticdb-status.v1"

  given Encoder[SemanticdbStatusReport] = deriveEncoder
  given Decoder[SemanticdbStatusReport] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      workspace <- cursor.downField("workspace").as[String]
      status <- cursor.downField("status").as[String]
      semanticdbFiles <- cursor.downField("semanticdbFiles").as[Int]
      parseableFiles <- cursor.downField("parseableFiles").as[Int]
      unparseableFiles <- cursor.downField("unparseableFiles").as[Int]
      sourceRoots <- cursor.downField("sourceRoots").as[List[String]]
      candidates <- cursor.downField("candidates").as[List[SemanticdbStatusCandidate]]
      errors <- cursor.downField("errors").as[List[String]]
    yield SemanticdbStatusReport(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      workspace = workspace,
      status = status,
      semanticdbFiles = semanticdbFiles,
      parseableFiles = parseableFiles,
      unparseableFiles = unparseableFiles,
      sourceRoots = sourceRoots,
      candidates = candidates,
      errors = errors
    )
  }

final case class SemanticdbSourceMatch(
  semanticdb: String,
  uri: Option[String],
  parseStatus: String,
  matchKind: String,
  symbols: Option[Int],
  occurrences: Option[Int],
  mtimeMillis: Long,
  error: Option[String]
)

object SemanticdbSourceMatch:
  given Encoder[SemanticdbSourceMatch] = deriveEncoder
  given Decoder[SemanticdbSourceMatch] = deriveDecoder

final case class SemanticdbForSourceReport(
  schemaVersion: String = SemanticdbForSourceReport.SchemaVersion,
  workspace: String,
  sourceFile: String,
  sourceRelativePath: Option[String],
  status: String,
  semanticdbFiles: Int,
  parseableFiles: Int,
  unparseableFiles: Int,
  matches: List[SemanticdbSourceMatch],
  candidatesConsidered: Int,
  warnings: List[String],
  errors: List[String]
)

object SemanticdbForSourceReport:
  val SchemaVersion: String = "semantic-scala.semanticdb-for-source.v1"

  given Encoder[SemanticdbForSourceReport] = deriveEncoder
  given Decoder[SemanticdbForSourceReport] = deriveDecoder
