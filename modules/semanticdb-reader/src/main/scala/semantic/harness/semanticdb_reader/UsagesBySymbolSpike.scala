package semantic.harness.semanticdb_reader

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.util.control.NonFatal

private[semanticdb_reader] object UsagesBySymbolSpike:
  val LocalSymbolMarker = "local-document-symbol"

  private final case class PreparedSource(
    declaration: DeclaredUsageSource,
    path: Path,
    sizeBytes: Long
  )

  private final case class PreparedArtifact(
    declaration: DeclaredUsageArtifact,
    path: Path,
    sizeBytes: Long
  )

  private final case class PreparedRequest(
    workspace: Path,
    sources: List[PreparedSource],
    artifacts: List[PreparedArtifact],
    selectedSources: List[PreparedSource],
    selectedArtifacts: List[PreparedArtifact],
    targetArtifact: Option[PreparedArtifact],
    targetSource: Option[PreparedSource],
    scopeExcluded: Boolean,
    declaredSourceCount: Int,
    selectedSourceCount: Int,
    declaredArtifactCount: Int,
    selectedArtifactCount: Int,
    rawArtifactBytes: Long
  )

  private final case class RawArtifact(
    prepared: PreparedArtifact,
    bytes: Array[Byte],
    hash: String
  )

  private final case class ArtifactGroup(
    groupId: String,
    members: List[RawArtifact],
    representative: RawArtifact,
    metadataConflict: Boolean
  )

  private final case class ParsedGroup(
    group: ArtifactGroup,
    documents: List[TextDocument]
  )

  private enum InitialTarget:
    case ExplicitResolved(target: ResolvedTarget)
    case ExplicitUnsupported
    case Point

  private enum PreparationResult:
    case Complete(prepared: PreparedRequest)
    case Deadline

  private enum DeadlineValue[+A]:
    case Complete(value: A)
    case Deadline

  private enum BoundedOperationResult[+A]:
    case Complete(value: A)
    case Deadline
    case Failed(failure: UsagesBySymbolFailure)

  private enum SourceMapping:
    case Unique(source: PreparedSource)
    case Missing
    case Ambiguous
    case UnsafeUri

  private enum FreshnessResult:
    case Complete(value: UsageFreshness)
    case Deadline

  private final case class DocumentWork(
    group: ArtifactGroup,
    artifact: PreparedArtifact,
    document: TextDocument,
    documentIndex: Int,
    safeUri: Option[String],
    mapping: SourceMapping,
    freshness: UsageFreshness
  ):
    val evidenceId: String = s"${group.groupId}#document-$documentIndex"

  private final case class ResolvedTarget(
    symbol: String,
    identityKind: UsageIdentityKind,
    evidence: UsageTargetEvidence,
    localDocumentId: Option[String]
  )

  private enum PointResolution:
    case Resolved(target: ResolvedTarget)
    case Unresolved
    case Ambiguous
    case ScanLimit

  private final case class ScanResult(
    occurrences: List[UsageOccurrenceEvidence],
    matchingOccurrences: Int,
    scannedOccurrences: Int,
    hit: List[String],
    inconsistent: Boolean,
    incomplete: Boolean
  )

  private final case class DocumentBuildResult(
    work: List[DocumentWork],
    deadlineHit: Boolean
  )

  private final case class OutputBound(
    occurrences: List[UsageOccurrenceEvidence],
    duplicateGroups: List[UsageDuplicateGroupEvidence],
    warnings: List[String],
    hit: Boolean,
    deadlineHit: Boolean
  )

  private final class WarningCollector(limit: Int, maxBytes: Int):
    private val values = ListBuffer.empty[String]
    private var count = 0
    private var overflow = false

    def add(category: String, subject: Option[String] = None): Unit =
      count += 1
      val suffix = subject.filter(_.nonEmpty).map(value => s": $value").getOrElse("")
      val value = boundedUtf8(s"$category$suffix", maxBytes)
      if values.size < limit then
        var index = 0
        while index < values.size && values(index) <= value do index += 1
        values.insert(index, value)
      else overflow = true

    def total: Int = count
    def retained: List[String] = values.toList
    def retained(
      deadline: UsageDeadline,
      phase: UsageDeadlinePhase
    ): DeadlineValue[List[String]] =
      val result = ListBuffer.empty[String]
      var index = 0
      while index < values.size && !deadline.expired do
        if deadline.check(phase) then ()
        else result += values(index)
        index += 1
      if deadline.expired then DeadlineValue.Deadline
      else DeadlineValue.Complete(result.toList)
    def retainedCount: Int = values.size
    def truncated: Boolean = overflow

  def run(
    request: UsagesBySymbolRequest,
    limits: UsagesBySymbolLimits = UsagesBySymbolLimits.Default,
    clock: UsageMonotonicClock = UsageMonotonicClock.System,
    deadlineObserver: UsageDeadlineObserver = UsageDeadlineObserver.Noop,
    boundedOperations: UsagesBySymbolBoundedOperations = UsagesBySymbolBoundedOperations.Default
  ): Either[UsagesBySymbolFailure, UsagesBySymbolReport] =
    val deadline = UsageDeadline.start(clock, limits.deadlineNanos, deadlineObserver)
    validateLimits(limits).flatMap { _ =>
      validateInitialTarget(request.target, limits).flatMap { initialTarget =>
        val expiredAfterValidation =
          deadline.check(UsageDeadlinePhase.EntryAfterLimitValidation) ||
            deadline.check(UsageDeadlinePhase.EntryAfterTargetDeclarationValidation)
        if expiredAfterValidation then deadlineBeforePreparation(request, limits, initialTarget)
        else
          prepare(request, limits, deadline, boundedOperations).flatMap {
            case PreparationResult.Deadline =>
              deadlineBeforePreparation(request, limits, initialTarget)
            case PreparationResult.Complete(prepared) =>
              execute(request, prepared, limits, deadline, initialTarget, boundedOperations)
          }
      }
    }

  private def validateInitialTarget(
    target: UsagesBySymbolTarget,
    limits: UsagesBySymbolLimits
  ): Either[UsagesBySymbolFailure, InitialTarget] =
    target match
      case UsagesBySymbolTarget.ExplicitGlobal(symbol) =>
        if !validSymbol(symbol, limits.maxStringBytes) then
          Left(invalid("Explicit symbol must be non-empty and within the symbol byte limit"))
        else if localSymbol(symbol) then Right(InitialTarget.ExplicitUnsupported)
        else
          Right(
            InitialTarget.ExplicitResolved(
              ResolvedTarget(
                symbol = symbol,
                identityKind = UsageIdentityKind.Global,
                evidence = UsageTargetEvidence(
                  mode = UsageTargetMode.ExplicitGlobal,
                  identityKind = UsageIdentityKind.Global,
                  stableSymbol = Some(symbol),
                  localSymbolMarker = None,
                  source = None,
                  range = None,
                  documentEvidenceId = None
                ),
                localDocumentId = None
              )
            )
          )
      case point: UsagesBySymbolTarget.Point =>
        if point.line <= 0 || point.column <= 0 then
          Left(invalid("Point line and column must be positive one-based values"))
        else Right(InitialTarget.Point)

  private def deadlineBeforePreparation(
    request: UsagesBySymbolRequest,
    limits: UsagesBySymbolLimits,
    initialTarget: InitialTarget
  ): Either[UsagesBySymbolFailure, UsagesBySymbolReport] =
    initialTarget match
      case InitialTarget.Point => Left(timeoutBeforeTargetResolution)
      case InitialTarget.ExplicitUnsupported =>
        Right(
          preparationTerminalReport(
            request,
            limits,
            UsagesBySymbolState.UnsupportedConstruct,
            None,
            Nil
          )
        )
      case InitialTarget.ExplicitResolved(target) =>
        Right(
          preparationTerminalReport(
            request,
            limits,
            UsagesBySymbolState.Truncated,
            Some(target.evidence),
            List("Deadline")
          )
        )

  private def preparationTerminalReport(
    request: UsagesBySymbolRequest,
    limits: UsagesBySymbolLimits,
    state: UsagesBySymbolState,
    target: Option[UsageTargetEvidence],
    hit: List[String]
  ): UsagesBySymbolReport =
    UsagesBySymbolReport(
      state = state,
      target = target,
      occurrences = Nil,
      duplicateGroups = Nil,
      coverage = UsageCoverageEvidence(
        inventoryClosed = request.inventoryClosed,
        declaredSources = request.sources.size,
        selectedSources = 0,
        excludedSources = 0,
        declaredArtifacts = request.artifacts.size,
        selectedArtifacts = 0,
        excludedArtifacts = 0,
        rawArtifactBytes = 0L,
        uniqueArtifactContents = 0,
        duplicateCopies = 0,
        parsedDocuments = 0,
        mappedDocuments = 0,
        unmappedDocuments = 0,
        ambiguousDocuments = 0,
        freshDocuments = 0,
        staleDocuments = 0,
        missingDigestDocuments = 0,
        documentsWithoutOccurrences = 0,
        documentsWithSynthetics = 0,
        scannedOrdinaryOccurrences = 0,
        matchingOccurrences = 0,
        returnedOccurrences = 0,
        totalWarnings = 0,
        returnedWarnings = 0
      ),
      limits = limitEvidence(limits, hit),
      warnings = Nil
    )

  private def timeoutBeforeTargetResolution: UsagesBySymbolFailure =
    failure(
      UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution,
      "Deadline expired before point target resolution"
    )

  private def observeBoundedOperation[A](
    deadline: UsageDeadline,
    beforePhase: UsageDeadlinePhase,
    afterPhase: UsageDeadlinePhase,
    exceptionalPhase: UsageDeadlinePhase
  )(
    operation: => A,
    failureValue: => UsagesBySymbolFailure
  ): BoundedOperationResult[A] =
    if deadline.check(beforePhase) then BoundedOperationResult.Deadline
    else
      try
        val value = operation
        if deadline.check(afterPhase) then BoundedOperationResult.Deadline
        else BoundedOperationResult.Complete(value)
      catch
        case NonFatal(_) =>
          if deadline.check(exceptionalPhase) then BoundedOperationResult.Deadline
          else BoundedOperationResult.Failed(failureValue)

  private def execute(
    request: UsagesBySymbolRequest,
    prepared: PreparedRequest,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    initialTarget: InitialTarget,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, UsagesBySymbolReport] =
    val warnings = WarningCollector(limits.maxWarnings, limits.maxStringBytes)
    val explicitTarget = initialTarget match
      case InitialTarget.ExplicitUnsupported =>
        return Right(
          targetTerminalReport(
            request,
            prepared,
            limits,
            UsagesBySymbolState.UnsupportedConstruct,
            None,
            warnings
          )
        )
      case InitialTarget.ExplicitResolved(target) => Some(target)
      case InitialTarget.Point                    => None

    readRawArtifacts(
      prepared.artifacts,
      limits,
      deadline,
      explicitTarget.nonEmpty,
      boundedOperations
    ).flatMap {
      case (rawArtifacts, rawDeadlineHit) =>
        artifactGroups(rawArtifacts, deadline, explicitTarget.nonEmpty).flatMap {
          case (groups, groupingDeadlineHit) =>
          val targetPath = prepared.targetArtifact.map(_.declaration.path)
          val requiredGroups = groups
          val duplicateConflict = groups.exists(_.metadataConflict)
          if duplicateConflict then warnings.add("ConflictingDuplicateMetadata")

          parseGroups(
            requiredGroups,
            targetPath,
            limits,
            deadline,
            explicitTarget.nonEmpty,
            boundedOperations
          ).flatMap { case (parsedGroups, parseDeadlineHit) =>
            val targetResult =
              explicitTarget match
                case Some(target) => Right(PointResolution.Resolved(target))
                case None =>
                  resolvePointTarget(
                    request.target.asInstanceOf[UsagesBySymbolTarget.Point],
                    prepared,
                    parsedGroups,
                    limits,
                    deadline,
                    boundedOperations
                  )

            targetResult.flatMap {
              case PointResolution.Unresolved =>
                Right(
                  targetTerminalReport(
                    request,
                    prepared,
                    limits,
                    UsagesBySymbolState.TargetUnresolved,
                    None,
                    warnings
                  )
                )
              case PointResolution.Ambiguous =>
                Right(
                  targetTerminalReport(
                    request,
                    prepared,
                    limits,
                    UsagesBySymbolState.TargetAmbiguous,
                    None,
                    warnings
                  )
                )
              case PointResolution.ScanLimit =>
                Right(
                  targetTerminalReport(
                    request,
                    prepared,
                    limits,
                    UsagesBySymbolState.Truncated,
                    None,
                    warnings,
                    hit = List("TargetResolutionOccurrenceLimit")
                  )
                )
              case PointResolution.Resolved(target) =>
                buildReport(
                  request = request,
                  prepared = prepared,
                  parsedGroups = parsedGroups,
                  allGroups = groups,
                  target = target,
                  deadlineAlreadyHit =
                    rawDeadlineHit || groupingDeadlineHit || parseDeadlineHit || deadline.expired,
                  duplicateConflict = duplicateConflict,
                  limits = limits,
                  deadline = deadline,
                  warnings = warnings,
                  boundedOperations = boundedOperations
                )
            }
          }
        }
    }

  private def buildReport(
    request: UsagesBySymbolRequest,
    prepared: PreparedRequest,
    parsedGroups: List[ParsedGroup],
    allGroups: List[ArtifactGroup],
    target: ResolvedTarget,
    deadlineAlreadyHit: Boolean,
    duplicateConflict: Boolean,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    warnings: WarningCollector,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, UsagesBySymbolReport] =
    if deadlineAlreadyHit then
      return Right(
        targetTerminalReport(
          request,
          prepared,
          limits,
          UsagesBySymbolState.Truncated,
          Some(target.evidence),
          warnings,
          hit = List("Deadline")
        )
      )

    val selectedPaths = prepared.selectedArtifacts.map(_.declaration.path).toSet
    val selectedParsed = parsedGroups.filter(_.group.members.exists(member =>
      selectedPaths.contains(member.prepared.declaration.path)
    ))
    val documentLimitHit = selectedParsed.map(_.documents.size).sum > limits.maxDocuments
    val documentsBuilder = ListBuffer.empty[(ParsedGroup, (TextDocument, Int))]
    var parsedIndex = 0
    while parsedIndex < selectedParsed.size &&
        documentsBuilder.size < limits.maxDocuments &&
        !deadline.expired
    do
      val parsed = selectedParsed(parsedIndex)
      var documentIndex = 0
      while documentIndex < parsed.documents.size &&
          documentsBuilder.size < limits.maxDocuments &&
          !deadline.expired
      do
        if deadline.check(UsageDeadlinePhase.DuringDocumentCounting) then ()
        else documentsBuilder += parsed -> (parsed.documents(documentIndex) -> documentIndex)
        documentIndex += 1
      parsedIndex += 1

    if deadline.expired then
      return Right(
        targetTerminalReport(
          request,
          prepared,
          limits,
          UsagesBySymbolState.Truncated,
          Some(target.evidence),
          warnings,
          hit = List("Deadline")
        )
      )
    val documents = documentsBuilder.toList

    buildDocumentWork(
      documents,
      prepared,
      limits,
      deadline,
      warnings,
      boundedOperations
    ) match
      case Left(value) => Left(value)
      case Right(documentBuild) if documentBuild.deadlineHit =>
        Right(
          targetTerminalReport(
            request,
            prepared,
            limits,
            UsagesBySymbolState.Truncated,
            Some(target.evidence),
            warnings,
            hit = List("Deadline")
          )
        )
      case Right(documentBuild) =>
        finishReportAfterDocuments(
          request,
          prepared,
          documentBuild.work,
          allGroups,
          selectedPaths,
          target,
          documentLimitHit,
          duplicateConflict,
          limits,
          deadline,
          warnings
        )

  private def finishReportAfterDocuments(
    request: UsagesBySymbolRequest,
    prepared: PreparedRequest,
    work: List[DocumentWork],
    allGroups: List[ArtifactGroup],
    selectedPaths: Set[String],
    target: ResolvedTarget,
    documentLimitHit: Boolean,
    duplicateConflict: Boolean,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    warnings: WarningCollector
  ): Either[UsagesBySymbolFailure, UsagesBySymbolReport] =
    val selectedGroupRows = ListBuffer.empty[ArtifactGroup]
    val allGroupsIterator = allGroups.iterator
    while allGroupsIterator.hasNext && !deadline.expired do
      val group = allGroupsIterator.next()
      val memberIterator = group.members.iterator
      var selected = false
      while memberIterator.hasNext && !selected && !deadline.expired do
        if deadline.check(UsageDeadlinePhase.DuringSelectedGroupAccumulation) then ()
        else
          selected = selectedPaths.contains(memberIterator.next().prepared.declaration.path)
      if selected then selectedGroupRows += group
    if deadline.expired then
      return Right(deadlineTargetReport(request, prepared, target, limits, warnings))

    val selectedGroups = selectedGroupRows.toList
    val duplicateEvidenceRows = ListBuffer.empty[UsageDuplicateGroupEvidence]
    if deadline.check(UsageDeadlinePhase.BeforeDuplicateEvidence) then
      return Right(deadlineTargetReport(request, prepared, target, limits, warnings))

    var duplicateCandidates = scala.collection.immutable.TreeMap.empty[String, ArtifactGroup]
    val selectedGroupsIterator = selectedGroups.iterator
    while selectedGroupsIterator.hasNext && !deadline.expired do
      val group = selectedGroupsIterator.next()
      if group.members match
          case _ :: _ :: _ => true
          case _           => false
      then
        if deadline.check(UsageDeadlinePhase.DuringDuplicateEvidenceSort) then ()
        else duplicateCandidates = duplicateCandidates.updated(group.groupId, group)
    if deadline.check(UsageDeadlinePhase.AfterDuplicateEvidenceSort) then
      return Right(deadlineTargetReport(request, prepared, target, limits, warnings))

    val duplicateBoundHit = duplicateCandidates.size > limits.maxDuplicateGroups
    val duplicateIterator = duplicateCandidates.valuesIterator
    var duplicatePathBoundHit = false
    while duplicateIterator.hasNext &&
        duplicateEvidenceRows.size < limits.maxDuplicateGroups &&
        !deadline.expired
    do
      duplicateEvidence(duplicateIterator.next(), limits, deadline) match
        case DeadlineValue.Deadline => ()
        case DeadlineValue.Complete(value) =>
          duplicateEvidenceRows += value
          if value.pathsTruncated then duplicatePathBoundHit = true

    if deadline.expired then
      Right(deadlineTargetReport(request, prepared, target, limits, warnings))
    else
      val boundedDuplicateEvidence = duplicateEvidenceRows.toList
      if duplicateBoundHit then warnings.add("DuplicateGroupEvidenceLimitReached")
      if duplicatePathBoundHit then warnings.add("DuplicatePathEvidenceLimitReached")

      val mappingFacts = mappingFactsFor(work, prepared, warnings, deadline) match
        case DeadlineValue.Deadline =>
          return Right(deadlineTargetReport(request, prepared, target, limits, warnings))
        case DeadlineValue.Complete(value) => value
      val freshnessFacts = freshnessFactsFor(work, deadline) match
        case DeadlineValue.Deadline =>
          return Right(deadlineTargetReport(request, prepared, target, limits, warnings))
        case DeadlineValue.Complete(value) => value

      val coverageSnapshot = coverageBeforeScan(
        request,
        prepared,
        selectedGroups,
        mappingFacts,
        freshnessFacts,
        warnings,
        deadline
      ) match
        case DeadlineValue.Deadline =>
          return Right(deadlineTargetReport(request, prepared, target, limits, warnings))
        case DeadlineValue.Complete(value) => value

      val scan = scanOccurrences(
        request,
        work,
        target,
        prepared.selectedSources,
        limits,
        deadline,
        warnings
      )
      val preliminaryCoverage = coverageSnapshot.copy(
        scannedOrdinaryOccurrences = scan.scannedOccurrences,
        matchingOccurrences = scan.matchingOccurrences,
        returnedOccurrences = scan.occurrences.size,
        totalWarnings = warnings.total,
        returnedWarnings = warnings.retainedCount
      )
      val initialHit =
        List(
          Option.when(documentLimitHit)("DocumentLimit"),
          Option.when(duplicateBoundHit)("DuplicateGroupLimit"),
          Option.when(duplicatePathBoundHit)("DuplicatePathSampleLimit"),
          Option.when(warnings.truncated)("WarningLimit")
        ).flatten ++ scan.hit
      if deadline.expired then
        return Right(
          deadlineReportWithCoverage(
            target,
            preliminaryCoverage,
            limits,
            initialHit
          )
        )

      val inconsistent =
        duplicateConflict ||
          mappingFacts.inconsistent ||
          freshnessFacts.stale > 0 ||
          scan.inconsistent
      val incomplete =
        prepared.scopeExcluded ||
          !request.inventoryClosed ||
          mappingFacts.incomplete ||
          freshnessFacts.missingDigest > 0 ||
          mappingFacts.withoutOccurrences > 0 ||
          scan.incomplete

      val retainedWarnings = warnings.retained(
        deadline,
        UsageDeadlinePhase.DuringPreliminaryEvidenceConstruction
      ) match
        case DeadlineValue.Deadline =>
          return Right(
            deadlineReportWithCoverage(target, preliminaryCoverage, limits, initialHit)
          )
        case DeadlineValue.Complete(value) => value
      if deadline.check(UsageDeadlinePhase.DuringPreliminaryEvidenceConstruction) then
        return Right(deadlineReportWithCoverage(target, preliminaryCoverage, limits, initialHit))
      val preliminaryLimits = limitEvidence(limits, initialHit)
      val preliminary = UsagesBySymbolReport(
        state = UsagesBySymbolState.EvidenceFound,
        target = Some(target.evidence),
        occurrences = scan.occurrences,
        duplicateGroups = boundedDuplicateEvidence,
        coverage = preliminaryCoverage,
        limits = preliminaryLimits,
        warnings = retainedWarnings
      )
      val output = boundOutput(preliminary, limits.maxResultEvidenceBytes, deadline)
      val finalHit =
        (
          initialHit ++
            Option.when(output.hit)("ResultEvidenceByteLimit") ++
            Option.when(output.deadlineHit || deadline.check(UsageDeadlinePhase.BeforeFinalReport))(
              "Deadline"
            )
        ).distinct.sorted
      val truncated = finalHit.nonEmpty
      val state =
        if truncated then UsagesBySymbolState.Truncated
        else if inconsistent then UsagesBySymbolState.ArtifactStaleOrInconsistent
        else if incomplete then UsagesBySymbolState.CoverageIncomplete
        else if output.occurrences.nonEmpty then UsagesBySymbolState.EvidenceFound
        else UsagesBySymbolState.NoUsagesObserved
      val finalCoverage = preliminaryCoverage.copy(
        returnedOccurrences = output.occurrences.size,
        returnedWarnings = output.warnings.size
      )

      Right(
        UsagesBySymbolReport(
          state = state,
          target = Some(target.evidence),
          occurrences = output.occurrences,
          duplicateGroups = output.duplicateGroups,
          coverage = finalCoverage,
          limits = limitEvidence(limits, finalHit),
          warnings = output.warnings
        )
      )

  private def deadlineTargetReport(
    request: UsagesBySymbolRequest,
    prepared: PreparedRequest,
    target: ResolvedTarget,
    limits: UsagesBySymbolLimits,
    warnings: WarningCollector
  ): UsagesBySymbolReport =
    targetTerminalReport(
      request,
      prepared,
      limits,
      UsagesBySymbolState.Truncated,
      Some(target.evidence),
      warnings,
      hit = List("Deadline")
    )

  private def deadlineReportWithCoverage(
    target: ResolvedTarget,
    coverage: UsageCoverageEvidence,
    limits: UsagesBySymbolLimits,
    hit: List[String]
  ): UsagesBySymbolReport =
    UsagesBySymbolReport(
      state = UsagesBySymbolState.Truncated,
      target = Some(target.evidence),
      occurrences = Nil,
      duplicateGroups = Nil,
      coverage = coverage.copy(returnedOccurrences = 0, returnedWarnings = 0),
      limits = limitEvidence(limits, (hit :+ "Deadline").distinct),
      warnings = Nil
    )

  private final case class MappingFacts(
    mapped: Int,
    unmapped: Int,
    ambiguous: Int,
    withoutOccurrences: Int,
    synthetics: Int,
    inconsistent: Boolean,
    incomplete: Boolean
  )

  private final case class FreshnessFacts(
    fresh: Int,
    stale: Int,
    missingDigest: Int
  )

  private def mappingFactsFor(
    work: List[DocumentWork],
    prepared: PreparedRequest,
    warnings: WarningCollector,
    deadline: UsageDeadline
  ): DeadlineValue[MappingFacts] =
    if deadline.check(UsageDeadlinePhase.BeforeMappingFactAggregation) then DeadlineValue.Deadline
    else
      val selectedSourcePaths = mutable.HashSet.empty[String]
      val selectedSourcesIterator = prepared.selectedSources.iterator
      while selectedSourcesIterator.hasNext && !deadline.expired do
        if deadline.check(UsageDeadlinePhase.DuringMappingFactAggregation) then ()
        else selectedSourcePaths += selectedSourcesIterator.next().declaration.path

      val mappedSelectedCounts = mutable.HashMap.empty[String, Int]
      var mapped = 0
      var unmapped = 0
      var ambiguous = 0
      var withoutOccurrences = 0
      var synthetics = 0
      var duplicateSourceEvidence = false
      var metadataConflict = false
      var mappedOutsideSelected = false
      val workIterator = work.iterator
      while workIterator.hasNext && !deadline.expired do
        if deadline.check(UsageDeadlinePhase.DuringMappingFactAggregation) then ()
        else
          val item = workIterator.next()
          if item.document.occurrences.isEmpty then withoutOccurrences += 1
          if item.document.synthetics.nonEmpty then synthetics += 1
          item.mapping match
            case SourceMapping.Unique(source) =>
              mapped += 1
              if metadata(source.declaration) != metadata(item.artifact.declaration) then
                metadataConflict = true
              val path = source.declaration.path
              if selectedSourcePaths.contains(path) then
                if deadline.check(UsageDeadlinePhase.DuringMappedSourceAccumulation) then ()
                else if deadline.check(UsageDeadlinePhase.DuringDuplicateSourceGrouping) then ()
                else
                  val next = mappedSelectedCounts.getOrElse(path, 0) + 1
                  mappedSelectedCounts.update(path, next)
                  if next > 1 then duplicateSourceEvidence = true
              else mappedOutsideSelected = true
            case SourceMapping.Missing | SourceMapping.UnsafeUri => unmapped += 1
            case SourceMapping.Ambiguous => ambiguous += 1

      if deadline.expired then DeadlineValue.Deadline
      else
        if duplicateSourceEvidence then warnings.add("MultipleDistinctDocumentsMapToOneSource")
        if metadataConflict then warnings.add("ArtifactAndSourceMetadataConflict")

        if deadline.check(UsageDeadlinePhase.BeforeUncoveredSourceSorting) then
          DeadlineValue.Deadline
        else
          var orderedUncovered = scala.collection.immutable.TreeSet.empty[String]
          val uncoveredCandidates = prepared.selectedSources.iterator
          while uncoveredCandidates.hasNext && !deadline.expired do
            if deadline.check(UsageDeadlinePhase.DuringUncoveredSourceSorting) then ()
            else
              val path = uncoveredCandidates.next().declaration.path
              if !mappedSelectedCounts.contains(path) then
                orderedUncovered = orderedUncovered.incl(path)

          if deadline.expired || deadline.check(UsageDeadlinePhase.AfterUncoveredSourceSorting) then
            DeadlineValue.Deadline
          else
            val uncovered = orderedUncovered.iterator
            while uncovered.hasNext && !deadline.expired do
              if deadline.check(UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction) then ()
              else
                warnings.add("DeclaredSourceHasNoUniqueDocument", Some(uncovered.next()))

            if deadline.expired then DeadlineValue.Deadline
            else
              DeadlineValue.Complete(
                MappingFacts(
                  mapped = mapped,
                  unmapped = unmapped,
                  ambiguous = ambiguous,
                  withoutOccurrences = withoutOccurrences,
                  synthetics = synthetics,
                  inconsistent = duplicateSourceEvidence || metadataConflict,
                  incomplete =
                    unmapped > 0 ||
                      ambiguous > 0 ||
                      orderedUncovered.nonEmpty ||
                      mappedOutsideSelected
                )
              )

  private def freshnessFactsFor(
    work: List[DocumentWork],
    deadline: UsageDeadline
  ): DeadlineValue[FreshnessFacts] =
    var fresh = 0
    var stale = 0
    var missingDigest = 0
    val iterator = work.iterator
    while iterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringFreshnessSummaryAccumulation) then ()
      else
        iterator.next().freshness match
          case UsageFreshness.Fresh         => fresh += 1
          case UsageFreshness.Stale         => stale += 1
          case UsageFreshness.MissingDigest => missingDigest += 1
          case _                            => ()
    if deadline.expired then DeadlineValue.Deadline
    else DeadlineValue.Complete(FreshnessFacts(fresh, stale, missingDigest))

  private def scanOccurrences(
    request: UsagesBySymbolRequest,
    work: List[DocumentWork],
    target: ResolvedTarget,
    selectedSources: List[PreparedSource],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    warnings: WarningCollector
  ): ScanResult =
    val result = ListBuffer.empty[UsageOccurrenceEvidence]
    var scanned = 0
    var matching = 0
    var stop = false
    var returnLimitHit = false
    var scanLimitHit = false
    var deadlineHit = false
    var inconsistent = false
    var incomplete = false
    val selectedSourcePaths = selectedSources.map(_.declaration.path).toSet
    val orderedWork =
      if deadline.check(UsageDeadlinePhase.BeforeOccurrenceSort) then
        deadlineHit = true
        stop = true
        Nil
      else
        sortedWork(work, deadline) match
          case DeadlineValue.Deadline =>
            deadlineHit = true
            stop = true
            Nil
          case DeadlineValue.Complete(sorted) =>
            if deadline.check(UsageDeadlinePhase.AfterOccurrenceSort) then
              deadlineHit = true
              stop = true
            sorted

    orderedWork.foreach { item =>
      if !stop then
        if deadline.check(UsageDeadlinePhase.BeforeOccurrenceDocument) then
          deadlineHit = true
          stop = true
        else if target.localDocumentId.forall(_ == item.evidenceId) then
          item.document.occurrences.foreach { occurrence =>
            if !stop && ordinaryRole(occurrence).nonEmpty then
              if scanned >= limits.maxScannedOccurrences then
                scanLimitHit = true
                stop = true
              else if deadline.check(UsageDeadlinePhase.DuringOccurrenceScan) then
                deadlineHit = true
                stop = true
              else
                scanned += 1
                if occurrence.symbol == target.symbol then
                  val includedRole =
                    occurrence.role == SymbolOccurrence.Role.REFERENCE ||
                      request.selectors.includeDefinitions
                  if includedRole then
                    matching += 1
                    occurrence.range match
                      case None =>
                        incomplete = true
                        warnings.add("MatchingOccurrenceHasNoRange")
                      case Some(value) if !validRange(value) =>
                        inconsistent = true
                        warnings.add("MatchingOccurrenceHasInvalidRange")
                      case Some(value) =>
                        val mappedSource = item.mapping match
                          case SourceMapping.Unique(source) => Some(source)
                          case _                            => None
                        val sourceSelected = mappedSource.forall(source =>
                          selectedSourcePaths.contains(source.declaration.path)
                        )
                        if !sourceSelected then incomplete = true
                        else
                          val declaration = mappedSource
                            .map(_.declaration)
                            .getOrElse(item.artifact.declaration)
                          val declarationMetadata = metadata(declaration)
                          if deadline.check(UsageDeadlinePhase.BeforeOccurrenceEvidence) then
                            deadlineHit = true
                            stop = true
                          else if result.size < limits.maxReturnedOccurrences then
                            result += UsageOccurrenceEvidence(
                              stableSymbol =
                                Option.when(target.identityKind == UsageIdentityKind.Global)(target.symbol),
                              localSymbolMarker =
                                Option.when(target.identityKind == UsageIdentityKind.LocalDocumentOnly)(
                                  LocalSymbolMarker
                                ),
                              role = ordinaryRole(occurrence).get,
                              source = mappedSource.map(_.declaration.path),
                              safeUri = item.safeUri,
                              range = toRange(value),
                              module = declarationMetadata._1,
                              sourceSet = declarationMetadata._2,
                              generated = declarationMetadata._3,
                              freshness = item.freshness,
                              artifactGroupId = item.group.groupId,
                              documentIndex = item.documentIndex
                            )
                          else returnLimitHit = true
          }
    }

    val hit =
      List(
        Option.when(returnLimitHit)("ReturnedOccurrenceLimit"),
        Option.when(scanLimitHit)("ScannedOccurrenceLimit"),
        Option.when(deadlineHit)("Deadline")
      ).flatten
    val occurrences =
      if deadlineHit then result.toList
      else if deadline.check(UsageDeadlinePhase.BeforeOccurrenceSort) then
        deadlineHit = true
        result.toList
      else
        sortOccurrences(result.toList, deadline) match
          case DeadlineValue.Deadline =>
            deadlineHit = true
            result.toList
          case DeadlineValue.Complete(sorted) =>
            if deadline.check(UsageDeadlinePhase.AfterOccurrenceSort) then deadlineHit = true
            sorted
    val finalHit =
      (
        hit ++ Option.when(deadlineHit)("Deadline")
      ).distinct
    ScanResult(
      occurrences = occurrences,
      matchingOccurrences = matching,
      scannedOccurrences = scanned,
      hit = finalHit,
      inconsistent = inconsistent,
      incomplete = incomplete
    )

  private def buildDocumentWork(
    documents: List[(ParsedGroup, (TextDocument, Int))],
    prepared: PreparedRequest,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    warnings: WarningCollector,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, DocumentBuildResult] =
    val result = ListBuffer.empty[DocumentWork]
    var index = 0
    var deadlineHit = false
    while index < documents.size && !deadlineHit do
      if deadline.check(UsageDeadlinePhase.BeforeDocument) then deadlineHit = true
      else
        val (parsed, (document, documentIndex)) = documents(index)
        if deadline.check(UsageDeadlinePhase.BeforeDocumentMapping) then deadlineHit = true
        else
          val safeUri = normalizeSafeUri(document.uri, limits.maxUriBytes)
          val mapping = safeUri match
            case Some(uri) => sourceMapping(uri, prepared.sources)
            case None      => SourceMapping.UnsafeUri
          if deadline.check(UsageDeadlinePhase.AfterDocumentMapping) then deadlineHit = true
          else
            if safeUri.isEmpty then warnings.add("UnsafeOrOverlongDocumentUri")
            mapping match
              case SourceMapping.Missing   => warnings.add("DocumentSourceMappingMissing", safeUri)
              case SourceMapping.Ambiguous => warnings.add("DocumentSourceMappingAmbiguous", safeUri)
              case _                       => ()
            freshness(document, mapping, deadline, boundedOperations) match
              case Left(value) => return Left(value)
              case Right(FreshnessResult.Deadline) => deadlineHit = true
              case Right(FreshnessResult.Complete(value)) =>
                if value == UsageFreshness.Stale then
                  warnings.add("SourceDigestStale", mappingSubject(mapping))
                else if value == UsageFreshness.MissingDigest then
                  warnings.add("SourceDigestMissing", mappingSubject(mapping))
                if document.occurrences.isEmpty then
                  warnings.add("DocumentOccurrenceDataAbsent", safeUri)
                if document.synthetics.nonEmpty then warnings.add("SyntheticsExcluded", safeUri)
                result += DocumentWork(
                  group = parsed.group,
                  artifact = parsed.group.representative.prepared,
                  document = document,
                  documentIndex = documentIndex,
                  safeUri = safeUri,
                  mapping = mapping,
                  freshness = value
                )
      index += 1
    Right(DocumentBuildResult(result.toList, deadlineHit))

  private def resolvePointTarget(
    point: UsagesBySymbolTarget.Point,
    prepared: PreparedRequest,
    parsedGroups: List[ParsedGroup],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, PointResolution] =
    (prepared.targetSource, prepared.targetArtifact) match
      case (Some(source), Some(artifact)) =>
        resolvePreparedPointTarget(
          point,
          prepared,
          source,
          artifact,
          parsedGroups,
          limits,
          deadline,
          boundedOperations
        )
      case _ =>
        Left(
          failure(
            UsagesBySymbolFailureKind.InternalInvariant,
            "Point target source or artifact was not prepared"
          )
        )

  private def resolvePreparedPointTarget(
    point: UsagesBySymbolTarget.Point,
    prepared: PreparedRequest,
    source: PreparedSource,
    artifact: PreparedArtifact,
    parsedGroups: List[ParsedGroup],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, PointResolution] =
    pointPosition(
      source,
      point.line,
      point.column,
      limits,
      deadline,
      boundedOperations
    ).flatMap { position =>
      var targetGroup = Option.empty[ParsedGroup]
      var groupIndex = 0
      while groupIndex < parsedGroups.size && targetGroup.isEmpty && !deadline.expired do
        if deadline.check(UsageDeadlinePhase.DuringPointTargetGroupSelection) then ()
        else
          val candidate = parsedGroups(groupIndex)
          if candidate.group.members.exists(
              _.prepared.declaration.path == artifact.declaration.path
            )
          then targetGroup = Some(candidate)
        groupIndex += 1
      if deadline.expired then Left(timeoutBeforeTargetResolution)
      else targetGroup match
        case None =>
          Right(PointResolution.Unresolved)
        case Some(parsed) =>
          var scanned = 0
          var limitHit = false
          var deadlineHit = false
          val candidates = ListBuffer.empty[(String, Int, SemanticRange)]
          if deadline.check(UsageDeadlinePhase.BeforePointOccurrenceScan) then
            deadlineHit = true
          parsed.documents.zipWithIndex.foreach { case (document, index) =>
            if !deadlineHit && !limitHit then
              val mapping = normalizeSafeUri(document.uri, limits.maxUriBytes)
                .map(uri => sourceMapping(uri, prepared.sources))
                .getOrElse(SourceMapping.UnsafeUri)
              val intended = mapping match
                case SourceMapping.Unique(value) =>
                  value.declaration.path == source.declaration.path
                case _ => false
              if intended then
                document.occurrences.foreach { occurrence =>
                  if !limitHit && !deadlineHit && ordinaryRole(occurrence).nonEmpty then
                    if scanned >= limits.maxScannedOccurrences then limitHit = true
                    else if deadline.check(UsageDeadlinePhase.DuringPointOccurrenceScan) then
                      deadlineHit = true
                    else
                      scanned += 1
                      occurrence.range.filter(validRange).foreach { range =>
                        if containsPoint(range, position._1, position._2) &&
                            validSymbol(occurrence.symbol, limits.maxStringBytes)
                        then candidates += ((occurrence.symbol, index, toRange(range)))
                      }
                }
          }
          if deadlineHit then
            Left(timeoutBeforeTargetResolution)
          else if limitHit then Right(PointResolution.ScanLimit)
          else if deadline.check(UsageDeadlinePhase.DuringPointOccurrenceScan) then
            Left(timeoutBeforeTargetResolution)
          else
            val symbols = candidates.map(_._1).distinct.sorted
            if symbols.isEmpty then Right(PointResolution.Unresolved)
            else if symbols.size > 1 then Right(PointResolution.Ambiguous)
            else
              val symbol = symbols.head
              val matching = candidates.filter(_._1 == symbol).toList
              val identity =
                if localSymbol(symbol) then UsageIdentityKind.LocalDocumentOnly
                else UsageIdentityKind.Global
              val documentIndexes = matching.map(_._2).distinct
              if identity == UsageIdentityKind.LocalDocumentOnly && documentIndexes.size != 1 then
                Right(PointResolution.Ambiguous)
              else
                val selected = matching.sortBy { case (_, index, range) =>
                  (
                    range.endLine - range.startLine,
                    range.endCharacter - range.startCharacter,
                    index,
                    range.startLine,
                    range.startCharacter
                  )
                }.head
                val evidenceId = s"${parsed.group.groupId}#document-${selected._2}"
                val resolved = ResolvedTarget(
                  symbol = symbol,
                  identityKind = identity,
                  evidence = UsageTargetEvidence(
                    mode = UsageTargetMode.PointSelected,
                    identityKind = identity,
                    stableSymbol = Option.when(identity == UsageIdentityKind.Global)(symbol),
                    localSymbolMarker =
                      Option.when(identity == UsageIdentityKind.LocalDocumentOnly)(LocalSymbolMarker),
                    source = Some(source.declaration.path),
                    range = Some(selected._3),
                    documentEvidenceId = Some(evidenceId)
                  ),
                  localDocumentId =
                    Option.when(identity == UsageIdentityKind.LocalDocumentOnly)(evidenceId)
                )
                deadline.check(UsageDeadlinePhase.AfterPointTargetResolution)
                Right(PointResolution.Resolved(resolved))
    }

  private def readRawArtifacts(
    artifacts: List[PreparedArtifact],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    targetAlreadyResolved: Boolean,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, (List[RawArtifact], Boolean)] =
    val result = ListBuffer.empty[RawArtifact]
    var deadlineHit = false
    var index = 0
    while index < artifacts.size && !deadlineHit do
      val artifact = artifacts(index)
      observeBoundedOperation(
        deadline,
        UsageDeadlinePhase.BeforeArtifactRead,
        UsageDeadlinePhase.AfterArtifactRead,
        UsageDeadlinePhase.AfterArtifactReadExceptional
      )(
        boundedOperations.readAllBytes(artifact.path),
        failure(
          UsagesBySymbolFailureKind.IoFailure,
          s"Unable to read declared artifact: ${artifact.declaration.path}"
        )
      ) match
        case BoundedOperationResult.Deadline => deadlineHit = true
        case BoundedOperationResult.Failed(value) => return Left(value)
        case BoundedOperationResult.Complete(bytes) =>
          if bytes.length.toLong != artifact.sizeBytes then
            return Left(
              failure(
                UsagesBySymbolFailureKind.UnsafeFilesystem,
                s"Declared artifact changed while being read: ${artifact.declaration.path}"
              )
            )
          else if deadline.check(UsageDeadlinePhase.BeforeArtifactHash) then deadlineHit = true
          else
            val hash = sha256(bytes)
            if deadline.check(UsageDeadlinePhase.AfterArtifactHash) then deadlineHit = true
            else result += RawArtifact(artifact, bytes, hash)
      index += 1
    if deadlineHit && !targetAlreadyResolved then
      Left(
        failure(
          UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution,
          "Deadline expired before point target resolution"
        )
      )
    else Right(result.toList, deadlineHit)

  private def parseGroups(
    groups: List[ArtifactGroup],
    targetPath: Option[String],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    targetAlreadyResolved: Boolean,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, (List[ParsedGroup], Boolean)] =
    val sorted = groups.sortBy(group =>
      (
        if targetPath.exists(path => group.members.exists(_.prepared.declaration.path == path)) then 0 else 1,
        group.groupId
      )
    )
    val result = ListBuffer.empty[ParsedGroup]
    var deadlineHit = false
    var index = 0
    while index < sorted.size && !deadlineHit do
      val group = sorted(index)
      observeBoundedOperation(
        deadline,
        UsageDeadlinePhase.BeforeGroupParse,
        UsageDeadlinePhase.AfterGroupParse,
        UsageDeadlinePhase.AfterGroupParseExceptional
      )(
        boundedOperations.parseTextDocuments(group.representative.bytes),
        failure(
          UsagesBySymbolFailureKind.ParseFailure,
          s"Malformed or unsupported SemanticDB artifact: ${group.representative.prepared.declaration.path}"
        )
      ) match
        case BoundedOperationResult.Deadline => deadlineHit = true
        case BoundedOperationResult.Failed(value) => return Left(value)
        case BoundedOperationResult.Complete(parsed) =>
          result += ParsedGroup(group, parsed.documents.toList)
      index += 1
    val targetParsed = targetPath.forall(path =>
      result.exists(_.group.members.exists(_.prepared.declaration.path == path))
    )
    if (!targetAlreadyResolved && !targetParsed) || (deadlineHit && !targetAlreadyResolved && !targetParsed) then
      Left(
        failure(
          UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution,
          "Deadline expired before point target resolution"
        )
      )
    else Right(result.toList, deadlineHit)

  private def prepare(
    request: UsagesBySymbolRequest,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, PreparationResult] =
    validateWorkspace(request.workspace, deadline, boundedOperations).flatMap {
      case DeadlineValue.Deadline => Right(PreparationResult.Deadline)
      case DeadlineValue.Complete(workspace) =>
        if request.sources.size > limits.maxSources then
          Left(invalid(s"Declared source count exceeds ${limits.maxSources}"))
        else if request.artifacts.isEmpty then
          Left(invalid("At least one declared SemanticDB artifact is required"))
        else if request.artifacts.size > limits.maxArtifacts then
          Left(invalid(s"Declared artifact count exceeds ${limits.maxArtifacts}"))
        else if deadline.check(UsageDeadlinePhase.BeforeSelectorValidation) then
          Right(PreparationResult.Deadline)
        else
          validateSelectors(request.selectors, limits).flatMap { _ =>
            if deadline.check(UsageDeadlinePhase.AfterSelectorValidation) then
              Right(PreparationResult.Deadline)
            else
              deduplicateSources(request.sources, deadline).flatMap {
                case DeadlineValue.Deadline => Right(PreparationResult.Deadline)
                case DeadlineValue.Complete(sources) =>
                  deduplicateArtifacts(request.artifacts, deadline).flatMap {
                    case DeadlineValue.Deadline => Right(PreparationResult.Deadline)
                    case DeadlineValue.Complete(artifacts) =>
                      prepareSources(
                        workspace,
                        sources,
                        limits,
                        deadline,
                        boundedOperations
                      ).flatMap {
                        case DeadlineValue.Deadline => Right(PreparationResult.Deadline)
                        case DeadlineValue.Complete(preparedSources) =>
                          prepareArtifacts(
                            workspace,
                            artifacts,
                            limits,
                            deadline,
                            boundedOperations
                          ).flatMap {
                            case DeadlineValue.Deadline => Right(PreparationResult.Deadline)
                            case DeadlineValue.Complete(preparedArtifacts) =>
                              finishPreparation(
                                request,
                                workspace,
                                preparedSources,
                                preparedArtifacts,
                                limits,
                                deadline
                              )
                          }
                      }
                  }
              }
          }
    }

  private def finishPreparation(
    request: UsagesBySymbolRequest,
    workspace: Path,
    preparedSources: List[PreparedSource],
    preparedArtifacts: List[PreparedArtifact],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline
  ): Either[UsagesBySymbolFailure, PreparationResult] =
    if deadline.check(UsageDeadlinePhase.BeforeTargetInventoryValidation) then
      Right(PreparationResult.Deadline)
    else
      validateTarget(request.target, preparedSources, preparedArtifacts, limits).flatMap {
        case (targetSource, targetArtifact) =>
          if deadline.check(UsageDeadlinePhase.AfterTargetInventoryValidation) then
            Right(PreparationResult.Deadline)
          else
            val selectedSources = ListBuffer.empty[PreparedSource]
            var sourceIndex = 0
            while sourceIndex < preparedSources.size && !deadline.expired do
              if deadline.check(UsageDeadlinePhase.DuringSelection) then ()
              else if selected(preparedSources(sourceIndex).declaration, request.selectors) then
                selectedSources += preparedSources(sourceIndex)
              sourceIndex += 1
            val selectedArtifacts = ListBuffer.empty[PreparedArtifact]
            var artifactIndex = 0
            var rawArtifactBytes = 0L
            while artifactIndex < preparedArtifacts.size && !deadline.expired do
              if deadline.check(UsageDeadlinePhase.DuringSelection) then ()
              else
                rawArtifactBytes = saturatingAdd(
                  rawArtifactBytes,
                  preparedArtifacts(artifactIndex).sizeBytes
                )
                if selected(preparedArtifacts(artifactIndex).declaration, request.selectors) then
                  selectedArtifacts += preparedArtifacts(artifactIndex)
              artifactIndex += 1
            if deadline.expired || deadline.check(UsageDeadlinePhase.AfterPreparation) then
              Right(PreparationResult.Deadline)
            else
              Right(
                PreparationResult.Complete(
                  PreparedRequest(
                    workspace = workspace,
                    sources = preparedSources,
                    artifacts = preparedArtifacts,
                    selectedSources = selectedSources.toList,
                    selectedArtifacts = selectedArtifacts.toList,
                    targetArtifact = targetArtifact,
                    targetSource = targetSource,
                    scopeExcluded =
                      selectedSources.size != sourceIndex ||
                        selectedArtifacts.size != artifactIndex,
                    declaredSourceCount = sourceIndex,
                    selectedSourceCount = selectedSources.size,
                    declaredArtifactCount = artifactIndex,
                    selectedArtifactCount = selectedArtifacts.size,
                    rawArtifactBytes = rawArtifactBytes
                  )
                )
              )
      }

  private def prepareSources(
    workspace: Path,
    values: List[DeclaredUsageSource],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, DeadlineValue[List[PreparedSource]]] =
    val result = ListBuffer.empty[PreparedSource]
    var index = 0
    while index < values.size && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.BeforeSourcePreparation) then ()
      else
        val declaration = values(index)
        validateDeclarationMetadata(
          declaration.path,
          declaration.module,
          declaration.sourceSet,
          limits
        ) match
          case Left(value) => return Left(value)
          case Right(_) =>
            if !declaration.path.endsWith(".scala") && !declaration.path.endsWith(".java") then
              return Left(invalid(s"Declared source has unsupported extension: ${declaration.path}"))
            resolveDeclaredFile(
              workspace,
              declaration.path,
              limits,
              deadline,
              UsageDeadlinePhase.BeforeSourcePathValidation,
              UsageDeadlinePhase.DuringSourcePathValidation,
              UsageDeadlinePhase.AfterSourcePathValidationExceptional,
              boundedOperations
            ) match
              case Left(value) => return Left(value)
              case Right(DeadlineValue.Deadline) => ()
              case Right(DeadlineValue.Complete(path)) =>
                safeSize(
                  path,
                  declaration.path,
                  deadline,
                  UsageDeadlinePhase.BeforeSourceSizeRead,
                  UsageDeadlinePhase.AfterSourceSizeRead,
                  UsageDeadlinePhase.AfterSourceSizeReadExceptional,
                  boundedOperations
                ) match
                  case Left(value) => return Left(value)
                  case Right(DeadlineValue.Deadline) => ()
                  case Right(DeadlineValue.Complete(size)) =>
                    if size > limits.maxSourceBytes then
                      return Left(
                        invalid(
                          s"Declared source exceeds ${limits.maxSourceBytes} bytes: ${declaration.path}"
                        )
                      )
                    result += PreparedSource(declaration, path, size)
                    deadline.check(UsageDeadlinePhase.AfterSourcePreparation)
      index += 1
    if deadline.expired then Right(DeadlineValue.Deadline)
    else Right(DeadlineValue.Complete(result.toList.sortBy(_.declaration.path)))

  private def prepareArtifacts(
    workspace: Path,
    values: List[DeclaredUsageArtifact],
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, DeadlineValue[List[PreparedArtifact]]] =
    val result = ListBuffer.empty[PreparedArtifact]
    var aggregate = 0L
    var index = 0
    while index < values.size && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.BeforeArtifactPreparation) then ()
      else
        val declaration = values(index)
        validateDeclarationMetadata(
          declaration.path,
          declaration.module,
          declaration.sourceSet,
          limits
        ) match
          case Left(value) => return Left(value)
          case Right(_) =>
            if !declaration.path.endsWith(".semanticdb") then
              return Left(invalid(s"Declared artifact must use .semanticdb: ${declaration.path}"))
            resolveDeclaredFile(
              workspace,
              declaration.path,
              limits,
              deadline,
              UsageDeadlinePhase.BeforeArtifactPathValidation,
              UsageDeadlinePhase.DuringArtifactPathValidation,
              UsageDeadlinePhase.AfterArtifactPathValidationExceptional,
              boundedOperations
            ) match
              case Left(value) => return Left(value)
              case Right(DeadlineValue.Deadline) => ()
              case Right(DeadlineValue.Complete(path)) =>
                safeSize(
                  path,
                  declaration.path,
                  deadline,
                  UsageDeadlinePhase.BeforeArtifactSizeRead,
                  UsageDeadlinePhase.AfterArtifactSizeRead,
                  UsageDeadlinePhase.AfterArtifactSizeReadExceptional,
                  boundedOperations
                ) match
                  case Left(value) => return Left(value)
                  case Right(DeadlineValue.Deadline) => ()
                  case Right(DeadlineValue.Complete(size)) =>
                    val nextAggregate = saturatingAdd(aggregate, size)
                    if size > limits.maxArtifactBytes then
                      return Left(
                        invalid(
                          s"Declared artifact exceeds ${limits.maxArtifactBytes} bytes: ${declaration.path}"
                        )
                      )
                    else if nextAggregate > limits.maxAggregateArtifactBytes then
                      return Left(
                        invalid(
                          s"Aggregate artifact bytes exceed ${limits.maxAggregateArtifactBytes}"
                        )
                      )
                    result += PreparedArtifact(declaration, path, size)
                    aggregate = nextAggregate
                    deadline.check(UsageDeadlinePhase.AfterArtifactPreparation)
      index += 1
    if deadline.expired then Right(DeadlineValue.Deadline)
    else
      Right(
        DeadlineValue.Complete(
          result.toList.sortBy(value =>
            (
              value.declaration.path,
              value.declaration.module,
              value.declaration.sourceSet,
              value.declaration.generated
            )
          )
        )
      )

  private def validateTarget(
    target: UsagesBySymbolTarget,
    sources: List[PreparedSource],
    artifacts: List[PreparedArtifact],
    limits: UsagesBySymbolLimits
  ): Either[UsagesBySymbolFailure, (Option[PreparedSource], Option[PreparedArtifact])] =
    target match
      case UsagesBySymbolTarget.ExplicitGlobal(symbol) =>
        if !validSymbol(symbol, limits.maxStringBytes) then
          Left(invalid("Explicit symbol must be non-empty and within the symbol byte limit"))
        else Right(None -> None)
      case point: UsagesBySymbolTarget.Point =>
        if point.line <= 0 || point.column <= 0 then
          Left(invalid("Point line and column must be positive one-based values"))
        else
          val source = sources.find(_.declaration.path == point.source)
          val artifact = artifacts.find(_.declaration.path == point.semanticdb)
          if source.isEmpty then Left(invalid("Point source must be present in the declared inventory"))
          else if artifact.isEmpty then
            Left(invalid("Point SemanticDB artifact must be present in the declared inventory"))
          else Right(source -> artifact)

  private def validateWorkspace(
    workspace: Path,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, DeadlineValue[Path]] =
    if deadline.check(UsageDeadlinePhase.BeforeWorkspaceValidation) then
      Right(DeadlineValue.Deadline)
    else
      try
        val normalized = workspace.toAbsolutePath.normalize()
        if Files.isSymbolicLink(normalized) then
          Left(unsafe("Workspace symbolic links are not permitted"))
        else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
          Left(unsafe("Workspace does not exist"))
        else if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then
          Left(unsafe("Workspace is not a directory"))
        else
          observeBoundedOperation(
            deadline,
            UsageDeadlinePhase.BeforeWorkspaceRealPath,
            UsageDeadlinePhase.AfterWorkspaceValidation,
            UsageDeadlinePhase.AfterWorkspaceValidationExceptional
          )(
            boundedOperations.realPath(normalized),
            unsafe("Unable to validate workspace")
          ) match
            case BoundedOperationResult.Deadline => Right(DeadlineValue.Deadline)
            case BoundedOperationResult.Failed(value) => Left(value)
            case BoundedOperationResult.Complete(real) =>
              if real != normalized then
                Left(unsafe("Workspace path contains a symbolic-link component"))
              else Right(DeadlineValue.Complete(normalized))
      catch
        case NonFatal(_) =>
          if deadline.check(UsageDeadlinePhase.AfterWorkspaceValidationExceptional) then
            Right(DeadlineValue.Deadline)
          else Left(unsafe("Unable to validate workspace"))

  private def resolveDeclaredFile(
    workspace: Path,
    relative: String,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    beforePhase: UsageDeadlinePhase,
    duringPhase: UsageDeadlinePhase,
    exceptionalPhase: UsageDeadlinePhase,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, DeadlineValue[Path]] =
    if deadline.check(beforePhase) then Right(DeadlineValue.Deadline)
    else
      validateRelativePath(relative, limits).flatMap { normalizedRelative =>
        val segments = normalizedRelative.split('/').toList
        var current = workspace
        var index = 0
        var symbolicComponent = false
        while index < segments.size && !symbolicComponent && !deadline.expired do
          if deadline.check(duringPhase) then ()
          else
            current = current.resolve(segments(index))
            symbolicComponent = Files.isSymbolicLink(current)
          index += 1
        if deadline.expired then Right(DeadlineValue.Deadline)
        else if symbolicComponent then
          Left(unsafe(s"Declared path contains a symbolic-link component: $relative"))
        else
          try
            val normalized = current.toAbsolutePath.normalize()
            if !normalized.startsWith(workspace) then
              Left(unsafe(s"Declared path escapes workspace: $relative"))
            else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
              Left(unsafe(s"Declared file does not exist: $relative"))
            else if !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) then
              Left(unsafe(s"Declared path is not a regular file: $relative"))
            else
              observeBoundedOperation(
                deadline,
                UsageDeadlinePhase.BeforeDeclaredRealPath,
                UsageDeadlinePhase.AfterDeclaredRealPath,
                exceptionalPhase
              )(
                boundedOperations.realPath(normalized),
                unsafe(s"Unable to validate declared file: $relative")
              ) match
                case BoundedOperationResult.Deadline => Right(DeadlineValue.Deadline)
                case BoundedOperationResult.Failed(value) => Left(value)
                case BoundedOperationResult.Complete(real) =>
                  if !real.startsWith(workspace) then
                    Left(unsafe(s"Declared path escapes workspace: $relative"))
                  else Right(DeadlineValue.Complete(normalized))
          catch
            case NonFatal(_) =>
              if deadline.check(exceptionalPhase) then Right(DeadlineValue.Deadline)
              else Left(unsafe(s"Unable to validate declared file: $relative"))
      }

  private def validateRelativePath(
    value: String,
    limits: UsagesBySymbolLimits
  ): Either[UsagesBySymbolFailure, String] =
    if value.isEmpty then Left(invalid("Declared path must be non-empty"))
    else if utf8Bytes(value) > limits.maxPathBytes then Left(invalid("Declared path exceeds byte limit"))
    else
      try
        val path = Path.of(value)
        val normalized = path.normalize().toString.replace('\\', '/')
        if path.isAbsolute then Left(invalid("Declared paths must be workspace-relative"))
        else if value.contains('\\') then Left(invalid("Declared paths must use normalized '/' separators"))
        else if normalized != value || value.startsWith("./") || value.split('/').exists(_.isEmpty) then
          Left(invalid(s"Declared path is not normalized: $value"))
        else if value.split('/').contains("..") then Left(invalid("Declared path traversal is not permitted"))
        else Right(normalized)
      catch
        case NonFatal(_) => Left(invalid("Declared path is invalid"))

  private def validateDeclarationMetadata(
    path: String,
    module: String,
    sourceSet: String,
    limits: UsagesBySymbolLimits
  ): Either[UsagesBySymbolFailure, Unit] =
    if path.isEmpty then Left(invalid("Declared path must be non-empty"))
    else if !validIdentifier(module, limits.maxStringBytes) then
      Left(invalid(s"Module identifier is invalid for declared path: $path"))
    else if !validIdentifier(sourceSet, limits.maxStringBytes) then
      Left(invalid(s"Source-set identifier is invalid for declared path: $path"))
    else Right(())

  private def validateSelectors(
    selectors: UsagesBySymbolSelectors,
    limits: UsagesBySymbolLimits
  ): Either[UsagesBySymbolFailure, Unit] =
    if selectors.modules.size > limits.maxSelectorValues ||
        selectors.sourceSets.size > limits.maxSelectorValues
    then Left(invalid(s"Selector values exceed ${limits.maxSelectorValues}"))
    else if !(selectors.modules ++ selectors.sourceSets).forall(validIdentifier(_, limits.maxStringBytes)) then
      Left(invalid("Selector identifiers must be non-empty and within the identifier byte limit"))
    else Right(())

  private def deduplicateSources(
    values: List[DeclaredUsageSource],
    deadline: UsageDeadline
  ): Either[UsagesBySymbolFailure, DeadlineValue[List[DeclaredUsageSource]]] =
    deduplicateDeclarations(
      values,
      _.path,
      value => (value.module, value.sourceSet, value.generated),
      "source",
      deadline,
      UsageDeadlinePhase.BeforeSourceDeduplication,
      UsageDeadlinePhase.DuringSourceDeduplication,
      UsageDeadlinePhase.AfterSourceDeduplication
    )

  private def deduplicateArtifacts(
    values: List[DeclaredUsageArtifact],
    deadline: UsageDeadline
  ): Either[UsagesBySymbolFailure, DeadlineValue[List[DeclaredUsageArtifact]]] =
    deduplicateDeclarations(
      values,
      _.path,
      value => (value.module, value.sourceSet, value.generated),
      "artifact",
      deadline,
      UsageDeadlinePhase.BeforeArtifactDeduplication,
      UsageDeadlinePhase.DuringArtifactDeduplication,
      UsageDeadlinePhase.AfterArtifactDeduplication
    )

  private def deduplicateDeclarations[A](
    values: List[A],
    path: A => String,
    metadata: A => (String, String, Boolean),
    label: String,
    deadline: UsageDeadline,
    beforePhase: UsageDeadlinePhase,
    duringPhase: UsageDeadlinePhase,
    afterPhase: UsageDeadlinePhase
  ): Either[UsagesBySymbolFailure, DeadlineValue[List[A]]] =
    if deadline.check(beforePhase) then Right(DeadlineValue.Deadline)
    else
      val grouped = mutable.LinkedHashMap.empty[String, ListBuffer[A]]
      var index = 0
      while index < values.size && !deadline.expired do
        if deadline.check(duringPhase) then ()
        else grouped.getOrElseUpdate(path(values(index)), ListBuffer.empty) += values(index)
        index += 1
      if deadline.expired then Right(DeadlineValue.Deadline)
      else
        val ordered = grouped.toList.sortBy(_._1)
        var conflict = Option.empty[String]
        var groupIndex = 0
        while groupIndex < ordered.size && conflict.isEmpty && !deadline.expired do
          if deadline.check(duringPhase) then ()
          else
            val (relative, declarations) = ordered(groupIndex)
            if declarations.map(metadata).distinct.size > 1 then conflict = Some(relative)
          groupIndex += 1
        conflict match
          case Some(relative) =>
            Left(invalid(s"Duplicate $label declarations have conflicting metadata: $relative"))
          case None if deadline.expired || deadline.check(afterPhase) =>
            Right(DeadlineValue.Deadline)
          case None =>
            Right(DeadlineValue.Complete(ordered.map(_._2.head)))

  private def artifactGroups(
    raw: List[RawArtifact],
    deadline: UsageDeadline,
    targetAlreadyResolved: Boolean
  ): Either[UsagesBySymbolFailure, (List[ArtifactGroup], Boolean)] =
    if deadline.check(UsageDeadlinePhase.BeforeArtifactGrouping) then
      if targetAlreadyResolved then Right(Nil -> true)
      else Left(timeoutBeforeTargetResolution)
    else
      val grouped = raw.groupBy(_.hash).toList.sortBy(_._1)
      val result = ListBuffer.empty[ArtifactGroup]
      var index = 0
      var deadlineHit = false
      while index < grouped.size && !deadlineHit do
        if deadline.check(UsageDeadlinePhase.DuringArtifactGrouping) then deadlineHit = true
        else
          val (hash, members) = grouped(index)
          val sorted = members.sortBy(member =>
            (
              member.prepared.declaration.path,
              member.prepared.declaration.module,
              member.prepared.declaration.sourceSet,
              member.prepared.declaration.generated
            )
          )
          result += ArtifactGroup(
            groupId = s"sha256:$hash",
            members = sorted,
            representative = sorted.head,
            metadataConflict =
              sorted.map(member => metadata(member.prepared.declaration)).distinct.size > 1
          )
        index += 1
      if !deadlineHit && deadline.check(UsageDeadlinePhase.AfterArtifactGrouping) then
        deadlineHit = true
      if deadlineHit && !targetAlreadyResolved then Left(timeoutBeforeTargetResolution)
      else Right(result.toList -> deadlineHit)

  private def duplicateEvidence(
    group: ArtifactGroup,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline
  ): DeadlineValue[UsageDuplicateGroupEvidence] =
    val samples = ListBuffer.empty[String]
    var copyCount = 0
    val iterator = group.members.iterator
    while iterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringDuplicateEvidence) then ()
      else
        val member = iterator.next()
        if samples.size < limits.maxDuplicatePathSamples then
          samples += member.prepared.declaration.path
        copyCount += 1
    if deadline.expired then DeadlineValue.Deadline
    else
      DeadlineValue.Complete(
        UsageDuplicateGroupEvidence(
          groupId = group.groupId,
          sizeBytes = group.representative.prepared.sizeBytes,
          copyCount = copyCount,
          representative = group.representative.prepared.declaration.path,
          samplePaths = samples.toList,
          pathsTruncated = samples.size < copyCount
        )
      )

  private def sourceMapping(
    uri: String,
    sources: List[PreparedSource]
  ): SourceMapping =
    if uri.split('/').count(_.nonEmpty) < 2 then SourceMapping.Missing
    else
      val exact = sources.filter(_.declaration.path == uri)
      val candidates =
        if exact.nonEmpty then exact
        else sources.filter(source => source.declaration.path.endsWith(s"/$uri"))
      candidates match
        case value :: Nil => SourceMapping.Unique(value)
        case Nil          => SourceMapping.Missing
        case _            => SourceMapping.Ambiguous

  private def freshness(
    document: TextDocument,
    mapping: SourceMapping,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, FreshnessResult] =
    mapping match
      case SourceMapping.Unique(source) =>
        if !usableMd5(document.md5) then
          if deadline.check(UsageDeadlinePhase.AfterFreshnessDigest) then
            Right(FreshnessResult.Deadline)
          else Right(FreshnessResult.Complete(UsageFreshness.MissingDigest))
        else
          observeBoundedOperation(
            deadline,
            UsageDeadlinePhase.BeforeFreshnessRead,
            UsageDeadlinePhase.AfterFreshnessRead,
            UsageDeadlinePhase.AfterFreshnessReadExceptional
          )(
            boundedOperations.readAllBytes(source.path),
            failure(
              UsagesBySymbolFailureKind.IoFailure,
              s"Unable to read declared source: ${source.declaration.path}"
            )
          ) match
            case BoundedOperationResult.Deadline => Right(FreshnessResult.Deadline)
            case BoundedOperationResult.Failed(value) => Left(value)
            case BoundedOperationResult.Complete(bytes) =>
              if bytes.length.toLong != source.sizeBytes then
                Left(unsafe(s"Declared source changed while being read: ${source.declaration.path}"))
              else if deadline.check(UsageDeadlinePhase.BeforeFreshnessDigest) then
                Right(FreshnessResult.Deadline)
              else
                val value =
                  if md5(bytes).equalsIgnoreCase(document.md5) then UsageFreshness.Fresh
                  else UsageFreshness.Stale
                if deadline.check(UsageDeadlinePhase.AfterFreshnessDigest) then
                  Right(FreshnessResult.Deadline)
                else Right(FreshnessResult.Complete(value))
      case SourceMapping.Ambiguous =>
        if deadline.check(UsageDeadlinePhase.AfterFreshnessDigest) then
          Right(FreshnessResult.Deadline)
        else Right(FreshnessResult.Complete(UsageFreshness.AmbiguousMapping))
      case _ =>
        if deadline.check(UsageDeadlinePhase.AfterFreshnessDigest) then
          Right(FreshnessResult.Deadline)
        else Right(FreshnessResult.Complete(UsageFreshness.Unmapped))

  private def pointPosition(
    source: PreparedSource,
    line: Int,
    column: Int,
    limits: UsagesBySymbolLimits,
    deadline: UsageDeadline,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, (Int, Int)] =
    observeBoundedOperation(
      deadline,
      UsageDeadlinePhase.BeforePointSourceRead,
      UsageDeadlinePhase.AfterPointSourceRead,
      UsageDeadlinePhase.AfterPointSourceReadExceptional
    )(
      boundedOperations.readAllBytes(source.path),
      failure(
        UsagesBySymbolFailureKind.IoFailure,
        s"Unable to read point source: ${source.declaration.path}"
      )
    ) match
      case BoundedOperationResult.Deadline => Left(timeoutBeforeTargetResolution)
      case BoundedOperationResult.Failed(value) => Left(value)
      case BoundedOperationResult.Complete(bytes) =>
        if bytes.length.toLong != source.sizeBytes || bytes.length.toLong > limits.maxSourceBytes then
          Left(unsafe(s"Declared source changed while being read: ${source.declaration.path}"))
        else if deadline.check(UsageDeadlinePhase.BeforePointDecode) then
          Left(timeoutBeforeTargetResolution)
        else
          strictUtf8(bytes) match
            case Left(value) => Left(value)
            case Right(text) =>
              if deadline.check(UsageDeadlinePhase.AfterPointDecode) then
                Left(timeoutBeforeTargetResolution)
              else if deadline.check(UsageDeadlinePhase.BeforePointCoordinateConversion) then
                Left(timeoutBeforeTargetResolution)
              else
                val lines = text.split("\\r?\\n", -1).toList
                val result =
                  if line > lines.size then Left(invalid("Point line is outside the declared source"))
                  else
                    val current = lines(line - 1)
                    if column - 1 > current.length then
                      Left(invalid("Point column is outside the declared source"))
                    else Right((line - 1, column - 1))
                result.flatMap { value =>
                  if deadline.check(UsageDeadlinePhase.AfterPointCoordinateConversion) then
                    Left(timeoutBeforeTargetResolution)
                  else Right(value)
                }

  private def strictUtf8(bytes: Array[Byte]): Either[UsagesBySymbolFailure, String] =
    try
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch
      case NonFatal(_) => Left(invalid("Declared point source is not strict UTF-8"))

  private def targetTerminalReport(
    request: UsagesBySymbolRequest,
    prepared: PreparedRequest,
    limits: UsagesBySymbolLimits,
    state: UsagesBySymbolState,
    target: Option[UsageTargetEvidence],
    warnings: WarningCollector,
    hit: List[String] = Nil
  ): UsagesBySymbolReport =
    UsagesBySymbolReport(
      state = state,
      target = target,
      occurrences = Nil,
      duplicateGroups = Nil,
      coverage = UsageCoverageEvidence(
        inventoryClosed = request.inventoryClosed,
        declaredSources = prepared.declaredSourceCount,
        selectedSources = prepared.selectedSourceCount,
        excludedSources = prepared.declaredSourceCount - prepared.selectedSourceCount,
        declaredArtifacts = prepared.declaredArtifactCount,
        selectedArtifacts = prepared.selectedArtifactCount,
        excludedArtifacts = prepared.declaredArtifactCount - prepared.selectedArtifactCount,
        rawArtifactBytes = prepared.rawArtifactBytes,
        uniqueArtifactContents = 0,
        duplicateCopies = 0,
        parsedDocuments = 0,
        mappedDocuments = 0,
        unmappedDocuments = 0,
        ambiguousDocuments = 0,
        freshDocuments = 0,
        staleDocuments = 0,
        missingDigestDocuments = 0,
        documentsWithoutOccurrences = 0,
        documentsWithSynthetics = 0,
        scannedOrdinaryOccurrences = 0,
        matchingOccurrences = 0,
        returnedOccurrences = 0,
        totalWarnings = warnings.total,
        returnedWarnings = warnings.retained.size
      ),
      limits = limitEvidence(limits, hit),
      warnings = warnings.retained
    )

  private def coverageBeforeScan(
    request: UsagesBySymbolRequest,
    prepared: PreparedRequest,
    selectedGroups: List[ArtifactGroup],
    mapping: MappingFacts,
    freshness: FreshnessFacts,
    warnings: WarningCollector,
    deadline: UsageDeadline
  ): DeadlineValue[UsageCoverageEvidence] =
    var uniqueArtifactContents = 0
    var duplicateCopies = 0

    val groupIterator = selectedGroups.iterator
    while groupIterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringCoverageConstruction) then ()
      else
        val group = groupIterator.next()
        uniqueArtifactContents += 1
        var members = 0
        val memberIterator = group.members.iterator
        while memberIterator.hasNext && !deadline.expired do
          if deadline.check(UsageDeadlinePhase.DuringCoverageConstruction) then ()
          else
            memberIterator.next()
            members += 1
        if !deadline.expired then duplicateCopies += math.max(0, members - 1)

    if deadline.expired then DeadlineValue.Deadline
    else
      DeadlineValue.Complete(
        UsageCoverageEvidence(
          inventoryClosed = request.inventoryClosed,
          declaredSources = prepared.declaredSourceCount,
          selectedSources = prepared.selectedSourceCount,
          excludedSources = prepared.declaredSourceCount - prepared.selectedSourceCount,
          declaredArtifacts = prepared.declaredArtifactCount,
          selectedArtifacts = prepared.selectedArtifactCount,
          excludedArtifacts = prepared.declaredArtifactCount - prepared.selectedArtifactCount,
          rawArtifactBytes = prepared.rawArtifactBytes,
          uniqueArtifactContents = uniqueArtifactContents,
          duplicateCopies = duplicateCopies,
          parsedDocuments = mapping.mapped + mapping.unmapped + mapping.ambiguous,
          mappedDocuments = mapping.mapped,
          unmappedDocuments = mapping.unmapped,
          ambiguousDocuments = mapping.ambiguous,
          freshDocuments = freshness.fresh,
          staleDocuments = freshness.stale,
          missingDigestDocuments = freshness.missingDigest,
          documentsWithoutOccurrences = mapping.withoutOccurrences,
          documentsWithSynthetics = mapping.synthetics,
          scannedOrdinaryOccurrences = 0,
          matchingOccurrences = 0,
          returnedOccurrences = 0,
          totalWarnings = warnings.total,
          returnedWarnings = warnings.retainedCount
        )
      )

  private def limitEvidence(
    limits: UsagesBySymbolLimits,
    hit: List[String]
  ): UsageLimitEvidence =
    UsageLimitEvidence(
      artifactLimit = limits.maxArtifacts,
      documentLimit = limits.maxDocuments,
      sourceLimit = limits.maxSources,
      aggregateArtifactByteLimit = limits.maxAggregateArtifactBytes,
      perArtifactByteLimit = limits.maxArtifactBytes,
      perSourceByteLimit = limits.maxSourceBytes,
      scannedOccurrenceLimit = limits.maxScannedOccurrences,
      returnedOccurrenceLimit = limits.maxReturnedOccurrences,
      resultEvidenceByteLimit = limits.maxResultEvidenceBytes,
      warningLimit = limits.maxWarnings,
      duplicateGroupLimit = limits.maxDuplicateGroups,
      deadlineNanos = limits.deadlineNanos,
      hit = hit.distinct.sorted
    )

  private def boundOutput(
    report: UsagesBySymbolReport,
    limit: Int,
    deadline: UsageDeadline
  ): OutputBound =
    var hit = false
    var deadlineHit = deadline.check(UsageDeadlinePhase.BeforeOutputBounding)
    val occurrences = mutable.ArrayBuffer.empty[UsageOccurrenceEvidence]
    val duplicates = mutable.ArrayBuffer.empty[UsageDuplicateGroupEvidence]
    val warnings = mutable.ArrayBuffer.empty[String]

    def copyValues[A](values: List[A], target: mutable.ArrayBuffer[A]): Unit =
      val iterator = values.iterator
      while iterator.hasNext && !deadlineHit do
        if deadline.check(UsageDeadlinePhase.DuringOutputBounding) then deadlineHit = true
        else target += iterator.next()

    if !deadlineHit then copyValues(report.occurrences, occurrences)
    if !deadlineHit then copyValues(report.duplicateGroups, duplicates)
    if !deadlineHit then copyValues(report.warnings, warnings)

    def overLimit(): Boolean =
      if deadlineHit then false
      else
        estimatedBytes(report, occurrences, duplicates, warnings, deadline) match
          case DeadlineValue.Deadline =>
            deadlineHit = true
            false
          case DeadlineValue.Complete(value) => value > limit.toLong

    var tooLarge = overLimit()
    while !deadlineHit && occurrences.nonEmpty && tooLarge do
      if deadline.check(UsageDeadlinePhase.DuringOutputBounding) then deadlineHit = true
      else
        occurrences.remove(occurrences.size - 1)
        hit = true
        tooLarge = overLimit()
    while !deadlineHit && warnings.nonEmpty && tooLarge do
      if deadline.check(UsageDeadlinePhase.DuringOutputBounding) then deadlineHit = true
      else
        warnings.remove(warnings.size - 1)
        hit = true
        tooLarge = overLimit()
    while !deadlineHit && duplicates.nonEmpty && tooLarge do
      if deadline.check(UsageDeadlinePhase.DuringOutputBounding) then deadlineHit = true
      else
        duplicates.remove(duplicates.size - 1)
        hit = true
        tooLarge = overLimit()
    if !deadlineHit && tooLarge then hit = true

    def snapshot[A](values: mutable.ArrayBuffer[A]): List[A] =
      val result = ListBuffer.empty[A]
      var index = 0
      while index < values.size && !deadlineHit do
        if deadline.check(UsageDeadlinePhase.DuringOutputBounding) then deadlineHit = true
        else result += values(index)
        index += 1
      result.toList

    if deadlineHit then OutputBound(Nil, Nil, Nil, hit, deadlineHit = true)
    else
      val boundedOccurrences = snapshot(occurrences)
      val boundedDuplicates = snapshot(duplicates)
      val boundedWarnings = snapshot(warnings)
      if !deadlineHit && deadline.check(UsageDeadlinePhase.AfterOutputBounding) then
        deadlineHit = true
      if deadlineHit then OutputBound(Nil, Nil, Nil, hit, deadlineHit = true)
      else OutputBound(boundedOccurrences, boundedDuplicates, boundedWarnings, hit, deadlineHit = false)

  private def estimatedBytes(
    report: UsagesBySymbolReport,
    occurrences: collection.Seq[UsageOccurrenceEvidence],
    duplicates: collection.Seq[UsageDuplicateGroupEvidence],
    warnings: collection.Seq[String],
    deadline: UsageDeadline
  ): DeadlineValue[Long] =
    var total = 768L
    report.target.foreach { value =>
      if deadline.check(UsageDeadlinePhase.DuringOutputSizeEstimation) then ()
      else
        total += 96L +
          value.stableSymbol.map(utf8Bytes).getOrElse(0) +
          value.localSymbolMarker.map(utf8Bytes).getOrElse(0) +
          value.source.map(utf8Bytes).getOrElse(0) +
          value.documentEvidenceId.map(utf8Bytes).getOrElse(0)
    }
    var index = 0
    while index < occurrences.size && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOutputSizeEstimation) then ()
      else
        val value = occurrences(index)
        total += 160L +
          value.stableSymbol.map(utf8Bytes).getOrElse(0) +
          value.localSymbolMarker.map(utf8Bytes).getOrElse(0) +
          value.source.map(utf8Bytes).getOrElse(0) +
          value.safeUri.map(utf8Bytes).getOrElse(0) +
          utf8Bytes(value.module) +
          utf8Bytes(value.sourceSet) +
          utf8Bytes(value.artifactGroupId)
      index += 1
    index = 0
    while index < duplicates.size && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOutputSizeEstimation) then ()
      else
        val value = duplicates(index)
        total += 96L + utf8Bytes(value.groupId) + utf8Bytes(value.representative)
        val sampleIterator = value.samplePaths.iterator
        while sampleIterator.hasNext && !deadline.expired do
          if deadline.check(UsageDeadlinePhase.DuringOutputSizeEstimation) then ()
          else total += utf8Bytes(sampleIterator.next())
      index += 1
    index = 0
    while index < warnings.size && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOutputSizeEstimation) then ()
      else total += 8L + utf8Bytes(warnings(index))
      index += 1
    if deadline.expired then DeadlineValue.Deadline
    else DeadlineValue.Complete(total)

  private def validateLimits(
    limits: UsagesBySymbolLimits
  ): Either[UsagesBySymbolFailure, Unit] =
    val positive =
      List(
        limits.maxArtifacts.toLong,
        limits.maxDocuments.toLong,
        limits.maxSources.toLong,
        limits.maxAggregateArtifactBytes,
        limits.maxArtifactBytes,
        limits.maxSourceBytes,
        limits.maxScannedOccurrences.toLong,
        limits.maxReturnedOccurrences.toLong,
        limits.maxResultEvidenceBytes.toLong,
        limits.maxStringBytes.toLong,
        limits.maxPathBytes.toLong,
        limits.maxUriBytes.toLong,
        limits.maxWarnings.toLong,
        limits.maxDuplicateGroups.toLong,
        limits.maxDuplicatePathSamples.toLong,
        limits.maxSelectorValues.toLong,
        limits.deadlineNanos
      ).forall(_ > 0)
    val withinHardMaximums =
      limits.maxArtifacts <= UsagesBySymbolLimits.HardMaxArtifacts &&
        limits.maxDocuments <= UsagesBySymbolLimits.HardMaxDocuments &&
        limits.maxSources <= UsagesBySymbolLimits.HardMaxSources &&
        limits.maxAggregateArtifactBytes <= UsagesBySymbolLimits.HardMaxAggregateArtifactBytes &&
        limits.maxArtifactBytes <= UsagesBySymbolLimits.HardMaxArtifactBytes &&
        limits.maxSourceBytes <= UsagesBySymbolLimits.HardMaxSourceBytes &&
        limits.maxScannedOccurrences <= UsagesBySymbolLimits.HardMaxScannedOccurrences &&
        limits.maxReturnedOccurrences <= UsagesBySymbolLimits.HardMaxReturnedOccurrences &&
        limits.maxResultEvidenceBytes <= UsagesBySymbolLimits.HardMaxResultEvidenceBytes &&
        limits.maxStringBytes <= UsagesBySymbolLimits.HardMaxStringBytes &&
        limits.maxPathBytes <= UsagesBySymbolLimits.HardMaxPathBytes &&
        limits.maxUriBytes <= UsagesBySymbolLimits.HardMaxUriBytes &&
        limits.maxWarnings <= UsagesBySymbolLimits.HardMaxWarnings &&
        limits.maxDuplicateGroups <= UsagesBySymbolLimits.HardMaxDuplicateGroups &&
        limits.maxDuplicatePathSamples <= UsagesBySymbolLimits.HardMaxDuplicatePathSamples &&
        limits.maxSelectorValues <= UsagesBySymbolLimits.HardMaxSelectorValues &&
        limits.deadlineNanos <= UsagesBySymbolLimits.HardDeadlineNanos
    if !positive then Left(invalid("All internal spike limits must be positive"))
    else if !withinHardMaximums then Left(invalid("Internal spike limits cannot exceed hard bounds"))
    else Right(())

  private def selected(
    declaration: DeclaredUsageSource | DeclaredUsageArtifact,
    selectors: UsagesBySymbolSelectors
  ): Boolean =
    val (module, sourceSet, generated) = declaration match
      case value: DeclaredUsageSource   => metadata(value)
      case value: DeclaredUsageArtifact => metadata(value)
    (selectors.modules.isEmpty || selectors.modules.contains(module)) &&
    (selectors.sourceSets.isEmpty || selectors.sourceSets.contains(sourceSet)) &&
    (selectors.includeGenerated || !generated)

  private def metadata(
    declaration: DeclaredUsageSource | DeclaredUsageArtifact
  ): (String, String, Boolean) =
    declaration match
      case value: DeclaredUsageSource =>
        (value.module, value.sourceSet, value.generated)
      case value: DeclaredUsageArtifact =>
        (value.module, value.sourceSet, value.generated)

  private def sortedWork(
    work: List[DocumentWork],
    deadline: UsageDeadline
  ): DeadlineValue[List[DocumentWork]] =
    var ordered = scala.collection.immutable.TreeMap.empty[(String, String, Int), DocumentWork]
    val workIterator = work.iterator
    while workIterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOccurrenceSort) then ()
      else
        val item = workIterator.next()
        val path = item.mapping match
          case SourceMapping.Unique(source) => source.declaration.path
          case _                            => item.safeUri.getOrElse("~unmapped")
        ordered = ordered.updated((path, item.group.groupId, item.documentIndex), item)
    val result = ListBuffer.empty[DocumentWork]
    val iterator = ordered.valuesIterator
    while iterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOccurrenceSort) then ()
      else result += iterator.next()
    if deadline.expired then DeadlineValue.Deadline
    else DeadlineValue.Complete(result.toList)

  private def sortOccurrences(
    values: List[UsageOccurrenceEvidence],
    deadline: UsageDeadline
  ): DeadlineValue[List[UsageOccurrenceEvidence]] =
    type PrimaryKey = (String, Int, Int, Int, Int, Int)
    type SecondaryKey = (String, String, Boolean, String, String, Int)
    var ordered = scala.collection.immutable.TreeMap
      .empty[(PrimaryKey, SecondaryKey, Int), UsageOccurrenceEvidence]
    var index = 0
    val valuesIterator = values.iterator
    while valuesIterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOccurrenceSort) then ()
      else
        val value = valuesIterator.next()
        val primary = (
          value.source.orElse(value.safeUri).getOrElse("~unmapped"),
          value.range.startLine,
          value.range.startCharacter,
          value.range.endLine,
          value.range.endCharacter,
          if value.role == UsageOccurrenceRole.Definition then 0 else 1
        )
        val secondary = (
          value.module,
          value.sourceSet,
          value.generated,
          value.stableSymbol.getOrElse(value.localSymbolMarker.getOrElse("")),
          value.artifactGroupId,
          value.documentIndex
        )
        ordered = ordered.updated((primary, secondary, index), value)
        index += 1
    val result = ListBuffer.empty[UsageOccurrenceEvidence]
    val iterator = ordered.valuesIterator
    while iterator.hasNext && !deadline.expired do
      if deadline.check(UsageDeadlinePhase.DuringOccurrenceSort) then ()
      else result += iterator.next()
    if deadline.expired then DeadlineValue.Deadline
    else DeadlineValue.Complete(result.toList)

  private def ordinaryRole(
    occurrence: SymbolOccurrence
  ): Option[UsageOccurrenceRole] =
    occurrence.role match
      case SymbolOccurrence.Role.DEFINITION => Some(UsageOccurrenceRole.Definition)
      case SymbolOccurrence.Role.REFERENCE  => Some(UsageOccurrenceRole.Reference)
      case _                                => None

  private def validRange(value: scala.meta.internal.semanticdb.Range): Boolean =
    value.startLine >= 0 &&
      value.startCharacter >= 0 &&
      value.endLine >= 0 &&
      value.endCharacter >= 0 &&
      positionAtOrBefore(
        value.startLine,
        value.startCharacter,
        value.endLine,
        value.endCharacter
      ) &&
      (value.startLine != value.endLine || value.startCharacter != value.endCharacter)

  private def containsPoint(
    range: scala.meta.internal.semanticdb.Range,
    line: Int,
    character: Int
  ): Boolean =
    positionAtOrBefore(range.startLine, range.startCharacter, line, character) &&
      positionBefore(line, character, range.endLine, range.endCharacter)

  private def positionAtOrBefore(
    leftLine: Int,
    leftCharacter: Int,
    rightLine: Int,
    rightCharacter: Int
  ): Boolean =
    leftLine < rightLine || (leftLine == rightLine && leftCharacter <= rightCharacter)

  private def positionBefore(
    leftLine: Int,
    leftCharacter: Int,
    rightLine: Int,
    rightCharacter: Int
  ): Boolean =
    leftLine < rightLine || (leftLine == rightLine && leftCharacter < rightCharacter)

  private def toRange(value: scala.meta.internal.semanticdb.Range): SemanticRange =
    SemanticRange(
      startLine = value.startLine,
      startCharacter = value.startCharacter,
      endLine = value.endLine,
      endCharacter = value.endCharacter
    )

  private def normalizeSafeUri(uri: String, maxBytes: Int): Option[String] =
    if uri.isEmpty || utf8Bytes(uri) > maxBytes || uri.contains('\\') then None
    else
      try
        val path = Path.of(uri)
        val normalized = path.normalize().toString.replace('\\', '/')
        Option.when(
          !path.isAbsolute &&
            normalized == uri.stripPrefix("./") &&
            !normalized.startsWith("../") &&
            !normalized.split('/').contains("..") &&
            !normalized.contains("://")
        )(normalized)
      catch
        case NonFatal(_) => None

  private def mappingSubject(mapping: SourceMapping): Option[String] =
    mapping match
      case SourceMapping.Unique(source) => Some(source.declaration.path)
      case _                            => None

  private def localSymbol(symbol: String): Boolean =
    symbol.matches("local[0-9]+")

  private def validSymbol(symbol: String, maxBytes: Int): Boolean =
    symbol.nonEmpty && utf8Bytes(symbol) <= maxBytes && !symbol.exists(_.isControl)

  private def validIdentifier(value: String, maxBytes: Int): Boolean =
    value.nonEmpty && utf8Bytes(value) <= maxBytes && !value.exists(_.isControl)

  private def usableMd5(value: String): Boolean =
    value.matches("(?i)[0-9a-f]{32}")

  private def sha256(bytes: Array[Byte]): String =
    digest("SHA-256", bytes)

  private def md5(bytes: Array[Byte]): String =
    digest("MD5", bytes)

  private def digest(algorithm: String, bytes: Array[Byte]): String =
    MessageDigest.getInstance(algorithm).digest(bytes).map("%02x".format(_)).mkString

  private def safeSize(
    path: Path,
    relative: String,
    deadline: UsageDeadline,
    beforePhase: UsageDeadlinePhase,
    afterPhase: UsageDeadlinePhase,
    exceptionalPhase: UsageDeadlinePhase,
    boundedOperations: UsagesBySymbolBoundedOperations
  ): Either[UsagesBySymbolFailure, DeadlineValue[Long]] =
    observeBoundedOperation(deadline, beforePhase, afterPhase, exceptionalPhase)(
      boundedOperations.size(path),
      failure(
        UsagesBySymbolFailureKind.IoFailure,
        s"Unable to inspect declared file size: $relative"
      )
    ) match
      case BoundedOperationResult.Deadline => Right(DeadlineValue.Deadline)
      case BoundedOperationResult.Failed(value) => Left(value)
      case BoundedOperationResult.Complete(value) => Right(DeadlineValue.Complete(value))

  private def saturatingAdd(left: Long, right: Long): Long =
    if right > 0 && left > Long.MaxValue - right then Long.MaxValue
    else left + right

  private def utf8Bytes(value: String): Int =
    value.getBytes(StandardCharsets.UTF_8).length

  private def boundedUtf8(value: String, maxBytes: Int): String =
    if utf8Bytes(value) <= maxBytes then value
    else
      val builder = StringBuilder()
      value.iterator.takeWhile { character =>
        val candidate = builder.result() + character
        if utf8Bytes(candidate) <= maxBytes then
          builder.append(character)
          true
        else false
      }.foreach(_ => ())
      builder.result()

  private def failure(
    kind: UsagesBySymbolFailureKind,
    message: String
  ): UsagesBySymbolFailure =
    UsagesBySymbolFailure(kind, boundedUtf8(message.replaceAll("\\s+", " ").trim, 1024))

  private def invalid(message: String): UsagesBySymbolFailure =
    failure(UsagesBySymbolFailureKind.InvalidInput, message)

  private def unsafe(message: String): UsagesBySymbolFailure =
    failure(UsagesBySymbolFailureKind.UnsafeFilesystem, message)
