package semantic.harness.semanticdb_reader

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

private[harness] object UsagesCliService:
  def run(request: UsagesCliRequest): Either[UsagesPublicFailure, UsagesPublicResult] =
    try
      for
        manifest <- UsagesManifestLoader.load(request.workspace, request.manifest)
        _ <- validatePointDeclarations(request.target, manifest)
        internalRequest = toInternalRequest(request, manifest)
        limits = UsagesBySymbolLimits.Default.copy(
          maxReturnedOccurrences = request.returnedOccurrenceLimit
        )
        report <- UsagesBySymbolSpike.run(internalRequest, limits).left.map(mapFailure)
        result <- mapReport(request, report, limits)
      yield result
    catch
      case NonFatal(_) => Left(internalFailure("Unable to complete usages request"))

  private[semanticdb_reader] def mapReportForTest(
    request: UsagesCliRequest,
    report: UsagesBySymbolReport,
    effectiveLimits: UsagesBySymbolLimits = UsagesBySymbolLimits.Default
  ): Either[UsagesPublicFailure, UsagesPublicResult] =
    mapReport(request, report, effectiveLimits)

  private def validatePointDeclarations(
    target: UsagesCliTarget,
    manifest: LoadedUsagesManifest
  ): Either[UsagesPublicFailure, Unit] =
    target match
      case _: UsagesCliTarget.ExplicitGlobal => Right(())
      case point: UsagesCliTarget.Point =>
        if !manifest.sources.exists(_.path == point.source) then
          Left(invalid("Point source must be declared in the manifest"))
        else if !manifest.artifacts.exists(_.path == point.semanticdb) then
          Left(invalid("Point SemanticDB artifact must be declared in the manifest"))
        else Right(())

  private def toInternalRequest(
    request: UsagesCliRequest,
    manifest: LoadedUsagesManifest
  ): UsagesBySymbolRequest =
    val target = request.target match
      case UsagesCliTarget.ExplicitGlobal(symbol) => UsagesBySymbolTarget.ExplicitGlobal(symbol)
      case point: UsagesCliTarget.Point =>
        UsagesBySymbolTarget.Point(point.source, point.line, point.column, point.semanticdb)
    UsagesBySymbolRequest(
      workspace = request.workspace,
      inventoryClosed = manifest.inventoryClosed,
      sources = manifest.sources.map(value =>
        DeclaredUsageSource(value.path, value.module, value.sourceSet, value.generated)
      ),
      artifacts = manifest.artifacts.map(value =>
        DeclaredUsageArtifact(value.path, value.module, value.sourceSet, value.generated)
      ),
      target = target,
      selectors = UsagesBySymbolSelectors(
        includeDefinitions = request.selectors.includeDefinitions,
        modules = request.selectors.modules.toSet,
        sourceSets = request.selectors.sourceSets.toSet,
        includeGenerated = request.selectors.includeGenerated
      )
    )

  private def mapReport(
    request: UsagesCliRequest,
    report: UsagesBySymbolReport,
    effectiveLimits: UsagesBySymbolLimits
  ): Either[UsagesPublicFailure, UsagesPublicResult] =
    for
      state <- mapState(report.state)
      targetMode = request.target match
        case _: UsagesCliTarget.ExplicitGlobal => UsagesPublicTargetMode.ExplicitGlobal
        case _: UsagesCliTarget.Point          => UsagesPublicTargetMode.PointSelected
      target <- traverseOption(report.target)(mapTarget)
      occurrences <- traverse(report.occurrences)(mapOccurrence)
      hits <- traverse(report.limits.hit)(mapHit)
      reasons <- reasonsFor(state, report, hits)
      result = UsagesPublicResult(
        state = state,
        targetMode = targetMode,
        target = target,
        selectors = request.selectors.copy(
          modules = request.selectors.modules.distinct.sorted,
          sourceSets = request.selectors.sourceSets.distinct.sorted
        ),
        occurrences = occurrences,
        duplicateGroups = report.duplicateGroups.map(value =>
          UsagesPublicDuplicateGroup(
            groupId = value.groupId,
            sizeBytes = value.sizeBytes,
            copyCount = value.copyCount,
            representative = value.representative,
            samplePaths = value.samplePaths.sorted,
            pathsTruncated = value.pathsTruncated
          )
        ).sortBy(_.groupId),
        coverage = mapCoverage(report.coverage),
        limits = mapLimits(report.limits, effectiveLimits, hits),
        reasons = reasons,
        warnings = report.warnings.sorted
      )
      _ <- validateResult(result)
    yield result

  private def mapState(value: UsagesBySymbolState): Either[UsagesPublicFailure, UsagesPublicState] =
    value match
      case UsagesBySymbolState.EvidenceFound => Right(UsagesPublicState.EvidenceFound)
      case UsagesBySymbolState.NoUsagesObserved => Right(UsagesPublicState.NoUsagesObserved)
      case UsagesBySymbolState.CoverageIncomplete => Right(UsagesPublicState.CoverageIncomplete)
      case UsagesBySymbolState.TargetAmbiguous => Right(UsagesPublicState.TargetAmbiguous)
      case UsagesBySymbolState.TargetUnresolved => Right(UsagesPublicState.TargetUnresolved)
      case UsagesBySymbolState.ArtifactStaleOrInconsistent => Right(UsagesPublicState.ArtifactStaleOrInconsistent)
      case UsagesBySymbolState.Truncated => Right(UsagesPublicState.Truncated)
      case UsagesBySymbolState.UnsupportedConstruct => Right(UsagesPublicState.UnsupportedConstruct)

  private def mapTarget(value: UsageTargetEvidence): Either[UsagesPublicFailure, UsagesPublicTarget] =
    for
      mode <- value.mode match
        case UsageTargetMode.ExplicitGlobal => Right(UsagesPublicTargetMode.ExplicitGlobal)
        case UsageTargetMode.PointSelected  => Right(UsagesPublicTargetMode.PointSelected)
      identity <- value.identityKind match
        case UsageIdentityKind.Global            => Right(UsagesPublicIdentityKind.Global)
        case UsageIdentityKind.LocalDocumentOnly => Right(UsagesPublicIdentityKind.LocalDocumentOnly)
    yield UsagesPublicTarget(
      mode = mode,
      identityKind = identity,
      stableSymbol = value.stableSymbol,
      localSymbolMarker = value.localSymbolMarker.map(_ => "DocumentLocal"),
      source = value.source,
      range = value.range.map(mapRange),
      documentEvidenceId = value.documentEvidenceId
    )

  private def mapOccurrence(value: UsageOccurrenceEvidence): Either[UsagesPublicFailure, UsagesPublicOccurrence] =
    for
      role <- value.role match
        case UsageOccurrenceRole.Definition => Right(UsagesPublicOccurrenceRole.Definition)
        case UsageOccurrenceRole.Reference  => Right(UsagesPublicOccurrenceRole.Reference)
      freshness <- value.freshness match
        case UsageFreshness.Fresh            => Right(UsagesPublicFreshness.Fresh)
        case UsageFreshness.Stale            => Right(UsagesPublicFreshness.Stale)
        case UsageFreshness.MissingDigest    => Right(UsagesPublicFreshness.MissingDigest)
        case UsageFreshness.Unmapped         => Right(UsagesPublicFreshness.Unmapped)
        case UsageFreshness.AmbiguousMapping => Right(UsagesPublicFreshness.AmbiguousMapping)
      _ <- Either.cond(
        value.stableSymbol.nonEmpty != value.localSymbolMarker.nonEmpty,
        (),
        internalFailure("Occurrence identity invariant failed")
      )
    yield UsagesPublicOccurrence(
      stableSymbol = value.stableSymbol,
      localSymbolMarker = value.localSymbolMarker.map(_ => "DocumentLocal"),
      role = role,
      source = value.source,
      safeUri = value.safeUri,
      range = mapRange(value.range),
      module = value.module,
      sourceSet = value.sourceSet,
      generated = value.generated,
      freshness = freshness,
      artifactGroupId = value.artifactGroupId,
      documentIndex = value.documentIndex
    )

  private def mapRange(value: SemanticRange): UsagesPublicRange =
    UsagesPublicRange(
      startLine = value.startLine,
      startCharacter = value.startCharacter,
      endLine = value.endLine,
      endCharacter = value.endCharacter
    )

  private def mapCoverage(value: UsageCoverageEvidence): UsagesPublicCoverage =
    UsagesPublicCoverage(
      inventoryBasis = UsagesPublicInventoryBasis(inventoryClosed = value.inventoryClosed),
      declaredSources = value.declaredSources,
      selectedSources = value.selectedSources,
      excludedSources = value.excludedSources,
      declaredArtifacts = value.declaredArtifacts,
      selectedArtifacts = value.selectedArtifacts,
      excludedArtifacts = value.excludedArtifacts,
      rawArtifactBytes = value.rawArtifactBytes,
      uniqueArtifactContents = value.uniqueArtifactContents,
      duplicateCopies = value.duplicateCopies,
      parsedDocuments = value.parsedDocuments,
      mappedDocuments = value.mappedDocuments,
      unmappedDocuments = value.unmappedDocuments,
      ambiguousDocuments = value.ambiguousDocuments,
      freshDocuments = value.freshDocuments,
      staleDocuments = value.staleDocuments,
      missingDigestDocuments = value.missingDigestDocuments,
      documentsWithoutOccurrences = value.documentsWithoutOccurrences,
      documentsWithSynthetics = value.documentsWithSynthetics,
      scannedOrdinaryOccurrences = value.scannedOrdinaryOccurrences,
      matchingOccurrences = value.matchingOccurrences,
      returnedOccurrences = value.returnedOccurrences,
      totalWarnings = value.totalWarnings,
      returnedWarnings = value.returnedWarnings
    )

  private def mapLimits(
    value: UsageLimitEvidence,
    effective: UsagesBySymbolLimits,
    hits: List[UsagesPublicLimitHit]
  ): UsagesPublicLimits =
    UsagesPublicLimits(
      artifactLimit = value.artifactLimit,
      documentLimit = value.documentLimit,
      sourceLimit = value.sourceLimit,
      manifestByteLimit = UsagesManifestLoader.MaxManifestBytes,
      aggregateArtifactByteLimit = value.aggregateArtifactByteLimit,
      perArtifactByteLimit = value.perArtifactByteLimit,
      perSourceByteLimit = value.perSourceByteLimit,
      stringByteLimit = effective.maxStringBytes,
      pathByteLimit = effective.maxPathBytes,
      uriByteLimit = effective.maxUriBytes,
      scannedOccurrenceLimit = value.scannedOccurrenceLimit,
      returnedOccurrenceLimit = value.returnedOccurrenceLimit,
      resultEvidenceByteLimit = value.resultEvidenceByteLimit,
      warningLimit = value.warningLimit,
      duplicateGroupLimit = value.duplicateGroupLimit,
      duplicatePathSampleLimit = effective.maxDuplicatePathSamples,
      selectorValueLimit = effective.maxSelectorValues,
      deadlineNanos = value.deadlineNanos,
      hits = hits.sortBy(_.toString)
    )

  private def mapHit(value: String): Either[UsagesPublicFailure, UsagesPublicLimitHit] =
    UsagesPublicLimitHit.values.find(_.toString == value).toRight(
      internalFailure("Unknown internal usages limit category")
    )

  private def reasonsFor(
    state: UsagesPublicState,
    report: UsagesBySymbolReport,
    hits: List[UsagesPublicLimitHit]
  ): Either[UsagesPublicFailure, List[UsagesPublicReason]] =
    state match
      case UsagesPublicState.UnsupportedConstruct =>
        Right(List(UsagesPublicReason(UsagesPublicReasonCode.ExplicitLocalSymbolUnsupported)))
      case UsagesPublicState.TargetAmbiguous =>
        Right(List(UsagesPublicReason(UsagesPublicReasonCode.PointTargetAmbiguous)))
      case UsagesPublicState.TargetUnresolved =>
        Right(List(UsagesPublicReason(UsagesPublicReasonCode.PointTargetUnresolved)))
      case _ =>
        for
          warningReasons <- traverse(report.warnings)(warningReason)
          flattened = warningReasons.flatten
          derived = List(
            Option.when(!report.coverage.inventoryClosed)(
              UsagesPublicReason(UsagesPublicReasonCode.OpenInventory)
            ),
            Option.when(report.coverage.excludedSources + report.coverage.excludedArtifacts > 0)(
              UsagesPublicReason(
                UsagesPublicReasonCode.SelectorExcludedScope,
                count = Some(report.coverage.excludedSources + report.coverage.excludedArtifacts)
              )
            )
          ).flatten
          grouped = (derived ++ flattened)
            .groupBy(value => (value.code, value.subject))
            .toList
            .map { case ((code, subject), values) =>
              val explicitCount = values.flatMap(_.count).sum
              val count = if explicitCount > 0 then Some(explicitCount) else Option.when(values.size > 1)(values.size)
              UsagesPublicReason(code, subject, count)
            }
            .sortBy(value => (value.code.toString, value.subject.getOrElse(""), value.count.getOrElse(0)))
        yield grouped

  private def warningReason(value: String): Either[UsagesPublicFailure, Option[UsagesPublicReason]] =
    val separator = value.indexOf(": ")
    val category = if separator >= 0 then value.substring(0, separator) else value
    val subject = Option.when(separator >= 0)(value.substring(separator + 2)).filter(_.nonEmpty)
    val code = category match
      case "ConflictingDuplicateMetadata" => Some(UsagesPublicReasonCode.ConflictingDuplicateMetadata)
      case "MultipleDistinctDocumentsMapToOneSource" => Some(UsagesPublicReasonCode.MultipleDistinctDocumentsForSource)
      case "ArtifactAndSourceMetadataConflict" => Some(UsagesPublicReasonCode.ArtifactSourceMetadataConflict)
      case "DeclaredSourceHasNoUniqueDocument" => Some(UsagesPublicReasonCode.UncoveredDeclaredSource)
      case "MatchingOccurrenceHasNoRange" => Some(UsagesPublicReasonCode.MatchingOccurrenceHasNoRange)
      case "MatchingOccurrenceHasInvalidRange" => Some(UsagesPublicReasonCode.InvalidMatchingRange)
      case "UnsafeOrOverlongDocumentUri" => Some(UsagesPublicReasonCode.UnsafeDocumentUri)
      case "DocumentSourceMappingMissing" => Some(UsagesPublicReasonCode.UnmappedDocument)
      case "DocumentSourceMappingAmbiguous" => Some(UsagesPublicReasonCode.AmbiguousDocumentMapping)
      case "SourceDigestStale" => Some(UsagesPublicReasonCode.StaleSourceDigest)
      case "SourceDigestMissing" => Some(UsagesPublicReasonCode.MissingDigest)
      case "DocumentOccurrenceDataAbsent" => Some(UsagesPublicReasonCode.MissingOccurrenceSection)
      case "SyntheticsExcluded" => Some(UsagesPublicReasonCode.SyntheticsExcluded)
      case "DuplicateGroupEvidenceLimitReached" | "DuplicatePathEvidenceLimitReached" => None
      case _ => return Left(internalFailure("Unknown internal usages warning category"))
    Right(code.map(value => UsagesPublicReason(value, subject = subject)))

  private val IncompleteCodes = Set(
    UsagesPublicReasonCode.OpenInventory,
    UsagesPublicReasonCode.SelectorExcludedScope,
    UsagesPublicReasonCode.UnmappedDocument,
    UsagesPublicReasonCode.AmbiguousDocumentMapping,
    UsagesPublicReasonCode.UncoveredDeclaredSource,
    UsagesPublicReasonCode.MissingDigest,
    UsagesPublicReasonCode.MissingOccurrenceSection,
    UsagesPublicReasonCode.MatchingOccurrenceHasNoRange,
    UsagesPublicReasonCode.UnsafeDocumentUri
  )

  private val InconsistentCodes = Set(
    UsagesPublicReasonCode.StaleSourceDigest,
    UsagesPublicReasonCode.ConflictingDuplicateMetadata,
    UsagesPublicReasonCode.ArtifactSourceMetadataConflict,
    UsagesPublicReasonCode.MultipleDistinctDocumentsForSource,
    UsagesPublicReasonCode.InvalidMatchingRange
  )

  private def validateResult(value: UsagesPublicResult): Either[UsagesPublicFailure, Unit] =
    val targetModeConsistent = value.target.forall(_.mode == value.targetMode)
    val noEvidence = value.occurrences.isEmpty && value.duplicateGroups.isEmpty
    val terminalCoverage =
      value.coverage.uniqueArtifactContents == 0 &&
        value.coverage.duplicateCopies == 0 &&
        value.coverage.parsedDocuments == 0 &&
        value.coverage.mappedDocuments == 0 &&
        value.coverage.unmappedDocuments == 0 &&
        value.coverage.ambiguousDocuments == 0 &&
        value.coverage.freshDocuments == 0 &&
        value.coverage.staleDocuments == 0 &&
        value.coverage.missingDigestDocuments == 0 &&
        value.coverage.documentsWithoutOccurrences == 0 &&
        value.coverage.documentsWithSynthetics == 0 &&
        value.coverage.scannedOrdinaryOccurrences == 0 &&
        value.coverage.matchingOccurrences == 0 &&
        value.coverage.returnedOccurrences == 0
    val valid = targetModeConsistent && (value.state match
      case UsagesPublicState.EvidenceFound =>
        value.target.nonEmpty && value.occurrences.nonEmpty && value.limits.hits.isEmpty &&
          value.reasons.forall(_.code == UsagesPublicReasonCode.SyntheticsExcluded)
      case UsagesPublicState.NoUsagesObserved =>
        value.target.nonEmpty && value.occurrences.isEmpty && value.coverage.matchingOccurrences == 0 &&
          value.coverage.returnedOccurrences == 0 && value.coverage.inventoryBasis.inventoryClosed &&
          value.coverage.excludedSources == 0 && value.coverage.excludedArtifacts == 0 &&
          value.coverage.unmappedDocuments == 0 && value.coverage.ambiguousDocuments == 0 &&
          value.coverage.staleDocuments == 0 && value.coverage.missingDigestDocuments == 0 &&
          value.coverage.documentsWithoutOccurrences == 0 && value.limits.hits.isEmpty &&
          value.reasons.forall(_.code == UsagesPublicReasonCode.SyntheticsExcluded)
      case UsagesPublicState.CoverageIncomplete =>
        value.target.nonEmpty && value.limits.hits.isEmpty && value.reasons.exists(reason => IncompleteCodes(reason.code)) &&
          !value.reasons.exists(reason => InconsistentCodes(reason.code))
      case UsagesPublicState.ArtifactStaleOrInconsistent =>
        value.target.nonEmpty && value.limits.hits.isEmpty && value.reasons.exists(reason => InconsistentCodes(reason.code))
      case UsagesPublicState.Truncated =>
        value.limits.hits.nonEmpty && (value.target match
          case Some(_) => !value.limits.hits.contains(UsagesPublicLimitHit.TargetResolutionOccurrenceLimit)
          case None =>
            value.targetMode == UsagesPublicTargetMode.PointSelected && noEvidence && terminalCoverage &&
              value.limits.hits.contains(UsagesPublicLimitHit.TargetResolutionOccurrenceLimit)
        )
      case UsagesPublicState.TargetAmbiguous =>
        value.targetMode == UsagesPublicTargetMode.PointSelected && value.target.isEmpty && noEvidence && terminalCoverage &&
          value.limits.hits.isEmpty && value.reasons == List(UsagesPublicReason(UsagesPublicReasonCode.PointTargetAmbiguous))
      case UsagesPublicState.TargetUnresolved =>
        value.targetMode == UsagesPublicTargetMode.PointSelected && value.target.isEmpty && noEvidence && terminalCoverage &&
          value.limits.hits.isEmpty && value.reasons == List(UsagesPublicReason(UsagesPublicReasonCode.PointTargetUnresolved))
      case UsagesPublicState.UnsupportedConstruct =>
        value.targetMode == UsagesPublicTargetMode.ExplicitGlobal && value.target.isEmpty && noEvidence && terminalCoverage &&
          value.limits.hits.isEmpty && value.reasons == List(UsagesPublicReason(UsagesPublicReasonCode.ExplicitLocalSymbolUnsupported))
    )
    Either.cond(valid, (), internalFailure("Public usages result invariant failed"))

  private def mapFailure(value: UsagesBySymbolFailure): UsagesPublicFailure =
    val kind = value.kind match
      case UsagesBySymbolFailureKind.InvalidInput => UsagesPublicFailureKind.InvalidInput
      case UsagesBySymbolFailureKind.UnsafeFilesystem => UsagesPublicFailureKind.UnsafeFilesystem
      case UsagesBySymbolFailureKind.IoFailure => UsagesPublicFailureKind.IoFailure
      case UsagesBySymbolFailureKind.ParseFailure => UsagesPublicFailureKind.ParseFailure
      case UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution => UsagesPublicFailureKind.TimeoutBeforeTargetResolution
      case UsagesBySymbolFailureKind.InternalInvariant => UsagesPublicFailureKind.InternalInvariant
    UsagesPublicFailure(failureKind = kind, message = boundedUtf8(value.message, 1024))

  private def traverse[A, B](values: List[A])(f: A => Either[UsagesPublicFailure, B]): Either[UsagesPublicFailure, List[B]] =
    values.foldLeft[Either[UsagesPublicFailure, List[B]]](Right(Nil)) { (result, value) =>
      for
        accumulated <- result
        next <- f(value)
      yield accumulated :+ next
    }

  private def traverseOption[A, B](value: Option[A])(f: A => Either[UsagesPublicFailure, B]): Either[UsagesPublicFailure, Option[B]] =
    value match
      case Some(current) => f(current).map(Some(_))
      case None          => Right(None)

  private def boundedUtf8(value: String, limit: Int): String =
    if value.getBytes(StandardCharsets.UTF_8).length <= limit then value
    else value.iterator.foldLeft(StringBuilder()) { (builder, character) =>
      val candidate = builder.result() + character
      if candidate.getBytes(StandardCharsets.UTF_8).length <= limit then builder.append(character)
      else builder
    }.result()

  private def invalid(message: String) = UsagesPublicFailure(failureKind = UsagesPublicFailureKind.InvalidInput, message = message)
  private def internalFailure(message: String) = UsagesPublicFailure(failureKind = UsagesPublicFailureKind.InternalInvariant, message = message)
