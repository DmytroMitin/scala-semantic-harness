package semantic.harness.semanticdb_reader

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import java.nio.file.Path

private[harness] enum UsagesPublicState:
  case EvidenceFound
  case NoUsagesObserved
  case CoverageIncomplete
  case TargetAmbiguous
  case TargetUnresolved
  case ArtifactStaleOrInconsistent
  case Truncated
  case UnsupportedConstruct

private[harness] object UsagesPublicState:
  given Encoder[UsagesPublicState] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicFailureKind:
  case InvalidInput
  case UnsafeFilesystem
  case IoFailure
  case ParseFailure
  case TimeoutBeforeTargetResolution
  case InternalInvariant

private[harness] object UsagesPublicFailureKind:
  given Encoder[UsagesPublicFailureKind] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicTargetMode:
  case ExplicitGlobal
  case PointSelected

private[harness] object UsagesPublicTargetMode:
  given Encoder[UsagesPublicTargetMode] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicIdentityKind:
  case Global
  case LocalDocumentOnly

private[harness] object UsagesPublicIdentityKind:
  given Encoder[UsagesPublicIdentityKind] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicOccurrenceRole:
  case Definition
  case Reference

private[harness] object UsagesPublicOccurrenceRole:
  given Encoder[UsagesPublicOccurrenceRole] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicFreshness:
  case Fresh
  case Stale
  case MissingDigest
  case Unmapped
  case AmbiguousMapping

private[harness] object UsagesPublicFreshness:
  given Encoder[UsagesPublicFreshness] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicReasonCode:
  case OpenInventory
  case SelectorExcludedScope
  case UnmappedDocument
  case AmbiguousDocumentMapping
  case UncoveredDeclaredSource
  case MissingDigest
  case MissingOccurrenceSection
  case MatchingOccurrenceHasNoRange
  case UnsafeDocumentUri
  case SyntheticsExcluded
  case StaleSourceDigest
  case ConflictingDuplicateMetadata
  case ArtifactSourceMetadataConflict
  case MultipleDistinctDocumentsForSource
  case InvalidMatchingRange
  case ExplicitLocalSymbolUnsupported
  case PointTargetAmbiguous
  case PointTargetUnresolved

private[harness] object UsagesPublicReasonCode:
  given Encoder[UsagesPublicReasonCode] = Encoder.encodeString.contramap(_.toString)

private[harness] enum UsagesPublicLimitHit:
  case TargetResolutionOccurrenceLimit
  case DocumentLimit
  case ScannedOccurrenceLimit
  case ReturnedOccurrenceLimit
  case DuplicateGroupLimit
  case DuplicatePathSampleLimit
  case WarningLimit
  case ResultEvidenceByteLimit
  case Deadline

private[harness] object UsagesPublicLimitHit:
  given Encoder[UsagesPublicLimitHit] = Encoder.encodeString.contramap(_.toString)

private[harness] final case class UsagesPublicRange(
  startLine: Int,
  startCharacter: Int,
  endLine: Int,
  endCharacter: Int,
  encoding: String = "UTF-16",
  base: Int = 0
)

private[harness] object UsagesPublicRange:
  given Encoder[UsagesPublicRange] = deriveEncoder

private[harness] final case class UsagesPublicTarget(
  mode: UsagesPublicTargetMode,
  identityKind: UsagesPublicIdentityKind,
  stableSymbol: Option[String],
  localSymbolMarker: Option[String],
  source: Option[String],
  range: Option[UsagesPublicRange],
  documentEvidenceId: Option[String]
)

private[harness] object UsagesPublicTarget:
  given Encoder[UsagesPublicTarget] = deriveEncoder

private[harness] final case class UsagesPublicSelectors(
  includeDefinitions: Boolean,
  modules: List[String],
  sourceSets: List[String],
  includeGenerated: Boolean
)

private[harness] object UsagesPublicSelectors:
  given Encoder[UsagesPublicSelectors] = deriveEncoder

private[harness] final case class UsagesPublicOccurrence(
  stableSymbol: Option[String],
  localSymbolMarker: Option[String],
  role: UsagesPublicOccurrenceRole,
  source: Option[String],
  safeUri: Option[String],
  range: UsagesPublicRange,
  module: String,
  sourceSet: String,
  generated: Boolean,
  freshness: UsagesPublicFreshness,
  artifactGroupId: String,
  documentIndex: Int
)

private[harness] object UsagesPublicOccurrence:
  given Encoder[UsagesPublicOccurrence] = deriveEncoder

private[harness] final case class UsagesPublicDuplicateGroup(
  groupId: String,
  sizeBytes: Long,
  copyCount: Int,
  representative: String,
  samplePaths: List[String],
  pathsTruncated: Boolean
)

private[harness] object UsagesPublicDuplicateGroup:
  given Encoder[UsagesPublicDuplicateGroup] = deriveEncoder

private[harness] final case class UsagesPublicInventoryBasis(
  kind: String = "ExplicitManifest",
  manifestSchemaVersion: String = UsagesManifestLoader.SchemaVersion,
  inventoryClosed: Boolean
)

private[harness] object UsagesPublicInventoryBasis:
  given Encoder[UsagesPublicInventoryBasis] = deriveEncoder

private[harness] final case class UsagesPublicCoverage(
  inventoryBasis: UsagesPublicInventoryBasis,
  declaredSources: Int,
  selectedSources: Int,
  excludedSources: Int,
  declaredArtifacts: Int,
  selectedArtifacts: Int,
  excludedArtifacts: Int,
  rawArtifactBytes: Long,
  uniqueArtifactContents: Int,
  duplicateCopies: Int,
  parsedDocuments: Int,
  mappedDocuments: Int,
  unmappedDocuments: Int,
  ambiguousDocuments: Int,
  freshDocuments: Int,
  staleDocuments: Int,
  missingDigestDocuments: Int,
  documentsWithoutOccurrences: Int,
  documentsWithSynthetics: Int,
  scannedOrdinaryOccurrences: Int,
  matchingOccurrences: Int,
  returnedOccurrences: Int,
  totalWarnings: Int,
  returnedWarnings: Int
)

private[harness] object UsagesPublicCoverage:
  given Encoder[UsagesPublicCoverage] = deriveEncoder

private[harness] final case class UsagesPublicLimits(
  artifactLimit: Int,
  documentLimit: Int,
  sourceLimit: Int,
  manifestByteLimit: Int,
  aggregateArtifactByteLimit: Long,
  perArtifactByteLimit: Long,
  perSourceByteLimit: Long,
  stringByteLimit: Int,
  pathByteLimit: Int,
  uriByteLimit: Int,
  scannedOccurrenceLimit: Int,
  returnedOccurrenceLimit: Int,
  resultEvidenceByteLimit: Int,
  warningLimit: Int,
  duplicateGroupLimit: Int,
  duplicatePathSampleLimit: Int,
  selectorValueLimit: Int,
  deadlineNanos: Long,
  hits: List[UsagesPublicLimitHit]
)

private[harness] object UsagesPublicLimits:
  given Encoder[UsagesPublicLimits] = deriveEncoder

private[harness] final case class UsagesPublicReason(
  code: UsagesPublicReasonCode,
  subject: Option[String] = None,
  count: Option[Int] = None
)

private[harness] object UsagesPublicReason:
  given Encoder[UsagesPublicReason] = deriveEncoder[UsagesPublicReason].mapJsonObject(_.filter {
    case ("subject", value) => !value.isNull
    case ("count", value)   => !value.isNull
    case _                    => true
  })

private[harness] final case class UsagesPublicResult(
  schemaVersion: String = UsagesPublicResult.SchemaVersion,
  state: UsagesPublicState,
  targetMode: UsagesPublicTargetMode,
  target: Option[UsagesPublicTarget],
  selectors: UsagesPublicSelectors,
  occurrences: List[UsagesPublicOccurrence],
  duplicateGroups: List[UsagesPublicDuplicateGroup],
  coverage: UsagesPublicCoverage,
  limits: UsagesPublicLimits,
  reasons: List[UsagesPublicReason],
  warnings: List[String]
)

private[harness] object UsagesPublicResult:
  val SchemaVersion = "semantic-scala.usages-result.v1"
  given Encoder[UsagesPublicResult] = deriveEncoder

private[harness] final case class UsagesPublicFailure(
  schemaVersion: String = UsagesPublicFailure.SchemaVersion,
  failureKind: UsagesPublicFailureKind,
  message: String
)

private[harness] object UsagesPublicFailure:
  val SchemaVersion = "semantic-scala.usages-failure.v1"
  given Encoder[UsagesPublicFailure] = deriveEncoder

private[harness] sealed trait UsagesCliTarget

private[harness] object UsagesCliTarget:
  final case class ExplicitGlobal(symbol: String) extends UsagesCliTarget
  final case class Point(source: String, line: Int, column: Int, semanticdb: String)
      extends UsagesCliTarget

private[harness] final case class UsagesCliRequest(
  workspace: Path,
  manifest: String,
  target: UsagesCliTarget,
  selectors: UsagesPublicSelectors,
  returnedOccurrenceLimit: Int
)
