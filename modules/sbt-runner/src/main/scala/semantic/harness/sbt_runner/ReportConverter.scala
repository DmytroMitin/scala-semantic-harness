package semantic.harness.sbt_runner

import semantic.harness.core.CompileReport
import semantic.harness.core.Diagnostic
import semantic.harness.core.DiagnosticPosition
import semantic.harness.core.TestReport

object ReportConverter:
  private val MaxDiagnosticLength = 1200
  private val PositionPattern = raw"([^\s]+\.scala):(\d+):(\d+)".r
  private val TestCountsPattern =
    raw".*Total\s+(\d+),\s+Failed\s+(\d+),\s+Errors\s+(\d+),\s+Passed\s+(\d+).*".r

  def compileReport(result: SbtRunResult): CompileReport =
    if result.exitCode == 0 then
      CompileReport(success = true, diagnostics = Nil)
    else
      CompileReport(
        success = false,
        diagnostics = diagnostics(result, "compile failed")
      )

  def testReport(result: SbtRunResult): TestReport =
    val counts = testCounts(result.combinedOutput)
    if result.exitCode == 0 then
      TestReport(
        success = true,
        total = counts.map(_.total).getOrElse(0),
        passed = counts.map(_.passed).getOrElse(0),
        failed = counts.map(_.failed).getOrElse(0),
        failures = Nil
      )
    else
      TestReport(
        success = false,
        total = counts.map(_.total).getOrElse(0),
        passed = counts.map(_.passed).getOrElse(0),
        failed = counts.map(c => c.failed + c.errors).getOrElse(0),
        failures = diagnostics(result, "test failed")
      )

  private def diagnostics(result: SbtRunResult, fallback: String): List[Diagnostic] =
    val lines = relevantLines(result.combinedOutput)
    val message =
      if lines.nonEmpty then lines.mkString("\n")
      else s"$fallback: sbt exited with code ${result.exitCode}"

    List(
      Diagnostic(
        severity = "error",
        message = truncate(message),
        position = firstPosition(lines)
      )
    )

  private def relevantLines(output: String): List[String] =
    output
      .linesIterator
      .map(stripSbtPrefix)
      .map(_.trim)
      .filter(_.nonEmpty)
      .filter(line =>
        line.contains("error") ||
          line.contains("Error") ||
          line.contains("failed") ||
          line.contains("Failed") ||
          line.contains(".scala:")
      )
      .take(20)
      .toList

  private def firstPosition(lines: List[String]): Option[DiagnosticPosition] =
    lines.iterator.flatMap(line => positionFromLine(line)).toSeq.headOption

  private def positionFromLine(line: String): Option[DiagnosticPosition] =
    PositionPattern.findFirstMatchIn(line).map { matchResult =>
      DiagnosticPosition(
        file = matchResult.group(1),
        line = matchResult.group(2).toInt,
        column = matchResult.group(3).toInt
      )
    }

  private def testCounts(output: String): Option[TestCounts] =
    output
      .linesIterator
      .flatMap {
        case TestCountsPattern(total, failed, errors, passed) =>
          Some(TestCounts(total.toInt, failed.toInt, errors.toInt, passed.toInt))
        case _ =>
          None
      }
      .toSeq
      .lastOption

  private def stripSbtPrefix(line: String): String =
    line
      .stripPrefix("[error]")
      .stripPrefix("[warn]")
      .stripPrefix("[info]")
      .trim

  private def truncate(message: String): String =
    if message.length <= MaxDiagnosticLength then message
    else message.take(MaxDiagnosticLength) + "\n... truncated ..."

  private final case class TestCounts(
    total: Int,
    failed: Int,
    errors: Int,
    passed: Int
  )

extension (result: SbtRunResult)
  private def combinedOutput: String =
    List(result.stdout, result.stderr).filter(_.nonEmpty).mkString("\n")
