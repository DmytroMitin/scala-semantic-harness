package semantic.harness.semanticdb_reader

import java.nio.file.Path

private[semanticdb_reader] enum UsagesBySymbolState:
  case EvidenceFound
  case NoUsagesObserved
  case CoverageIncomplete
  case TargetAmbiguous
  case TargetUnresolved
  case ArtifactStaleOrInconsistent
  case Truncated
  case UnsupportedConstruct

private[semanticdb_reader] enum UsagesBySymbolFailureKind:
  case InvalidInput
  case UnsafeFilesystem
  case IoFailure
  case ParseFailure
  case TimeoutBeforeTargetResolution
  case InternalInvariant

private[semanticdb_reader] final case class UsagesBySymbolFailure(
  kind: UsagesBySymbolFailureKind,
  message: String
)

private[semanticdb_reader] final case class DeclaredUsageSource(
  path: String,
  module: String,
  sourceSet: String,
  generated: Boolean
)

private[semanticdb_reader] final case class DeclaredUsageArtifact(
  path: String,
  module: String,
  sourceSet: String,
  generated: Boolean
)

private[semanticdb_reader] sealed trait UsagesBySymbolTarget

private[semanticdb_reader] object UsagesBySymbolTarget:
  final case class ExplicitGlobal(symbol: String) extends UsagesBySymbolTarget

  final case class Point(
    source: String,
    line: Int,
    column: Int,
    semanticdb: String
  ) extends UsagesBySymbolTarget

private[semanticdb_reader] final case class UsagesBySymbolSelectors(
  includeDefinitions: Boolean = false,
  modules: Set[String] = Set.empty,
  sourceSets: Set[String] = Set.empty,
  includeGenerated: Boolean = false
)

private[semanticdb_reader] final case class UsagesBySymbolRequest(
  workspace: Path,
  inventoryClosed: Boolean,
  sources: List[DeclaredUsageSource],
  artifacts: List[DeclaredUsageArtifact],
  target: UsagesBySymbolTarget,
  selectors: UsagesBySymbolSelectors = UsagesBySymbolSelectors()
)

private[semanticdb_reader] enum UsageTargetMode:
  case ExplicitGlobal
  case PointSelected

private[semanticdb_reader] enum UsageIdentityKind:
  case Global
  case LocalDocumentOnly

private[semanticdb_reader] enum UsageOccurrenceRole:
  case Definition
  case Reference

private[semanticdb_reader] enum UsageFreshness:
  case Fresh
  case Stale
  case MissingDigest
  case Unmapped
  case AmbiguousMapping

private[semanticdb_reader] final case class UsageTargetEvidence(
  mode: UsageTargetMode,
  identityKind: UsageIdentityKind,
  stableSymbol: Option[String],
  localSymbolMarker: Option[String],
  source: Option[String],
  range: Option[SemanticRange],
  documentEvidenceId: Option[String]
)

private[semanticdb_reader] final case class UsageOccurrenceEvidence(
  stableSymbol: Option[String],
  localSymbolMarker: Option[String],
  role: UsageOccurrenceRole,
  source: Option[String],
  safeUri: Option[String],
  range: SemanticRange,
  module: String,
  sourceSet: String,
  generated: Boolean,
  freshness: UsageFreshness,
  artifactGroupId: String,
  documentIndex: Int
)

private[semanticdb_reader] final case class UsageDuplicateGroupEvidence(
  groupId: String,
  sizeBytes: Long,
  copyCount: Int,
  representative: String,
  samplePaths: List[String],
  pathsTruncated: Boolean
)

private[semanticdb_reader] final case class UsageLimitEvidence(
  artifactLimit: Int,
  documentLimit: Int,
  sourceLimit: Int,
  aggregateArtifactByteLimit: Long,
  perArtifactByteLimit: Long,
  perSourceByteLimit: Long,
  scannedOccurrenceLimit: Int,
  returnedOccurrenceLimit: Int,
  resultEvidenceByteLimit: Int,
  warningLimit: Int,
  duplicateGroupLimit: Int,
  deadlineNanos: Long,
  hit: List[String]
)

private[semanticdb_reader] final case class UsageCoverageEvidence(
  inventoryClosed: Boolean,
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

private[semanticdb_reader] final case class UsagesBySymbolReport(
  state: UsagesBySymbolState,
  target: Option[UsageTargetEvidence],
  occurrences: List[UsageOccurrenceEvidence],
  duplicateGroups: List[UsageDuplicateGroupEvidence],
  coverage: UsageCoverageEvidence,
  limits: UsageLimitEvidence,
  warnings: List[String]
)

private[semanticdb_reader] final case class UsagesBySymbolLimits(
  maxArtifacts: Int,
  maxDocuments: Int,
  maxSources: Int,
  maxAggregateArtifactBytes: Long,
  maxArtifactBytes: Long,
  maxSourceBytes: Long,
  maxScannedOccurrences: Int,
  maxReturnedOccurrences: Int,
  maxResultEvidenceBytes: Int,
  maxStringBytes: Int,
  maxPathBytes: Int,
  maxUriBytes: Int,
  maxWarnings: Int,
  maxDuplicateGroups: Int,
  maxDuplicatePathSamples: Int,
  maxSelectorValues: Int,
  deadlineNanos: Long
)

private[semanticdb_reader] object UsagesBySymbolLimits:
  val HardMaxArtifacts = 256
  val HardMaxDocuments = 4096
  val HardMaxSources = 50000
  val HardMaxAggregateArtifactBytes: Long = 256L * 1024L * 1024L
  val HardMaxArtifactBytes: Long = 16L * 1024L * 1024L
  val HardMaxSourceBytes: Long = 8L * 1024L * 1024L
  val HardMaxScannedOccurrences = 1000000
  val HardMaxReturnedOccurrences = 500
  val HardMaxResultEvidenceBytes = 1024 * 1024
  val HardMaxStringBytes = 1024
  val HardMaxPathBytes = 4096
  val HardMaxUriBytes = 4096
  val HardMaxWarnings = 100
  val HardMaxDuplicateGroups = 100
  val HardMaxDuplicatePathSamples = 20
  val HardMaxSelectorValues = 256
  val HardDeadlineNanos: Long = 30L * 1000L * 1000L * 1000L

  val Default = UsagesBySymbolLimits(
    maxArtifacts = HardMaxArtifacts,
    maxDocuments = HardMaxDocuments,
    maxSources = HardMaxSources,
    maxAggregateArtifactBytes = HardMaxAggregateArtifactBytes,
    maxArtifactBytes = HardMaxArtifactBytes,
    maxSourceBytes = HardMaxSourceBytes,
    maxScannedOccurrences = HardMaxScannedOccurrences,
    maxReturnedOccurrences = HardMaxReturnedOccurrences,
    maxResultEvidenceBytes = HardMaxResultEvidenceBytes,
    maxStringBytes = HardMaxStringBytes,
    maxPathBytes = HardMaxPathBytes,
    maxUriBytes = HardMaxUriBytes,
    maxWarnings = HardMaxWarnings,
    maxDuplicateGroups = HardMaxDuplicateGroups,
    maxDuplicatePathSamples = HardMaxDuplicatePathSamples,
    maxSelectorValues = HardMaxSelectorValues,
    deadlineNanos = HardDeadlineNanos
  )

private[semanticdb_reader] trait UsageMonotonicClock:
  def nanoTime(): Long

private[semanticdb_reader] object UsageMonotonicClock:
  val System: UsageMonotonicClock = new UsageMonotonicClock:
    def nanoTime(): Long = java.lang.System.nanoTime()
