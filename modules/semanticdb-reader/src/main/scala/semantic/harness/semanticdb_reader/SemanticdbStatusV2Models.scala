package semantic.harness.semanticdb_reader

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder

final case class SemanticdbDocumentSummary(
  uri: String,
  symbols: Int,
  occurrences: Int
)

object SemanticdbDocumentSummary:
  given Encoder[SemanticdbDocumentSummary] = deriveEncoder
  given Decoder[SemanticdbDocumentSummary] = deriveDecoder

final case class SemanticdbStatusCandidateV2(
  semanticdb: String,
  parseStatus: String,
  mtimeMillis: Option[Long],
  sizeBytes: Option[Long],
  contentHash: Option[String],
  duplicateGroupId: Option[String],
  duplicateCount: Option[Int],
  duplicateRepresentative: Option[String],
  documentCount: Int,
  documentsParsed: Int,
  documentsIgnored: Int,
  documentUris: List[String],
  documents: List[SemanticdbDocumentSummary],
  totalSymbols: Int,
  totalOccurrences: Int,
  error: Option[String]
)

object SemanticdbStatusCandidateV2:
  given Encoder[SemanticdbStatusCandidateV2] = deriveEncoder
  given Decoder[SemanticdbStatusCandidateV2] = deriveDecoder

final case class SemanticdbDuplicateGroup(
  duplicateGroupId: String,
  contentHash: String,
  sizeBytes: Long,
  fileCount: Int,
  representative: String,
  samplePaths: List[String],
  returnedPaths: Int,
  pathsTruncated: Boolean
)

object SemanticdbDuplicateGroup:
  given Encoder[SemanticdbDuplicateGroup] = deriveEncoder
  given Decoder[SemanticdbDuplicateGroup] = deriveDecoder

final case class SemanticdbStatusReportV2(
  schemaVersion: String = SemanticdbStatusReportV2.SchemaVersion,
  workspace: String,
  artifactStatus: String,
  coverageStatus: String,
  semanticdbFiles: Int,
  uniqueContentFiles: Int,
  duplicateFiles: Int,
  duplicateGroupCount: Int,
  parseableFiles: Int,
  unparseableFiles: Int,
  totalCandidates: Int,
  returnedCandidates: Int,
  candidateLimit: Int,
  candidatesTruncated: Boolean,
  duplicateGroups: List[SemanticdbDuplicateGroup],
  candidates: List[SemanticdbStatusCandidateV2],
  warnings: List[String],
  errors: List[String]
)

object SemanticdbStatusReportV2:
  val SchemaVersion: String = "semantic-scala.semanticdb-status.v2"

  given Encoder[SemanticdbStatusReportV2] = deriveEncoder
  given Decoder[SemanticdbStatusReportV2] = deriveDecoder
