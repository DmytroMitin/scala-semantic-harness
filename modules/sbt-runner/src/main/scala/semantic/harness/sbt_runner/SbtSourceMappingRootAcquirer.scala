package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

trait SbtSourceMappingRootAcquirer:
  def acquire(
      request: SbtSourceMappingRootRequest
  ): Either[SbtSourceMappingRootFailure, SbtSourceMappingRootReceipt]

object SbtSourceMappingRootAcquirer:
  val DefaultTimeout: FiniteDuration = 180.seconds
  val default: SbtSourceMappingRootAcquirer =
    ProcessSbtSourceMappingRootAcquirer(DefaultTimeout, SbtSourceMappingRootProcess.default)

private[sbt_runner] final case class ProcessSbtSourceMappingRootAcquirer(
    timeout: FiniteDuration,
    process: SbtSourceMappingRootProcess
) extends SbtSourceMappingRootAcquirer:
  override def acquire(
      request: SbtSourceMappingRootRequest
  ): Either[SbtSourceMappingRootFailure, SbtSourceMappingRootReceipt] =
    SbtSourceMappingRootRequest
      .validate(request)
      .left
      .map(SbtSourceMappingRootFailure.Validation.apply)
      .flatMap(acquireValidated)

  private def acquireValidated(
      request: SbtSourceMappingRootRequest
  ): Either[SbtSourceMappingRootFailure, SbtSourceMappingRootReceipt] =
    val temporaryRoot = Files.createTempDirectory("ss-sbt-source-root-")
    val globalBase = Files.createDirectory(temporaryRoot.resolve("global"))
    val receiptFile = temporaryRoot.resolve("receipt.protocol")
    try
      Files.writeString(
        globalBase.resolve("global.sbt"),
        SbtSourceMappingRootInjection.globalSettings(request),
        StandardCharsets.UTF_8
      )
      process.run(
        request,
        globalBase,
        receiptFile,
        SbtFixedTask.SourceMappingRootReceipt,
        timeout
      ) match
        case SbtSourceMappingRootProcessOutcome.Completed(result) if result.exitCode == 0 =>
          readReceipt(receiptFile, request).left.map { message =>
            if message.contains("effective Scala version") then
              SbtSourceMappingRootFailure.ScalaVersionMismatch(message)
            else SbtSourceMappingRootFailure.Protocol(message)
          }
        case SbtSourceMappingRootProcessOutcome.Completed(result) =>
          val message = processFailure(request, result)
          if isScalaSwitchFailure(request, result) then
            Left(SbtSourceMappingRootFailure.ScalaSwitch(message))
          else if isUnknownProject(result) then
            Left(SbtSourceMappingRootFailure.UnknownProject(message))
          else Left(SbtSourceMappingRootFailure.Process(message))
        case SbtSourceMappingRootProcessOutcome.TimedOut(stdout, stderr) =>
          Left(SbtSourceMappingRootFailure.Process(
            s"Unable to acquire sbt source-mapping roots for project '${request.project.value}': " +
              s"sbt exceeded the ${timeout.toSeconds}-second timeout${suffix(diagnosticExcerpt(stdout, stderr))}"
          ))
        case SbtSourceMappingRootProcessOutcome.FailedToStart(message) =>
          Left(SbtSourceMappingRootFailure.Process(
            s"Unable to acquire sbt source-mapping roots for project '${request.project.value}': " +
              s"unable to start sbt${suffix(Option(message).map(_.trim).filter(_.nonEmpty))}"
          ))
    finally deleteRecursively(temporaryRoot)

  private def readReceipt(
      path: Path,
      request: SbtSourceMappingRootRequest
  ): Either[String, SbtSourceMappingRootReceipt] =
    if !Files.isRegularFile(path) then
      Left("Invalid sbt source-mapping root receipt: result file was not created")
    else
      val stream = Files.newInputStream(path)
      val bytes =
        try stream.readNBytes(SbtSourceMappingRootProtocol.MaxProtocolBytes + 1)
        finally stream.close()
      if bytes.length > SbtSourceMappingRootProtocol.MaxProtocolBytes then
        Left("Invalid sbt source-mapping root receipt: protocol exceeds byte limit")
      else SbtSourceMappingRootProtocol.parse(String(bytes, StandardCharsets.UTF_8), request)

  private def combined(result: SbtRunResult): String = result.stdout + "\n" + result.stderr

  private def isScalaSwitchFailure(
      request: SbtSourceMappingRootRequest,
      result: SbtRunResult
  ): Boolean =
    request.requestedScalaVersion.nonEmpty && {
      val output = combined(result).toLowerCase(java.util.Locale.ROOT)
      output.contains("switching to scala") || output.contains("switch failed") ||
      output.contains("not a valid scala version") || output.contains("not supported")
    }

  private def isUnknownProject(result: SbtRunResult): Boolean =
    val output = combined(result)
    output.contains("Not a valid project ID") || output.contains("No project")

  private def processFailure(request: SbtSourceMappingRootRequest, result: SbtRunResult): String =
    s"Unable to acquire sbt source-mapping roots for project '${request.project.value}': " +
      s"sbt exited with code ${result.exitCode}${suffix(diagnosticExcerpt(result.stdout, result.stderr))}"

  private def diagnosticExcerpt(stdout: String, stderr: String): Option[String] =
    val lines = List(stdout, stderr).flatMap(_.linesIterator).map(_.trim).filter(_.nonEmpty)
    val relevant = lines.filter(line =>
      line.startsWith("[error]") || line.contains("error") || line.contains("Error") ||
        line.contains("failed") || line.contains("Failed")
    )
    val selected = (if relevant.nonEmpty then relevant else lines.takeRight(8)).take(8)
    Option(selected.mkString("\n")).map(_.take(1200)).map(_.trim).filter(_.nonEmpty)

  private def suffix(value: Option[String]): String = value.fold("")(message => s": $message")

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

private[sbt_runner] object SbtSourceMappingRootInjection:
  val Task = SbtFixedTask.SourceMappingRootReceipt.selectedTask

  private val Settings =
    s"""@transient val $Task = taskKey[Unit]("Export one bounded Compile source-mapping root receipt")
       |
       |$Task := {
       |  val selectedClassDirectory = (Compile / classDirectory).value.getCanonicalFile
       |  val selectedSemanticdbTargetRoot = (Compile / semanticdbTargetRoot).value.getCanonicalFile
       |  val selectedScalaVersion = scalaVersion.value
       |  val encoder = java.util.Base64.getEncoder
       |  def encoded(value: String): String =
       |    encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |  val requestedScala = sys.env.get("SEMANTIC_SCALA_REQUESTED_SCALA_VERSION").toSeq.map { value =>
       |    s"requestedScalaVersion\t$${encoded(value)}"
       |  }
       |  val javaContext = sys.env.get("SEMANTIC_SCALA_TARGET_CONTEXT_JAVA").toSeq.map { value =>
       |    s"javaContext\t$${encoded(value)}"
       |  }
       |  IO.writeLines(
       |    file(sys.env("SEMANTIC_SCALA_SOURCE_MAPPING_ROOT_RECEIPT")),
       |    Seq(
       |      "${SbtSourceMappingRootProtocol.Format}",
       |      s"project\t$${encoded(thisProjectRef.value.project)}",
       |      "configuration\tCompile"
       |    ) ++ requestedScala ++ Seq(
       |      s"effectiveScalaVersion\t$${encoded(selectedScalaVersion)}",
       |      s"classDirectory\t$${encoded(selectedClassDirectory.getAbsolutePath)}",
       |      s"semanticdbTargetRoot\t$${encoded(selectedSemanticdbTargetRoot.getAbsolutePath)}"
       |    ) ++ javaContext
       |  )
       |}
       |""".stripMargin

  def globalSettings(request: SbtSourceMappingRootRequest): String = Settings

private[sbt_runner] enum SbtSourceMappingRootProcessOutcome:
  case Completed(result: SbtRunResult)
  case TimedOut(stdout: String, stderr: String)
  case FailedToStart(message: String)

private[sbt_runner] trait SbtSourceMappingRootProcess:
  def run(
      request: SbtSourceMappingRootRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtSourceMappingRootProcessOutcome

private[sbt_runner] object SbtSourceMappingRootProcess:
  val default: SbtSourceMappingRootProcess = ProcessSbtSourceMappingRootProcess()

private final case class ProcessSbtSourceMappingRootProcess()
    extends SbtSourceMappingRootProcess:
  override def run(
      request: SbtSourceMappingRootRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtSourceMappingRootProcessOutcome =
    val environment = Map(
      "SEMANTIC_SCALA_SOURCE_MAPPING_ROOT_RECEIPT" -> receiptFile.toString
    ) ++ request.requestedScalaVersion.map(axis =>
      "SEMANTIC_SCALA_REQUESTED_SCALA_VERSION" -> axis.value
    ) ++ request.targetJava.map(selected =>
      "SEMANTIC_SCALA_TARGET_CONTEXT_JAVA" -> SbtJavaContext.token(selected)
    )
    SbtProcessLifecycle.run(
      request.workspace,
      globalBase,
      globalBase.getParent.resolve("r"),
      SbtCommandSequence.selected(request.project, task, request.requestedScalaVersion),
      request.targetJava,
      environment,
      timeout
    ) match
      case SbtProcessOutcome.Completed(result) => SbtSourceMappingRootProcessOutcome.Completed(result)
      case SbtProcessOutcome.TimedOut(stdout, stderr) =>
        SbtSourceMappingRootProcessOutcome.TimedOut(stdout, stderr)
      case SbtProcessOutcome.FailedToStart(message) =>
        SbtSourceMappingRootProcessOutcome.FailedToStart(message)
