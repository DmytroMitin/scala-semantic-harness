package semantic.harness.semanticdb_reader

import java.nio.file.Path

class UsagesCliServiceSuite extends munit.FunSuite:
  private val request = UsagesCliRequest(
    workspace = Path.of("."),
    manifest = "usages.json",
    target = UsagesCliTarget.Point("src/A.scala", 1, 1, "out/A.semanticdb"),
    selectors = UsagesPublicSelectors(false, Nil, Nil, false),
    returnedOccurrenceLimit = 500
  )

  test("adapter preserves the targetless point-resolution occurrence limit form"):
    val result = UsagesCliService.mapReportForTest(
      request,
      terminalReport(
        state = UsagesBySymbolState.Truncated,
        hits = List("TargetResolutionOccurrenceLimit")
      )
    ).getOrElse(fail("Expected a public result"))

    assertEquals(result.state, UsagesPublicState.Truncated)
    assertEquals(result.targetMode, UsagesPublicTargetMode.PointSelected)
    assertEquals(result.target, None)
    assertEquals(result.occurrences, Nil)
    assertEquals(result.limits.hits, List(UsagesPublicLimitHit.TargetResolutionOccurrenceLimit))

  test("adapter fails closed for an unknown internal limit category"):
    val failure = UsagesCliService.mapReportForTest(
      request,
      terminalReport(UsagesBySymbolState.Truncated, List("FutureLimit"))
    ).left.getOrElse(fail("Expected a failure"))

    assertEquals(failure.failureKind, UsagesPublicFailureKind.InternalInvariant)

  test("adapter fails closed for an unknown internal warning category"):
    val globalRequest = request.copy(target = UsagesCliTarget.ExplicitGlobal("a/A#run()."))
    val target = UsageTargetEvidence(
      mode = UsageTargetMode.ExplicitGlobal,
      identityKind = UsageIdentityKind.Global,
      stableSymbol = Some("a/A#run()."),
      localSymbolMarker = None,
      source = None,
      range = None,
      documentEvidenceId = None
    )
    val report = terminalReport(UsagesBySymbolState.CoverageIncomplete, Nil).copy(
      target = Some(target),
      coverage = zeroCoverage.copy(inventoryClosed = false),
      warnings = List("FutureWarning: bounded-subject")
    )
    val failure = UsagesCliService.mapReportForTest(globalRequest, report).left.getOrElse(
      fail("Expected a failure")
    )

    assertEquals(failure.failureKind, UsagesPublicFailureKind.InternalInvariant)

  private def terminalReport(
    state: UsagesBySymbolState,
    hits: List[String]
  ): UsagesBySymbolReport =
    UsagesBySymbolReport(
      state = state,
      target = None,
      occurrences = Nil,
      duplicateGroups = Nil,
      coverage = zeroCoverage,
      limits = limitEvidence(hits),
      warnings = Nil
    )

  private val zeroCoverage = UsageCoverageEvidence(
    inventoryClosed = true,
    declaredSources = 1,
    selectedSources = 1,
    excludedSources = 0,
    declaredArtifacts = 1,
    selectedArtifacts = 1,
    excludedArtifacts = 0,
    rawArtifactBytes = 0,
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
  )

  private def limitEvidence(hits: List[String]): UsageLimitEvidence =
    val limits = UsagesBySymbolLimits.Default
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
      hit = hits
    )
