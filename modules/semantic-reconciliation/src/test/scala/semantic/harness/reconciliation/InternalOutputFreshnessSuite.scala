package semantic.harness.reconciliation

import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.sbt_runner.SbtInternalSourceLayoutReceipt

class InternalOutputFreshnessSuite extends munit.FunSuite:
  test("multiple eligible internals use one batch and retain only bounded worker results"):
    val fixture = freshnessFixture("worker-batch")
    try
      val runtime = RecordingRuntime(Right(Map(
        "a" -> assessment(InternalOutputFreshnessStatusV6.Fresh, InternalOutputFreshnessReasonV6.SourceAndProductContentMatch),
        "b" -> assessment(InternalOutputFreshnessStatusV6.Stale, InternalOutputFreshnessReasonV6.SourceContentMismatch)
      )))
      val assessor = InternalOutputFreshnessAssessor.withRuntime(runtime)
      val results = assessor.assessBatch(List(request("a", fixture), request("b", fixture)))

      assertEquals(runtime.calls, 1)
      assertEquals(runtime.batchSizes, List(2))
      assertEquals(results("a").status, InternalOutputFreshnessStatusV6.Fresh)
      assertEquals(results("b").status, InternalOutputFreshnessStatusV6.Stale)
      assertEquals(results("a").analysisFile, Some("producer/target/zinc/inc_compile.zip"))
    finally deleteRecursively(fixture.workspace)

  test("runtime failure and partial batch response fail every eligible internal closed"):
    val fixture = freshnessFixture("worker-failure")
    try
      val failed = InternalOutputFreshnessAssessor.withRuntime(RecordingRuntime(Left("offline")))
        .assessBatch(List(request("a", fixture), request("b", fixture)))
      assert(failed.values.forall(value =>
        value.status == InternalOutputFreshnessStatusV6.Unverifiable &&
          value.reason == InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion
      ))

      val partial = InternalOutputFreshnessAssessor.withRuntime(RecordingRuntime(Right(Map(
        "a" -> assessment(InternalOutputFreshnessStatusV6.Fresh, InternalOutputFreshnessReasonV6.SourceAndProductContentMatch)
      )))).assessBatch(List(request("a", fixture), request("b", fixture)))
      assert(partial.values.forall(_.status == InternalOutputFreshnessStatusV6.Unverifiable))

      val throwing = InternalOutputFreshnessAssessor.withRuntime(new ZincFreshnessWorkerRuntime:
        override def assess(inputs: List[ZincFreshnessWorkerInput]) =
          throw IllegalStateException("cleanup failed")
      ).assessBatch(List(request("a", fixture), request("b", fixture)))
      assert(throwing.values.forall(value =>
        value.status == InternalOutputFreshnessStatusV6.Unverifiable &&
          value.reason == InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion
      ))
    finally deleteRecursively(fixture.workspace)

  test("missing analysis and absent classes are classified without resolving or launching a worker"):
    val fixture = freshnessFixture("worker-precheck")
    try
      val runtime = RecordingRuntime(Left("must not be called"))
      val assessor = InternalOutputFreshnessAssessor.withRuntime(runtime)
      val missingAnalysis = assessor.assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.workspace.resolve("missing.zip")),
        Some(fixture.layout)
      )
      assertEquals(
        missingAnalysis.status -> missingAnalysis.reason,
        InternalOutputFreshnessStatusV6.Unverifiable -> InternalOutputFreshnessReasonV6.AnalysisFileMissing
      )

      val absentClasses = assessor.assess(
        fixture.workspace,
        "2.13.18",
        fixture.workspace.resolve("missing-classes"),
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      assertEquals(absentClasses.reason, InternalOutputFreshnessReasonV6.DependencyClassDirectoryAbsent)
      assertEquals(runtime.calls, 0)
    finally deleteRecursively(fixture.workspace)

  private final case class FreshnessFixture(
      workspace: Path,
      classes: Path,
      analysis: Path,
      layout: SbtInternalSourceLayoutReceipt
  )

  private final case class RecordingRuntime(
      result: Either[String, Map[String, InternalOutputFreshnessAssessment]]
  ) extends ZincFreshnessWorkerRuntime:
    var calls = 0
    var batchSizes = List.empty[Int]
    override def assess(
        inputs: List[ZincFreshnessWorkerInput]
    ): Either[String, Map[String, InternalOutputFreshnessAssessment]] =
      calls += 1
      batchSizes = batchSizes :+ inputs.size
      result

  private def freshnessFixture(prefix: String): FreshnessFixture =
    val workspace = Files.createTempDirectory(prefix)
    val classes = Files.createDirectories(workspace.resolve("producer/target/classes"))
    val analysis = Files.createDirectories(workspace.resolve("producer/target/zinc"))
      .resolve("inc_compile.zip")
    Files.write(analysis, Array[Byte](1, 2, 3))
    val unmanaged = Files.createDirectories(workspace.resolve("producer/src/main/scala"))
    val managed = workspace.resolve("producer/target/src_managed/main")
    FreshnessFixture(workspace, classes, analysis, SbtInternalSourceLayoutReceipt(
      sourceDirectories = List(unmanaged, managed),
      unmanagedSourceDirectories = List(unmanaged),
      managedSourceDirectories = List(managed),
      sourceGeneratorCount = 0
    ))

  private def request(id: String, fixture: FreshnessFixture): InternalOutputFreshnessRequest =
    InternalOutputFreshnessRequest(
      id,
      fixture.workspace,
      "2.13.18",
      fixture.classes,
      Some(fixture.analysis),
      Some(fixture.layout)
    )

  private def assessment(
      status: InternalOutputFreshnessStatusV6,
      reason: InternalOutputFreshnessReasonV6
  ): InternalOutputFreshnessAssessment = InternalOutputFreshnessAssessment(
    status,
    reason,
    None,
    Some(1),
    Some(1)
  )

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
