package semantic.harness.semanticdb_reader

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder

final case class SemanticdbCoverageInventoryBasis(
  kind: String,
  extensions: List[String],
  excludedDirectoryNames: List[String],
  followsSymbolicLinks: Boolean,
  includesFilesOutsideConventionalRoots: Boolean
)

object SemanticdbCoverageInventoryBasis:
  given Encoder[SemanticdbCoverageInventoryBasis] = deriveEncoder
  given Decoder[SemanticdbCoverageInventoryBasis] = deriveDecoder

final case class SemanticdbCoverageMatch(
  documentEvidenceId: String,
  uri: String,
  normalizedUri: String,
  contentHash: String,
  documentIndex: Int,
  matchKind: String,
  artifactCopies: Int,
  artifactPathSamples: List[String],
  returnedArtifactPaths: Int,
  artifactPathsTruncated: Boolean,
  symbols: Int,
  occurrences: Int
)

object SemanticdbCoverageMatch:
  given Encoder[SemanticdbCoverageMatch] = deriveEncoder
  given Decoder[SemanticdbCoverageMatch] = deriveDecoder

final case class SemanticdbSourceCoverageEntry(
  source: String,
  language: String,
  coverage: String,
  matchKind: Option[String],
  matches: List[SemanticdbCoverageMatch],
  warnings: List[String]
)

object SemanticdbSourceCoverageEntry:
  given Encoder[SemanticdbSourceCoverageEntry] = deriveEncoder
  given Decoder[SemanticdbSourceCoverageEntry] = deriveDecoder

final case class SemanticdbUnmatchedDocumentEvidence(
  documentEvidenceId: String,
  uri: String,
  normalizedUri: String,
  contentHash: String,
  documentIndex: Int,
  artifactCopies: Int,
  artifactPathSamples: List[String],
  returnedArtifactPaths: Int,
  artifactPathsTruncated: Boolean,
  symbols: Int,
  occurrences: Int
)

object SemanticdbUnmatchedDocumentEvidence:
  given Encoder[SemanticdbUnmatchedDocumentEvidence] = deriveEncoder
  given Decoder[SemanticdbUnmatchedDocumentEvidence] = deriveDecoder

final case class SemanticdbCoverageReport(
  schemaVersion: String = SemanticdbCoverageReport.SchemaVersion,
  workspace: String,
  coverageStatus: String,
  inventoryBasis: SemanticdbCoverageInventoryBasis,
  sourceFiles: Int,
  coveredSourceFiles: Int,
  uncoveredSourceFiles: Int,
  ambiguousSourceFiles: Int,
  semanticdbArtifactFiles: Int,
  uniqueArtifactContents: Int,
  rawDocumentEntries: Int,
  uniqueDocumentEvidence: Int,
  matchedDocumentEvidence: Int,
  unmatchedDocumentEvidence: Int,
  ambiguousDocumentEvidence: Int,
  totalSourceEntries: Int,
  returnedSourceEntries: Int,
  sourceEntryLimit: Int,
  sourceEntriesTruncated: Boolean,
  totalUnmatchedDocumentEntries: Int,
  returnedUnmatchedDocumentEntries: Int,
  documentEntryLimit: Int,
  unmatchedDocumentEntriesTruncated: Boolean,
  sources: List[SemanticdbSourceCoverageEntry],
  unmatchedDocuments: List[SemanticdbUnmatchedDocumentEvidence],
  warnings: List[String],
  errors: List[String]
)

object SemanticdbCoverageReport:
  val SchemaVersion: String = "semantic-scala.semanticdb-coverage.v1"

  given Encoder[SemanticdbCoverageReport] = deriveEncoder
  given Decoder[SemanticdbCoverageReport] = deriveDecoder
