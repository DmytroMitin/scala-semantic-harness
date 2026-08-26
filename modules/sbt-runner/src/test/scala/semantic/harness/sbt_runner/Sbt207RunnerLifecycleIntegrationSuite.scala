package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class Sbt207RunnerLifecycleIntegrationSuite extends munit.FunSuite:
  override val munitTimeout = 15.minutes

  test("sbt 2.0.7 selected operations are structured fresh and repeatable"):
    val workspace = copyFixture()
    val passing = project("passing")
    val failing = project("failing")
    val runner = ProcessSbtRunner(120.seconds)
    val classpaths = ProcessSbtClasspathAcquirer(
      120.seconds,
      SbtClasspathProcess.default
    )
    val receipts = ProcessSbtTastyCompileReceiptAcquirer(
      120.seconds,
      SbtTastyCompileProcess.default
    )
    try
      val compileRun = runner.compile(workspace, Some(passing), None)
      val compile = ReportConverter.compileReport(compileRun)
      assertEquals(compile.success, true, clue(compileRun))

      val passingRun = runner.test(workspace, Some(passing), None)
      val passingTests = ReportConverter.testReport(passingRun)
      assertEquals(passingTests.success, true, clue(passingRun))
      assertEquals(passingTests.total, 3)
      assertEquals(passingTests.passed, 2)
      assertEquals(passingTests.failed, 0)

      val failingTests = ReportConverter.testReport(runner.test(workspace, Some(failing), None))
      assertEquals(failingTests.success, false)
      assertEquals(failingTests.total, 1)
      assertEquals(failingTests.passed, 0)
      assertEquals(failingTests.failed, 1)

      val unknown = ReportConverter.compileReport(
        runner.compile(workspace, Some(project("unknownProject")), None)
      )
      assertEquals(unknown.success, false)

      List.fill(3)(SbtClasspathConfiguration.Compile).foreach { configuration =>
        val acquired = classpaths.acquire(SbtClasspathRequest(workspace, passing, configuration))
        assert(acquired.exists(_.entries.nonEmpty), clue(acquired))
      }
      List.fill(3)(SbtClasspathConfiguration.Test).foreach { configuration =>
        val acquired = classpaths.acquire(SbtClasspathRequest(workspace, passing, configuration))
        assert(acquired.exists(_.entries.nonEmpty), clue(acquired))
      }

      val receipt = receipts.acquire(
        SbtTastyCompileRequest(
          workspace,
          passing,
          Path.of("passing/src/main/scala/FixtureValue.scala")
        )
      )
      assertEquals(receipt.map(_.compileStatus), Right(SbtTastyCompileStatus.Succeeded))
      assertEquals(receipt.map(_.sourceIncluded), Right(true))
    finally deleteRecursively(workspace)

  private def copyFixture(): Path =
    val source = Path.of(getClass.getResource("/sbt-2.0.7-runner-lifecycle").toURI)
    val destination = Files.createTempDirectory("sbt-2.0.7-runner-lifecycle-")
    val paths = Files.walk(source)
    try
      paths.iterator().asScala.foreach { path =>
        val target = destination.resolve(source.relativize(path).toString)
        if Files.isDirectory(path) then Files.createDirectories(target)
        else
          Files.createDirectories(target.getParent)
          Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
      }
    finally paths.close()
    destination

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)
