package semantic.harness.cli

import io.circe.parser.decode
import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.core.CompileReport
import semantic.harness.sbt_runner.SbtRunner
import scala.concurrent.duration.*

class CliExamplesIntegrationSuite extends munit.FunSuite:
  override val munitTimeout = 3.minutes

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

  private def runCompileJson(label: String, cwd: Path): TimedCliRun =
    val start = System.nanoTime()
    val result = CliApp.run(
      List("compile", "--json"),
      SbtRunner.default,
      cwd
    )
    val elapsed = (System.nanoTime() - start).nanos
    TimedCliRun(label, cwd, result, elapsed)

  private def decodeJsonOnlyCompileReport(run: TimedCliRun): CompileReport =
    val stdout = run.result.stdout.getOrElse(fail(s"expected stdout\n${run.diagnostics}"))
    assertResult(!stdout.linesIterator.exists(_.trim.isEmpty), s"expected JSON-only stdout with no blank lines\n${run.diagnostics}")
    decode[CompileReport](stdout).fold(error => fail(s"stdout was not CompileReport JSON: $error\n${run.diagnostics}"), identity)

  private def repoRoot: Path =
    val root = Path.of("").toAbsolutePath.normalize()
    assert(Files.exists(root.resolve("build.sbt")))
    assert(Files.exists(root.resolve("examples/scala3-compile-success/build.sbt")))
    assert(Files.exists(root.resolve("examples/scala3-compile-failure/build.sbt")))
    root

  private final case class TimedCliRun(label: String, cwd: Path, result: CliResult, elapsed: FiniteDuration):
    def diagnostics: String =
      val command = "semantic-scala compile --json"
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
