package semantic.harness.semanticdb_reader

import java.io.IOException
import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocuments

class UsagesBySymbolSpikeTask089Suite extends munit.FunSuite:
  import UsagesBySymbolSpikeTestSupport.*

  private val DeadlineLimits = UsagesBySymbolLimits.Default.copy(deadlineNanos = 100L)

  test("expiry during mapped-source accumulation stops before duplicate-source grouping"):
    val base = complexFixture("task089-mapped")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.DuringMappedSourceAccumulation)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringMappedSourceAccumulation), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringDuplicateSourceGrouping), 0)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringFreshnessSummaryAccumulation), 0)

  test("expiry during duplicate-source grouping stops later mapping evidence"):
    val base = complexFixture("task089-duplicate-source")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.DuringDuplicateSourceGrouping)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringDuplicateSourceGrouping), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeUncoveredSourceSorting), 0)

  test("expiry before uncovered-source sorting stops sorting and warning construction"):
    val base = complexFixture("task089-before-uncovered-sort")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.BeforeUncoveredSourceSorting)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeUncoveredSourceSorting), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringUncoveredSourceSorting), 0)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction), 0)

  test("expiry during uncovered-source deterministic sorting stops warning construction"):
    val base = complexFixture("task089-during-uncovered-sort")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.DuringUncoveredSourceSorting, 3)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringUncoveredSourceSorting), 3)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction), 0)

  test("large bounded uncovered-source fixture checks inside warning construction"):
    val base = complexFixture("task089-large-warning", sourceCount = 256)
    val (report, script) = expireReport(
      base.request,
      UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction,
      32
    )

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction), 32)
    assertEquals(report.coverage.totalWarnings, 33)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringFreshnessSummaryAccumulation), 0)

  test("expiry during freshness-summary accumulation stops coverage and scanning"):
    val base = complexFixture("task089-freshness-summary")
    val (report, script) = expireReport(
      base.request,
      UsageDeadlinePhase.DuringFreshnessSummaryAccumulation,
      2
    )

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringFreshnessSummaryAccumulation), 2)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringCoverageConstruction), 0)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeOccurrenceSort), 0)

  test("expiry during coverage construction stops preliminary evidence"):
    val base = complexFixture("task089-coverage")
    val (report, script) = expireReport(base.request, UsageDeadlinePhase.DuringCoverageConstruction)

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringCoverageConstruction), 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeOccurrenceSort), 0)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringPreliminaryEvidenceConstruction), 0)

  test("expiry during preliminary-evidence construction cannot return a completed state"):
    val base = complexFixture("task089-preliminary")
    val (report, script) = expireReport(
      base.request,
      UsageDeadlinePhase.DuringPreliminaryEvidenceConstruction
    )

    assertDeadlineReport(report)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringPreliminaryEvidenceConstruction), 1)
    assert(report.coverage.scannedOrdinaryOccurrences > 0)
    assertEquals(report.occurrences, Nil)

  test("explicit-global expiry in every Task 089 post-document phase is Deadline truncation"):
    val phases = List(
      UsageDeadlinePhase.DuringMappedSourceAccumulation,
      UsageDeadlinePhase.DuringDuplicateSourceGrouping,
      UsageDeadlinePhase.BeforeUncoveredSourceSorting,
      UsageDeadlinePhase.DuringUncoveredSourceSorting,
      UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction,
      UsageDeadlinePhase.DuringFreshnessSummaryAccumulation,
      UsageDeadlinePhase.DuringCoverageConstruction,
      UsageDeadlinePhase.DuringPreliminaryEvidenceConstruction
    )

    phases.zipWithIndex.foreach { case (phase, index) =>
      val base = complexFixture(s"task089-all-phases-$index")
      val report = expireReport(base.request, phase)._1
      assertDeadlineReport(report)
      assertEquals(report.target.flatMap(_.stableSymbol), Some(Target))
    }

  test("uniquely resolved point target expiry in post-document aggregation is Deadline truncation"):
    val base = complexFixture("task089-point-post-document")
    val script = PhaseDeadlineScript(UsageDeadlinePhase.DuringFreshnessSummaryAccumulation)
    val report = successful(pointRequest(base), script, UsagesBySymbolBoundedOperations.Default)

    assertDeadlineReport(report)
    assertEquals(report.target.map(_.mode), Some(UsageTargetMode.PointSelected))
    assertEquals(report.target.flatMap(_.stableSymbol), Some(Target))

  test("repeated scripted post-document expiry produces structurally equal partial results"):
    val base = complexFixture("task089-structural")
    def observed(): UsagesBySymbolReport =
      expireReport(
        base.request,
        UsageDeadlinePhase.DuringUncoveredSourceWarningConstruction,
        4
      )._1

    assertEquals(observed(), observed())

  test("artifact read failure before expiry retains IoFailure"):
    val base = simpleFixture("task089-artifact-failure-before")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.ArtifactRead, () => ())
    val failure = failed(base.request, script, operations)

    assertEquals(failure.kind, UsagesBySymbolFailureKind.IoFailure)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterArtifactReadExceptional), 1)

  test("artifact read crossing expiry then throwing uses deadline precedence and stops"):
    val base = simpleFixture("task089-artifact-failure-deadline")
    val copy = copyArtifact(base.workspace, base.artifact, "out/copy.semanticdb")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.ArtifactRead, () => script.expireNow())
    val report = successful(base.request.copy(artifacts = List(base.artifact, copy)), script, operations)

    assertDeadlineReport(report)
    assertEquals(operations.artifactReads, 1)
    assertEquals(operations.parses, 0)

  test("protobuf parse failure before expiry retains ParseFailure"):
    val base = simpleFixture("task089-parse-failure-before")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.Parse, () => ())
    val failure = failed(base.request, script, operations)

    assertEquals(failure.kind, UsagesBySymbolFailureKind.ParseFailure)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterGroupParseExceptional), 1)

  test("protobuf parse crossing expiry then throwing truncates and stops document work"):
    val base = simpleFixture("task089-parse-failure-deadline")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.Parse, () => script.expireNow())
    val report = successful(base.request, script, operations)

    assertDeadlineReport(report)
    assertEquals(operations.parses, 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforeDocument), 0)

  test("freshness read failure before expiry retains IoFailure"):
    val base = simpleFixture("task089-freshness-failure-before")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.SourceRead, () => ())
    val failure = failed(base.request, script, operations)

    assertEquals(failure.kind, UsagesBySymbolFailureKind.IoFailure)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterFreshnessReadExceptional), 1)

  test("freshness read crossing expiry then throwing truncates before later source work"):
    val base = complexFixture("task089-freshness-failure-deadline")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.SourceRead, () => script.expireNow())
    val report = successful(base.request, script, operations)

    assertDeadlineReport(report)
    assertEquals(operations.sourceReads, 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.DuringFreshnessSummaryAccumulation), 0)

  test("point-source read failure before expiry retains IoFailure"):
    val base = simpleFixture("task089-point-failure-before")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.SourceRead, () => ())
    val failure = failed(pointRequest(base), script, operations)

    assertEquals(failure.kind, UsagesBySymbolFailureKind.IoFailure)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.AfterPointSourceReadExceptional), 1)

  test("point-source read crossing expiry then throwing is TimeoutBeforeTargetResolution"):
    val base = simpleFixture("task089-point-failure-deadline")
    val script = nonExpiringScript()
    val operations = FailingOperations(FailAt.SourceRead, () => script.expireNow())
    val failure = failed(pointRequest(base), script, operations)

    assertEquals(failure.kind, UsagesBySymbolFailureKind.TimeoutBeforeTargetResolution)
    assertEquals(operations.sourceReads, 1)
    assertEquals(script.occurrencesOf(UsageDeadlinePhase.BeforePointDecode), 0)

  test("file-size failure observes deadline before retaining or replacing IoFailure"):
    val beforeBase = simpleFixture("task089-size-failure-before")
    val beforeScript = nonExpiringScript()
    val beforeFailure = failed(
      beforeBase.request,
      beforeScript,
      FailingOperations(FailAt.Size, () => ())
    )
    assertEquals(beforeFailure.kind, UsagesBySymbolFailureKind.IoFailure)
    assertEquals(beforeScript.occurrencesOf(UsageDeadlinePhase.AfterSourceSizeReadExceptional), 1)

    val deadlineBase = simpleFixture("task089-size-failure-deadline")
    val deadlineScript = nonExpiringScript()
    val report = successful(
      deadlineBase.request,
      deadlineScript,
      FailingOperations(FailAt.Size, () => deadlineScript.expireNow())
    )
    assertDeadlineReport(report)

  test("real-path failure observes deadline before retaining or replacing UnsafeFilesystem"):
    val beforeBase = simpleFixture("task089-realpath-failure-before")
    val beforeScript = nonExpiringScript()
    val beforeFailure = failed(
      beforeBase.request,
      beforeScript,
      FailingOperations(FailAt.RealPath, () => ())
    )
    assertEquals(beforeFailure.kind, UsagesBySymbolFailureKind.UnsafeFilesystem)
    assertEquals(beforeScript.occurrencesOf(UsageDeadlinePhase.AfterWorkspaceValidationExceptional), 1)

    val deadlineBase = simpleFixture("task089-realpath-failure-deadline")
    val deadlineScript = nonExpiringScript()
    val report = successful(
      deadlineBase.request,
      deadlineScript,
      FailingOperations(FailAt.RealPath, () => deadlineScript.expireNow())
    )
    assertDeadlineReport(report)

  test("non-expired request preserves the frozen one-definition two-reference baseline"):
    val request = baselineRequest("task089-baseline")
    val script = nonExpiringScript()
    val report = successful(request, script, UsagesBySymbolBoundedOperations.Default)

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(report.occurrences.count(_.role == UsageOccurrenceRole.Definition), 1)
    assertEquals(report.occurrences.count(_.role == UsageOccurrenceRole.Reference), 2)
    assertEquals(report.limits.hit, Nil)

  test("one unchanged absolute deadline is used through Task 089 phases and operations"):
    val base = complexFixture("task089-one-deadline")
    val script = nonExpiringScript()
    val report = successful(base.request, script, UsagesBySymbolBoundedOperations.Default)

    assertEquals(script.starts.toList, List(0L -> 100L))
    assert(!report.limits.hit.contains("Deadline"))

  private final case class Fixture(
    workspace: Path,
    source: DeclaredUsageSource,
    artifact: DeclaredUsageArtifact,
    request: UsagesBySymbolRequest
  )

  private enum FailAt:
    case ArtifactRead
    case Parse
    case SourceRead
    case Size
    case RealPath

  private final class FailingOperations(
    failAt: FailAt,
    crossOrObserve: () => Unit
  ) extends UsagesBySymbolBoundedOperations:
    private val delegate = UsagesBySymbolBoundedOperations.Default
    var artifactReads = 0
    var sourceReads = 0
    var parses = 0

    def readAllBytes(path: Path): Array[Byte] =
      if path.toString.endsWith(".semanticdb") then
        artifactReads += 1
        if failAt == FailAt.ArtifactRead then fail()
      else
        sourceReads += 1
        if failAt == FailAt.SourceRead then fail()
      delegate.readAllBytes(path)

    def parseTextDocuments(bytes: Array[Byte]): TextDocuments =
      parses += 1
      if failAt == FailAt.Parse then fail()
      delegate.parseTextDocuments(bytes)

    def size(path: Path): Long =
      if failAt == FailAt.Size then fail()
      delegate.size(path)

    def realPath(path: Path): Path =
      if failAt == FailAt.RealPath then fail()
      delegate.realPath(path)

    private def fail(): Nothing =
      crossOrObserve()
      throw IOException("scripted bounded-operation failure")

  private def simpleFixture(label: String): Fixture =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val text = "object A\n"
    val source = writeSource(workspace, "module-a/src/main/scala/example/A.scala", text)
    val artifact = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(document(source.path, text, Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1)))),
      source.module,
      source.sourceSet
    )
    Fixture(workspace, source, artifact, request(workspace, List(source), List(artifact)))

  private def complexFixture(label: String, sourceCount: Int = 32): Fixture =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val sources = (0 until sourceCount).toList.map { index =>
      writeSource(
        workspace,
        f"module-a/src/main/scala/example/Source$index%04d.scala",
        s"object Source$index\n"
      )
    }
    val first = sources.head
    val text = "object Source0\n"
    val artifact = writeArtifact(
      workspace,
      "out/complex.semanticdb",
      Seq(
        document(
          first.path,
          text,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        ),
        document(first.path, text, Nil)
      ),
      first.module,
      first.sourceSet
    )
    Fixture(workspace, first, artifact, request(workspace, sources, List(artifact)))

  private def pointRequest(base: Fixture): UsagesBySymbolRequest =
    base.request.copy(
      target = UsagesBySymbolTarget.Point(
        source = base.source.path,
        line = 1,
        column = 1,
        semanticdb = base.artifact.path
      )
    )

  private def baselineRequest(label: String): UsagesBySymbolRequest =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val entries = List(
      ("module-a/src/main/scala/example/Definitions.scala", "object Definitions\n", SymbolOccurrence.Role.DEFINITION),
      ("module-b/src/main/scala/example/Uses.scala", "object Uses\n", SymbolOccurrence.Role.REFERENCE),
      ("module-b/src/test/scala/example/ConsumerSpec.scala", "object ConsumerSpec\n", SymbolOccurrence.Role.REFERENCE)
    )
    val sourceArtifacts = entries.zipWithIndex.map { case ((path, text, role), index) =>
      val source = writeSource(workspace, path, text)
      val artifact = writeArtifact(
        workspace,
        s"out/baseline-$index.semanticdb",
        Seq(document(source.path, text, Seq(occurrence(Target, role, 0, 0, 1)))),
        source.module,
        source.sourceSet
      )
      source -> artifact
    }
    request(workspace, sourceArtifacts.map(_._1), sourceArtifacts.map(_._2))

  private def expireReport(
    request: UsagesBySymbolRequest,
    phase: UsageDeadlinePhase,
    occurrence: Int = 1
  ): (UsagesBySymbolReport, PhaseDeadlineScript) =
    val script = PhaseDeadlineScript(phase, occurrence)
    successful(request, script, UsagesBySymbolBoundedOperations.Default) -> script

  private def nonExpiringScript(): PhaseDeadlineScript =
    PhaseDeadlineScript(UsageDeadlinePhase.BeforeFinalReport, expireAtOccurrence = 99)

  private def successful(
    request: UsagesBySymbolRequest,
    script: PhaseDeadlineScript,
    operations: UsagesBySymbolBoundedOperations
  ): UsagesBySymbolReport =
    UsagesBySymbolSpike
      .run(request, DeadlineLimits, script.clock, script, operations)
      .fold(value => fail(s"Unexpected failure: $value"), identity)

  private def failed(
    request: UsagesBySymbolRequest,
    script: PhaseDeadlineScript,
    operations: UsagesBySymbolBoundedOperations
  ): UsagesBySymbolFailure =
    UsagesBySymbolSpike
      .run(request, DeadlineLimits, script.clock, script, operations)
      .swap
      .fold(value => fail(s"Expected failure, got: $value"), identity)

  private def assertDeadlineReport(report: UsagesBySymbolReport): Unit =
    assertEquals(report.state, UsagesBySymbolState.Truncated)
    assert(report.limits.hit.contains("Deadline"))
