package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait SbtClasspathAcquirer:
  def acquire(request: SbtClasspathRequest): Either[SbtClasspathFailure, SbtClasspathResult]

object SbtClasspathAcquirer:
  val DefaultTimeout: FiniteDuration = 180.seconds
  val default: SbtClasspathAcquirer =
    ProcessSbtClasspathAcquirer(DefaultTimeout, SbtClasspathProcess.default)

private[sbt_runner] final case class ProcessSbtClasspathAcquirer(
    timeout: FiniteDuration,
    process: SbtClasspathProcess
) extends SbtClasspathAcquirer:
  override def acquire(
      request: SbtClasspathRequest
  ): Either[SbtClasspathFailure, SbtClasspathResult] =
    SbtClasspathRequest.validate(request).left.map(SbtClasspathFailure.Validation.apply).flatMap {
      validated =>
        withTemporaryProtocol(validated)
    }

  private def withTemporaryProtocol(
      request: SbtClasspathRequest
  ): Either[SbtClasspathFailure, SbtClasspathResult] =
    val temporaryRoot = Files.createTempDirectory("semantic-scala-sbt-classpath-")
    val globalBase = Files.createDirectory(temporaryRoot.resolve("global"))
    val resultFile = temporaryRoot.resolve("classpath.protocol")
    try
      Files.writeString(
        globalBase.resolve("global.sbt"),
        SbtClasspathInjection.GlobalSettings,
        StandardCharsets.UTF_8
      )
      val task = request.configuration match
        case SbtClasspathConfiguration.Compile =>
          SbtClasspathInjection.CompileTask
        case SbtClasspathConfiguration.Test =>
          SbtClasspathInjection.TestTask
      process.run(request, globalBase, resultFile, task, timeout) match
        case SbtClasspathProcessOutcome.Completed(result) if result.exitCode == 0 =>
          if Files.isRegularFile(resultFile) then
            SbtClasspathProtocol.parse(Files.readString(resultFile, StandardCharsets.UTF_8), request)
          else
            Left(
              SbtClasspathFailure.Protocol(
                "Invalid sbt classpath protocol: result file was not created"
              )
            )
        case SbtClasspathProcessOutcome.Completed(result) =>
          Left(SbtClasspathFailure.Process(processFailure(request, result)))
        case SbtClasspathProcessOutcome.TimedOut(stdout, stderr) =>
          val excerpt = diagnosticExcerpt(stdout, stderr)
          Left(
            SbtClasspathFailure.Process(
              s"Unable to acquire sbt classpath for project '${request.project.value}' " +
                s"configuration '${SbtClasspathConfiguration.value(request.configuration)}': " +
                s"sbt exceeded the ${timeout.toSeconds}-second timeout${suffix(excerpt)}"
            )
          )
        case SbtClasspathProcessOutcome.FailedToStart(message) =>
          Left(
            SbtClasspathFailure.Process(
              s"Unable to acquire sbt classpath for project '${request.project.value}' " +
                s"configuration '${SbtClasspathConfiguration.value(request.configuration)}': " +
                s"unable to start sbt${suffix(Option(message).map(_.trim).filter(_.nonEmpty))}"
            )
          )
    finally deleteRecursively(temporaryRoot)

  private def processFailure(
      request: SbtClasspathRequest,
      result: SbtRunResult
  ): String =
    val excerpt = diagnosticExcerpt(result.stdout, result.stderr)
    s"Unable to acquire sbt classpath for project '${request.project.value}' " +
      s"configuration '${SbtClasspathConfiguration.value(request.configuration)}': " +
      s"sbt exited with code ${result.exitCode}${suffix(excerpt)}"

  private def diagnosticExcerpt(stdout: String, stderr: String): Option[String] =
    val lines = List(stdout, stderr)
      .filter(_.nonEmpty)
      .flatMap(_.linesIterator)
      .map(_.trim)
      .filter(_.nonEmpty)
    val relevant = lines.filter(line =>
      line.startsWith("[error]") ||
        line.contains("error") ||
        line.contains("Error") ||
        line.contains("failed") ||
        line.contains("Failed")
    )
    val selected = (if relevant.nonEmpty then relevant else lines.takeRight(8)).take(8)
    val text = selected.mkString("\n")
    Option(if text.length <= 1200 then text else text.take(1200) + "\n... truncated ...")
      .map(_.trim)
      .filter(_.nonEmpty)

  private def suffix(value: Option[String]): String =
    value.fold("")(message => s": $message")

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

private[sbt_runner] object SbtClasspathInjection:
  val CompileTask = "semanticScalaInternalExportCompileClasspath"
  val TestTask = "semanticScalaInternalExportTestClasspath"

  val GlobalSettings: String =
    """val semanticScalaInternalExportCompileClasspath =
      |  taskKey[Unit]("Export Compile fullClasspath for semantic-scala")
      |val semanticScalaInternalExportTestClasspath =
      |  taskKey[Unit]("Export Test fullClasspath for semantic-scala")
      |
      |def semanticScalaInternalWriteClasspath(
      |    projectId: String,
      |    configuration: String,
      |    classpath: Classpath
      |): Unit = {
      |  val encoder = java.util.Base64.getEncoder
      |  def encoded(value: String): String =
      |    encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      |  val entries = classpath.map(_.data).map { entry =>
      |    val kind =
      |      if (entry.isDirectory) "Directory"
      |      else if (entry.isFile &&
      |          entry.getName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) "Jar"
      |      else "Unsupported"
      |    s"entry\t$kind\t${encoded(entry.getAbsolutePath)}"
      |  }
      |  IO.writeLines(
      |    file(sys.env("SEMANTIC_SCALA_SBT_CLASSPATH_RESULT")),
      |    Seq(
      |      "semantic-scala.internal-sbt-classpath.v1",
      |      s"project\t${encoded(projectId)}",
      |      s"configuration\t$configuration"
      |    ) ++ entries
      |  )
      |}
      |
      |semanticScalaInternalExportCompileClasspath := {
      |  semanticScalaInternalWriteClasspath(
      |    thisProjectRef.value.project,
      |    "Compile",
      |    (Compile / fullClasspath).value
      |  )
      |}
      |
      |semanticScalaInternalExportTestClasspath := {
      |  semanticScalaInternalWriteClasspath(
      |    thisProjectRef.value.project,
      |    "Test",
      |    (Test / fullClasspath).value
      |  )
      |}
      |""".stripMargin

private[sbt_runner] enum SbtClasspathProcessOutcome:
  case Completed(result: SbtRunResult)
  case TimedOut(stdout: String, stderr: String)
  case FailedToStart(message: String)

private[sbt_runner] trait SbtClasspathProcess:
  def run(
      request: SbtClasspathRequest,
      globalBase: Path,
      resultFile: Path,
      task: String,
      timeout: FiniteDuration
  ): SbtClasspathProcessOutcome

private[sbt_runner] object SbtClasspathProcess:
  val default: SbtClasspathProcess = ProcessSbtClasspathProcess()

private final case class ProcessSbtClasspathProcess() extends SbtClasspathProcess:
  override def run(
      request: SbtClasspathRequest,
      globalBase: Path,
      resultFile: Path,
      task: String,
      timeout: FiniteDuration
  ): SbtClasspathProcessOutcome =
    val command = s"project ${request.project.value}; $task"
    val builder = ProcessBuilder(
      "sbt",
      "-batch",
      "-Dsbt.log.noformat=true",
      "-Dsbt.supershell=false",
      s"-Dsbt.global.base=$globalBase",
      s"project ${request.project.value}",
      task
    )
      .directory(request.workspace.toFile)
      .redirectErrorStream(false)
    builder.environment().put("SEMANTIC_SCALA_SBT_CLASSPATH_RESULT", resultFile.toString)
    SbtSandbox.configure(builder.environment())

    Try(builder.start()).toEither match
      case Left(exception) =>
        SbtClasspathProcessOutcome.FailedToStart(Option(exception.getMessage).getOrElse(""))
      case Right(process) =>
        val stdoutThread = SbtClasspathStreamCollector(process.getInputStream)
        val stderrThread = SbtClasspathStreamCollector(process.getErrorStream)
        stdoutThread.start()
        stderrThread.start()
        val completed = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
        if !completed then
          process.destroy()
          if !process.waitFor(2, TimeUnit.SECONDS) then process.destroyForcibly()
        stdoutThread.join(5000)
        stderrThread.join(5000)
        val stdout = stdoutThread.result
        val stderr = stderrThread.result
        if completed then
          SbtClasspathProcessOutcome.Completed(
            SbtRunResult(command, process.exitValue(), stdout, stderr)
          )
        else SbtClasspathProcessOutcome.TimedOut(stdout, stderr)

private final class SbtClasspathStreamCollector(stream: java.io.InputStream) extends Thread:
  private val buffer = StringBuilder()

  override def run(): Unit =
    val bytes = stream.readAllBytes()
    buffer.append(String(bytes, StandardCharsets.UTF_8))

  def result: String = buffer.toString
