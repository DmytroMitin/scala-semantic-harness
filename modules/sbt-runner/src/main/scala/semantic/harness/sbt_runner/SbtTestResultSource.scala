package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

private[sbt_runner] final case class SbtTestCounts(
    total: Int,
    passed: Int,
    failures: Int,
    errors: Int,
    skipped: Int
)

private[sbt_runner] object SbtTestCounts:
  val Zero: SbtTestCounts = SbtTestCounts(0, 0, 0, 0, 0)

private[sbt_runner] final case class SbtStructuredTestResult(
    success: Boolean,
    counts: SbtTestCounts
)

private[sbt_runner] object SbtTestResultSource:
  val CompletionFormat = "semantic-scala.internal-sbt-test-completion.v1"
  val CompletionEnvironment = "SEMANTIC_SCALA_SBT_TEST_COMPLETION"
  val Task = "semanticScalaInternalStructuredTest"

  private val MaxCompletionBytes = 512

  val GlobalSettings: String =
    s"""@transient val $Task = taskKey[Unit]("Run Test and export one structured result")
       |
       |$Task := {
       |  val testOutput = (Test / executeTests).value
       |  val suites = testOutput.events.values.toSeq
       |  val passed = suites.map(_.passedCount.toLong).sum
       |  val failures = suites.map(_.failureCount.toLong).sum
       |  val errors = suites.map(_.errorCount.toLong).sum
       |  val skipped = suites.map { suite =>
       |    suite.skippedCount.toLong + suite.ignoredCount.toLong +
       |      suite.canceledCount.toLong + suite.pendingCount.toLong
       |  }.sum
       |  val total = passed + failures + errors + skipped
       |  val succeeded =
       |    testOutput.overall != sbt.protocol.testing.TestResult.Failed &&
       |      testOutput.overall != sbt.protocol.testing.TestResult.Error
       |  IO.writeLines(
       |    file(sys.env("$CompletionEnvironment")),
       |    Seq(
       |      "$CompletionFormat",
       |      s"success\t$$succeeded",
       |      s"total\t$$total",
       |      s"passed\t$$passed",
       |      s"failures\t$$failures",
       |      s"errors\t$$errors",
       |      s"skipped\t$$skipped"
       |    )
       |  )
       |}
       |""".stripMargin

  def read(completionFile: Path): Either[String, SbtStructuredTestResult] =
    if !Files.isRegularFile(completionFile, LinkOption.NOFOLLOW_LINKS) then
      Left("Invalid structured sbt test result: completion file was not created")
    else if Files.size(completionFile) > MaxCompletionBytes then
      Left("Invalid structured sbt test result: completion protocol exceeds byte limit")
    else
      val lines = Files.readAllLines(completionFile, StandardCharsets.UTF_8).asScala.toList
      lines match
        case format :: successLine :: totalLine :: passedLine :: failuresLine :: errorsLine ::
            skippedLine :: Nil if format == CompletionFormat =>
          for
            success <- parseSuccess(successLine)
            total <- parseCount("total", totalLine)
            passed <- parseCount("passed", passedLine)
            failures <- parseCount("failures", failuresLine)
            errors <- parseCount("errors", errorsLine)
            skipped <- parseCount("skipped", skippedLine)
            counts <- validateCounts(total, passed, failures, errors, skipped)
          yield SbtStructuredTestResult(success, counts)
        case _ => Left("Invalid structured sbt test result: invalid completion protocol")

  private def parseSuccess(line: String): Either[String, Boolean] =
    line.split("\\t", -1).toList match
      case "success" :: "true" :: Nil  => Right(true)
      case "success" :: "false" :: Nil => Right(false)
      case _ => Left("Invalid structured sbt test result: invalid success record")

  private def parseCount(name: String, line: String): Either[String, Int] =
    line.split("\\t", -1).toList match
      case key :: value :: Nil if key == name =>
        Try(value.toInt).toEither.left
          .map(_ => s"Invalid structured sbt test result: invalid $name count")
          .flatMap { count =>
            if count >= 0 then Right(count)
            else Left(s"Invalid structured sbt test result: invalid $name count")
          }
      case _ => Left(s"Invalid structured sbt test result: invalid $name record")

  private def validateCounts(
      total: Int,
      passed: Int,
      failures: Int,
      errors: Int,
      skipped: Int
  ): Either[String, SbtTestCounts] =
    Try(Math.addExact(Math.addExact(passed, failures), Math.addExact(errors, skipped))).toEither.left
      .map(_ => "Invalid structured sbt test result: count overflow")
      .flatMap { computedTotal =>
        if computedTotal == total then
          Right(SbtTestCounts(total, passed, failures, errors, skipped))
        else Left("Invalid structured sbt test result: inconsistent counts")
      }
