package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolOccurrence

class UsagesBySymbolSpikeDeadlineSuite extends munit.FunSuite:
  import UsagesBySymbolSpikeTestSupport.*

  private val DeadlineLimits = UsagesBySymbolLimits.Default.copy(deadlineNanos = 100L)

  test("expiry during source declaration preparation stops before artifact preparation"):
    val base = fixture("deadline-source-preparation")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeSourcePreparation)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeSourcePreparation), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeArtifactPreparation), 0)

  test("expiry during artifact declaration preparation stops before artifact path work"):
    val base = fixture("deadline-artifact-preparation")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeArtifactPreparation)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeArtifactPreparation), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeArtifactPathValidation), 0)

  test("explicit global expiry inside preparation is post-resolution Truncated evidence"):
    val base = fixture("deadline-explicit-preparation")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeSourcePathValidation)

    assertDeadlineReport(report)
    assertEquals(report.target.flatMap(_.stableSymbol), Some(Target))
    assertEquals(script.starts.size, 1)

  test("point-target expiry before target resolution is a typed operational timeout"):
    val base = fixture("deadline-point-pre-resolution")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.BeforeArtifactRead)
    val result = UsagesBySymbolSpike.run(
      pointRequest(base),
      DeadlineLimits,
      script.clock,
      script
    )

    val failure = result.left.getOrElse(fail("Expected point-target timeout"))
    assertEquals(failure.kind, UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeArtifactRead), 1)

  test("expiry during artifact read and hash iteration performs no later artifact read"):
    val base = fixture("deadline-artifact-read")
    val copy = copyArtifact(base.workspace, base.artifact, "out/copy.semanticdb")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterArtifactHash)
    val report = run(
      base.request.copy(artifacts = List(base.artifact, copy)),
      DeadlineLimits,
      script.clock,
      script
    )

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeArtifactRead), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterArtifactHash), 1)

  test("expiry immediately after protobuf parsing is deterministic truncation"):
    val base = fixture("deadline-protobuf-parse")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.AfterGroupParse)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeGroupParse), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterGroupParse), 1)

  test("point-source read and coordinate processing remain pre-resolution"):
    val base = fixture("deadline-point-source")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterPointSourceRead)
    val result = UsagesBySymbolSpike.run(
      pointRequest(base),
      DeadlineLimits,
      script.clock,
      script
    )

    val failure = result.left.getOrElse(fail("Expected point-source timeout"))
    assertEquals(failure.kind, UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterPointSourceRead), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforePointDecode), 0)

  test("expiry after point coordinate conversion remains a pre-resolution timeout"):
    val base = fixture("deadline-point-coordinate")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterPointCoordinateConversion)
    val result = UsagesBySymbolSpike.run(
      pointRequest(base),
      DeadlineLimits,
      script.clock,
      script
    )

    val failure = result.left.getOrElse(fail("Expected point-coordinate timeout"))
    assertEquals(failure.kind, UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterPointCoordinateConversion), 1)

  test("expiry after unique point resolution is post-resolution Truncated evidence"):
    val base = fixture("deadline-point-resolved")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterPointTargetResolution)
    val report = run(pointRequest(base), DeadlineLimits, script.clock, script)

    assertDeadlineReport(report)
    assertEquals(report.target.map(_.mode), Some(UsageTargetMode.PointSelected))
    assertEquals(report.target.flatMap(_.stableSymbol), Some(Target))

  test("expiry during raw duplicate grouping stops before protobuf parsing"):
    val base = fixture("deadline-artifact-grouping")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.DuringArtifactGrouping)
    val report = run(base.request, DeadlineLimits, script.clock, script)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringArtifactGrouping), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeGroupParse), 0)

  test("expiry during document counting stops before document mapping"):
    val base = fixture("deadline-document-counting")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.DuringDocumentCounting)
    val report = run(base.request, DeadlineLimits, script.clock, script)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringDocumentCounting), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeDocument), 0)

  test("expiry after duplicate-evidence sorting prevents evidence construction"):
    val base = fixture("deadline-duplicate-evidence")
    val copy = copyArtifact(base.workspace, base.artifact, "out/copy.semanticdb")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterDuplicateEvidenceSort)
    val report = run(
      base.request.copy(artifacts = List(base.artifact, copy)),
      DeadlineLimits,
      script.clock,
      script
    )

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeDuplicateEvidence), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterDuplicateEvidenceSort), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringDuplicateEvidence), 0)
    assertEquals(report.duplicateGroups, Nil)

  test("expiry during document iteration stops before freshness"):
    val base = fixture("deadline-document-iteration")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeDocument)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeDocument), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeFreshnessRead), 0)

  test("expiry immediately after a freshness source read stops before digest"):
    val base = fixture("deadline-after-freshness-read")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.AfterFreshnessRead)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterFreshnessRead), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeFreshnessDigest), 0)

  test("expiry between multiple freshness reads proves no later source is read"):
    val (request, _) = twoDocumentRequest("deadline-between-freshness")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterFreshnessRead)
    val report = run(request, DeadlineLimits, script.clock, script)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeFreshnessRead), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterFreshnessRead), 1)

  test("expiry during occurrence scanning preserves deterministic partial truncation"):
    val base = fixture("deadline-occurrence-scan", occurrenceCount = 3)
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.DuringOccurrenceScan, 2)

    assertDeadlineReport(report)
    assertEquals(report.coverage.scannedOrdinaryOccurrences, 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringOccurrenceScan), 2)

  test("expiry before returned occurrence evidence stops evidence construction"):
    val base = fixture("deadline-occurrence-evidence")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeOccurrenceEvidence)

    assertDeadlineReport(report)
    assertEquals(report.coverage.scannedOrdinaryOccurrences, 1)
    assertEquals(report.occurrences, Nil)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeOccurrenceEvidence), 1)

  test("expiry during output bounding prevents additional evidence construction"):
    val base = fixture("deadline-output-bound", occurrenceCount = 3)
    val script = PhaseDeadlineScript(UsageDeadlinePhase.DuringOutputBounding)
    val report = run(
      base.request,
      DeadlineLimits.copy(maxResultEvidenceBytes = 900),
      script.clock,
      script
    )

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringOutputBounding), 1)
    assertEquals(report.occurrences, Nil)

  test("a non-expired request preserves one definition and two references"):
    val (request, observer) = baselineRequest("deadline-baseline")
    val report = run(request, DeadlineLimits, observer.clock, observer)

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(report.occurrences.size, 3)
    assertEquals(report.occurrences.count(_.role == UsageOccurrenceRole.Definition), 1)
    assertEquals(report.occurrences.count(_.role == UsageOccurrenceRole.Reference), 2)
    assertEquals(report.limits.hit, Nil)

  test("the same scripted expiry is structurally equal across repeated runs"):
    val base = fixture("deadline-structural-equality", occurrenceCount = 3)
    def observed(): UsagesBySymbolReport =
      val script = PhaseDeadlineScript(UsageDeadlinePhase.DuringOccurrenceScan, 2)
      run(base.request, DeadlineLimits, script.clock, script)

    assertEquals(observed(), observed())

  test("one request starts exactly one absolute deadline and never extends it"):
    val base = fixture("deadline-single-context")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterOutputBounding, expireAtOccurrence = 99)
    val report = run(base.request, DeadlineLimits, script.clock, script)

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(script.starts.toList, List(0L -> 100L))

  test("expiry before final report construction cannot return a completed state"):
    val base = fixture("deadline-final-report")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeFinalReport)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeFinalReport), 1)

  test("an operational parse failure discovered before expiry retains precedence"):
    val base = fixture("deadline-parse-precedence")
    Files.write(base.workspace.resolve(base.artifact.path), Array[Byte](1, 2, 3))
    val script = PhaseDeadlineScript(UsageDeadlinePhase.AfterGroupParse)
    val result = UsagesBySymbolSpike.run(base.request, DeadlineLimits, script.clock, script)

    val failure = result.left.getOrElse(fail("Expected parse failure"))
    assertEquals(failure.kind, UsagesBySymbolFailureKind.ParseFailure)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterGroupParse), 0)

  private final case class Fixture(
    workspace: Path,
    source: DeclaredUsageSource,
    artifact: DeclaredUsageArtifact,
    request: UsagesBySymbolRequest
  )

  private def fixture(label: String, occurrenceCount: Int = 1): Fixture =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val text = "object A\n"
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      text
    )
    val occurrences =
      (0 until occurrenceCount).map(index =>
        occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, index, index + 1)
      )
    val artifact = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(document(source.path, text, occurrences)),
      source.module,
      source.sourceSet
    )
    Fixture(
      workspace,
      source,
      artifact,
      request(workspace, List(source), List(artifact))
    )

  private def pointRequest(base: Fixture): UsagesBySymbolRequest =
    base.request.copy(
      target = UsagesBySymbolTarget.Point(
        source = base.source.path,
        line = 1,
        column = 1,
        semanticdb = base.artifact.path
      )
    )

  private def expireReport(
    request: UsagesBySymbolRequest,
    phase: UsageDeadlinePhase,
    occurrence: Int = 1
  ): (UsagesBySymbolReport, PhaseDeadlineScript) =
    val script = PhaseDeadlineScript(phase, occurrence)
    run(request, DeadlineLimits, script.clock, script) -> script

  private def assertDeadlineReport(report: UsagesBySymbolReport): Unit =
    assertEquals(report.state, UsagesBySymbolState.Truncated)
    assert(report.limits.hit.contains("Deadline"))

  private def twoDocumentRequest(
    label: String
  ): (UsagesBySymbolRequest, List[DeclaredUsageSource]) =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val firstText = "object A\n"
    val secondText = "object B\n"
    val first = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      firstText
    )
    val second = writeSource(
      workspace,
      "module-a/src/main/scala/example/B.scala",
      secondText
    )
    val artifact = writeArtifact(
      workspace,
      "out/multi.semanticdb",
      Seq(
        document(
          first.path,
          firstText,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        ),
        document(
          second.path,
          secondText,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      first.module,
      first.sourceSet
    )
    request(workspace, List(first, second), List(artifact)) -> List(first, second)

  private def baselineRequest(
    label: String
  ): (UsagesBySymbolRequest, PhaseDeadlineScript) =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val definitionsText = "object Definitions\n"
    val mainText = "object Uses\n"
    val testText = "object ConsumerSpec\n"
    val definitions = writeSource(
      workspace,
      "module-a/src/main/scala/example/Definitions.scala",
      definitionsText
    )
    val main = writeSource(
      workspace,
      "module-b/src/main/scala/example/Uses.scala",
      mainText
    )
    val test = writeSource(
      workspace,
      "module-b/src/test/scala/example/ConsumerSpec.scala",
      testText
    )
    val artifacts = List(
      writeArtifact(
        workspace,
        "out/definitions.semanticdb",
        Seq(
          document(
            definitions.path,
            definitionsText,
            Seq(occurrence(Target, SymbolOccurrence.Role.DEFINITION, 0, 0, 1))
          )
        ),
        definitions.module,
        definitions.sourceSet
      ),
      writeArtifact(
        workspace,
        "out/main.semanticdb",
        Seq(
          document(
            main.path,
            mainText,
            Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
          )
        ),
        main.module,
        main.sourceSet
      ),
      writeArtifact(
        workspace,
        "out/test.semanticdb",
        Seq(
          document(
            test.path,
            testText,
            Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
          )
        ),
        test.module,
        test.sourceSet
      )
    )
    val observer =
      PhaseDeadlineScript(UsageDeadlinePhase.AfterOutputBounding, expireAtOccurrence = 99)
    request(workspace, List(definitions, main, test), artifacts) -> observer
