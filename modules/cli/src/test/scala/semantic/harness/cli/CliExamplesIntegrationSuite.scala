package semantic.harness.cli

import io.circe.parser.decode
import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.core.CompileReport
import semantic.harness.core.TestReport
import semantic.harness.sbt_runner.SbtRunner
import scala.concurrent.duration.*

class CliExamplesIntegrationSuite extends munit.FunSuite:
  override val munitTimeout = 8.minutes

  test("integration: compile --json runs against external example projects"):
    val root = repoRoot

    val success = runCompileJson(
      "compile-success example",
      root.resolve("examples/scala3-compile-success")
    )

    assertResult(success.result.exitCode == 0, s"expected exitCode 0\n${success.diagnostics}")
    assertResult(success.result.stderr.isEmpty, s"expected stderr to be absent\n${success.diagnostics}")
    val successReport = decodeJsonOnlyCompileReport(success)
    assertEquals(successReport.success, true)
    assertEquals(successReport.diagnostics, Nil)

    val failure = runCompileJson(
      "compile-failure example",
      root.resolve("examples/scala3-compile-failure")
    )

    assertResult(failure.result.exitCode == 0, s"expected exitCode 0\n${failure.diagnostics}")
    assertResult(failure.result.stderr.isEmpty, s"expected stderr to be absent\n${failure.diagnostics}")
    val failureReport = decodeJsonOnlyCompileReport(failure)
    assertEquals(failureReport.success, false)
    assert(failureReport.diagnostics.nonEmpty)

  test("integration: selected child compile succeeds while the root aggregate fails"):
    val fixture = selectionFixture

    val root = runCompileJson("multi-project root aggregate", fixture)
    val selected = runCompileJson(
      "selected core2_13 compile",
      fixture,
      List("--sbt-project", "core2_13")
    )

    assertEquals(decodeJsonOnlyCompileReport(root).success, false)
    assertEquals(decodeJsonOnlyCompileReport(selected).success, true)

  test("integration: selected compile failure and errors stay within the requested child"):
    val fixture = selectionFixture

    val compile = runBuildJson(
      "selected compile failure",
      fixture,
      List("compile", "--sbt-project", "compileFail_2", "--json")
    )
    val errors = runBuildJson(
      "selected errors",
      fixture,
      List("errors", "--sbt-project", "compileFail_2", "--json")
    )

    val compileReport = decodeJsonOnlyCompileReport(compile)
    val errorsReport = decodeJsonOnlyCompileReport(errors)
    assertEquals(compileReport.success, false)
    assertEquals(errorsReport.schemaVersion, CompileReport.ErrorsSchemaVersion)
    assertEquals(errorsReport.success, false)
    val messages = (compileReport.diagnostics ++ errorsReport.diagnostics).map(_.message).mkString("\n")
    assert(messages.contains("Broken.scala"), clue(messages))
    assert(!messages.contains("FailingSuite.scala"), clue(messages))

  test("integration: selected project tests preserve pass and failure counts"):
    val fixture = selectionFixture

    val passing = runBuildJson(
      "selected passing tests",
      fixture,
      List("test", "--sbt-project", "core2_13", "--json")
    )
    val failing = runBuildJson(
      "selected failing tests",
      fixture,
      List("test", "--sbt-project", "testFail_2", "--json")
    )

    val passReport = decodeJsonOnlyTestReport(passing)
    val failReport = decodeJsonOnlyTestReport(failing)
    assertEquals(passReport, TestReport(success = true, total = 1, passed = 1, failed = 0, failures = Nil))
    assertEquals(failReport.success, false)
    assertEquals(failReport.total, 2)
    assertEquals(failReport.passed, 1)
    assertEquals(failReport.failed, 1)
    assert(failReport.failures.nonEmpty)

  test("integration: unknown valid project fails closed with bounded sanitized diagnostics"):
    val fixture = selectionFixture
    val run = runCompileJson(
      "unknown selected project",
      fixture,
      List("--sbt-project", "unknownProject_2")
    )

    val report = decodeJsonOnlyCompileReport(run)
    assertEquals(report.success, false)
    assert(report.diagnostics.nonEmpty)
    assert(report.diagnostics.forall(_.message.length <= 1200))
    assert(!report.diagnostics.exists(_.message.contains(fixture.toString)))

  private def runCompileJson(
      label: String,
      cwd: Path,
      options: List[String] = Nil
  ): TimedCliRun =
    runBuildJson(label, cwd, "compile" :: options ::: List("--json"))

  private def runBuildJson(label: String, cwd: Path, args: List[String]): TimedCliRun =
    val start = System.nanoTime()
    val result = CliApp.run(
      args,
      SbtRunner.default,
      cwd
    )
    val elapsed = (System.nanoTime() - start).nanos
    TimedCliRun(label, cwd, result, elapsed)

  private def decodeJsonOnlyCompileReport(run: TimedCliRun): CompileReport =
    val stdout = run.result.stdout.getOrElse(fail(s"expected stdout\n${run.diagnostics}"))
    assertResult(!stdout.linesIterator.exists(_.trim.isEmpty), s"expected JSON-only stdout with no blank lines\n${run.diagnostics}")
    decode[CompileReport](stdout).fold(error => fail(s"stdout was not CompileReport JSON: $error\n${run.diagnostics}"), identity)

  private def decodeJsonOnlyTestReport(run: TimedCliRun): TestReport =
    val stdout = run.result.stdout.getOrElse(fail(s"expected stdout\n${run.diagnostics}"))
    assertResult(!stdout.linesIterator.exists(_.trim.isEmpty), s"expected JSON-only stdout with no blank lines\n${run.diagnostics}")
    decode[TestReport](stdout).fold(error => fail(s"stdout was not TestReport JSON: $error\n${run.diagnostics}"), identity)

  private def repoRoot: Path =
    val root = Path.of("").toAbsolutePath.normalize()
    assert(Files.exists(root.resolve("build.sbt")))
    assert(Files.exists(root.resolve("examples/scala3-compile-success/build.sbt")))
    assert(Files.exists(root.resolve("examples/scala3-compile-failure/build.sbt")))
    root

  private def selectionFixture: Path =
    val fixture = repoRoot.resolve("examples/sbt-multi-project-selection")
    assert(Files.exists(fixture.resolve("build.sbt")))
    fixture

  private final case class TimedCliRun(label: String, cwd: Path, result: CliResult, elapsed: FiniteDuration):
    def diagnostics: String =
      val command = "semantic-scala build-oracle --json"
      s"""$label
         |command: $command
         |cwd: $cwd
         |elapsed: ${elapsed.toMillis} ms
         |exitCode: ${result.exitCode}
         |stdout tail:
         |${tail(result.stdout)}
         |stderr tail:
         |${tail(result.stderr)}
         |""".stripMargin

  private def tail(value: Option[String], maxLines: Int = 20): String =
    value match
      case None => "<none>"
      case Some(text) =>
        val lines = text.linesIterator.toVector
        val selected = lines.takeRight(maxLines)
        if selected.isEmpty then "<empty>"
        else selected.mkString("\n")

  private def assertResult(condition: Boolean, message: => String): Unit =
    if !condition then fail(message)
