package semantic.harness.semanticdb_reader

private[semanticdb_reader] enum UsageDeadlinePhase:
  case EntryAfterLimitValidation
  case EntryAfterTargetDeclarationValidation
  case BeforeWorkspaceValidation
  case BeforeWorkspaceRealPath
  case AfterWorkspaceValidation
  case AfterWorkspaceValidationExceptional
  case BeforeDeclaredRealPath
  case AfterDeclaredRealPath
  case BeforeSelectorValidation
  case AfterSelectorValidation
  case BeforeSourceDeduplication
  case DuringSourceDeduplication
  case AfterSourceDeduplication
  case BeforeArtifactDeduplication
  case DuringArtifactDeduplication
  case AfterArtifactDeduplication
  case BeforeSourcePreparation
  case BeforeSourcePathValidation
  case DuringSourcePathValidation
  case AfterSourcePathValidationExceptional
  case BeforeSourceSizeRead
  case AfterSourceSizeRead
  case AfterSourceSizeReadExceptional
  case AfterSourcePreparation
  case BeforeArtifactPreparation
  case BeforeArtifactPathValidation
  case DuringArtifactPathValidation
  case AfterArtifactPathValidationExceptional
  case BeforeArtifactSizeRead
  case AfterArtifactSizeRead
  case AfterArtifactSizeReadExceptional
  case AfterArtifactPreparation
  case BeforeTargetInventoryValidation
  case AfterTargetInventoryValidation
  case DuringSelection
  case AfterPreparation
  case BeforeArtifactRead
  case AfterArtifactRead
  case AfterArtifactReadExceptional
  case BeforeArtifactHash
  case AfterArtifactHash
  case BeforeArtifactGrouping
  case DuringArtifactGrouping
  case AfterArtifactGrouping
  case BeforeGroupParse
  case AfterGroupParse
  case AfterGroupParseExceptional
  case DuringDocumentCounting
  case BeforePointSourceRead
  case AfterPointSourceRead
  case AfterPointSourceReadExceptional
  case BeforePointDecode
  case AfterPointDecode
  case BeforePointCoordinateConversion
  case AfterPointCoordinateConversion
  case DuringPointTargetGroupSelection
  case BeforePointOccurrenceScan
  case DuringPointOccurrenceScan
  case AfterPointTargetResolution
  case BeforeDocument
  case BeforeDocumentMapping
  case AfterDocumentMapping
  case BeforeFreshnessRead
  case AfterFreshnessRead
  case AfterFreshnessReadExceptional
  case BeforeFreshnessDigest
  case AfterFreshnessDigest
  case BeforeDuplicateEvidence
  case AfterDuplicateEvidenceSort
  case DuringDuplicateEvidence
  case DuringSelectedGroupAccumulation
  case DuringDuplicateEvidenceSort
  case BeforeMappingFactAggregation
  case DuringMappingFactAggregation
  case DuringMappedSourceAccumulation
  case DuringDuplicateSourceGrouping
  case BeforeUncoveredSourceSorting
  case DuringUncoveredSourceSorting
  case AfterUncoveredSourceSorting
  case DuringUncoveredSourceWarningConstruction
  case DuringFreshnessSummaryAccumulation
  case DuringCoverageConstruction
  case DuringPreliminaryEvidenceConstruction
  case BeforeOccurrenceDocument
  case DuringOccurrenceScan
  case BeforeOccurrenceEvidence
  case BeforeOccurrenceSort
  case DuringOccurrenceSort
  case AfterOccurrenceSort
  case BeforeOutputBounding
  case DuringOutputBounding
  case DuringOutputSizeEstimation
  case AfterOutputBounding
  case BeforeFinalReport

private[semanticdb_reader] trait UsageDeadlineObserver:
  def started(startedNanos: Long, absoluteDeadlineNanos: Long): Unit
  def reached(phase: UsageDeadlinePhase): Unit

private[semanticdb_reader] object UsageDeadlineObserver:
  val Noop: UsageDeadlineObserver = new UsageDeadlineObserver:
    def started(startedNanos: Long, absoluteDeadlineNanos: Long): Unit = ()
    def reached(phase: UsageDeadlinePhase): Unit = ()

private[semanticdb_reader] final class UsageDeadline private (
  val startedNanos: Long,
  val absoluteDeadlineNanos: Long,
  private val clock: UsageMonotonicClock,
  private val observer: UsageDeadlineObserver
):
  private var observedExpiry = false

  def check(phase: UsageDeadlinePhase): Boolean =
    if observedExpiry then true
    else
      observer.reached(phase)
      observedExpiry = clock.nanoTime() - absoluteDeadlineNanos >= 0
      observedExpiry

  def expired: Boolean = observedExpiry

private[semanticdb_reader] object UsageDeadline:
  def start(
    clock: UsageMonotonicClock,
    durationNanos: Long,
    observer: UsageDeadlineObserver
  ): UsageDeadline =
    val started = clock.nanoTime()
    val absolute =
      if durationNanos > 0 && started > Long.MaxValue - durationNanos then Long.MaxValue
      else started + durationNanos
    observer.started(started, absolute)
    UsageDeadline(started, absolute, clock, observer)
