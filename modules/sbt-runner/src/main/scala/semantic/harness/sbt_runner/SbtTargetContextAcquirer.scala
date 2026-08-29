package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

trait SbtTargetContextAcquirer:
  def acquire(
      request: SbtTargetContextRequest
  ): Either[SbtTargetContextFailure, SbtTargetContextReceipt]

object SbtTargetContextAcquirer:
  val DefaultTimeout: FiniteDuration = 180.seconds
  val default: SbtTargetContextAcquirer =
    ProcessSbtTargetContextAcquirer(DefaultTimeout, SbtTargetContextProcess.default)

enum SbtTargetContextFailure:
  case Validation(message: String)
  case UnknownProject(message: String)
  case Process(message: String)
  case Protocol(message: String)

object SbtTargetContextFailure:
  def message(failure: SbtTargetContextFailure): String =
    failure match
      case SbtTargetContextFailure.Validation(message)     => message
      case SbtTargetContextFailure.UnknownProject(message) => message
      case SbtTargetContextFailure.Process(message)        => message
      case SbtTargetContextFailure.Protocol(message)       => message

private[sbt_runner] final case class ProcessSbtTargetContextAcquirer(
    timeout: FiniteDuration,
    process: SbtTargetContextProcess,
    materializer: Option[SbtClasspathMaterializer] = None
) extends SbtTargetContextAcquirer:
  override def acquire(
      request: SbtTargetContextRequest
  ): Either[SbtTargetContextFailure, SbtTargetContextReceipt] =
    SbtTargetContextRequest
      .validate(request)
      .left
      .map(SbtTargetContextFailure.Validation.apply)
      .flatMap(acquireValidated)

  private def acquireValidated(
      request: SbtTargetContextRequest
  ): Either[SbtTargetContextFailure, SbtTargetContextReceipt] =
    val temporaryRoot = Files.createTempDirectory("ss-sbt-target-context-")
    val globalBase = Files.createDirectory(temporaryRoot.resolve("global"))
    val receiptFile = temporaryRoot.resolve("receipt.protocol")
    try
      Files.writeString(
        globalBase.resolve("global.sbt"),
        SbtTargetContextInjection.globalSettings(request),
        StandardCharsets.UTF_8
      )
      process.run(request, globalBase, receiptFile, SbtFixedTask.TargetContextReceipt, timeout) match
        case SbtTargetContextProcessOutcome.Completed(result) if result.exitCode == 0 =>
          readReceipt(receiptFile, request)
            .flatMap(receipt =>
              materializer
                .getOrElse(SbtClasspathMaterializer.forWorkspace(request.workspace))
                .materialize(receipt, temporaryRoot)
            )
            .left
            .map(SbtTargetContextFailure.Protocol.apply)
        case SbtTargetContextProcessOutcome.Completed(result) =>
          val message = processFailure(request, result)
          if isUnknownProject(result) then Left(SbtTargetContextFailure.UnknownProject(message))
          else Left(SbtTargetContextFailure.Process(message))
        case SbtTargetContextProcessOutcome.TimedOut(stdout, stderr) =>
          Left(
            SbtTargetContextFailure.Process(
              s"Unable to acquire sbt target context for project '${request.project.value}': " +
                s"sbt exceeded the ${timeout.toSeconds}-second timeout${suffix(diagnosticExcerpt(stdout, stderr))}"
            )
          )
        case SbtTargetContextProcessOutcome.FailedToStart(message) =>
          Left(
            SbtTargetContextFailure.Process(
              s"Unable to acquire sbt target context for project '${request.project.value}': " +
                s"unable to start sbt${suffix(Option(message).map(_.trim).filter(_.nonEmpty))}"
            )
          )
    finally deleteRecursively(temporaryRoot)

  private def readReceipt(
      path: Path,
      request: SbtTargetContextRequest
  ): Either[String, SbtTargetContextReceipt] =
    if !Files.isRegularFile(path) then
      Left("Invalid sbt target-context receipt: result file was not created")
    else
      val stream = Files.newInputStream(path)
      val bytes =
        try stream.readNBytes(SbtTargetContextProtocol.MaxProtocolBytes + 1)
        finally stream.close()
      if bytes.length > SbtTargetContextProtocol.MaxProtocolBytes then
        Left("Invalid sbt target-context receipt: protocol exceeds byte limit")
      else SbtTargetContextProtocol.parse(String(bytes, StandardCharsets.UTF_8), request)

  private def processFailure(request: SbtTargetContextRequest, result: SbtRunResult): String =
    s"Unable to acquire sbt target context for project '${request.project.value}': " +
      s"sbt exited with code ${result.exitCode}${suffix(diagnosticExcerpt(result.stdout, result.stderr))}"

  private def isUnknownProject(result: SbtRunResult): Boolean =
    val output = result.stdout + "\n" + result.stderr
    output.contains("Not a valid project ID") || output.contains("No project")

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

private[sbt_runner] object SbtTargetContextInjection:
  val Task = SbtFixedTask.TargetContextReceipt.selectedTask

  private val Settings =
    SbtInjectedClasspathMaterialization.Settings +
      s"""@transient val $Task = taskKey[Unit]("Export one bounded Compile target-context receipt")
       |
       |$Task := {
       |  val selectedClassDirectory = (Compile / classDirectory).value.getCanonicalFile
       |  val selectedSemanticdbTargetRoot = (Compile / semanticdbTargetRoot).value.getCanonicalFile
       |  val selectedClasspath = (Compile / fullClasspath).value
       |  val selectedScalaVersion = scalaVersion.value
       |  val converter = fileConverter.value
       |  val encoder = java.util.Base64.getEncoder
       |  def encoded(value: String): String =
       |    encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |  val entries = selectedClasspath.map(entry =>
       |    semanticScalaInternalClasspathFile(entry, converter)
       |  ).map { entry =>
       |    val kind =
       |      if (entry.isDirectory) "Directory"
       |      else if (entry.isFile) "Jar"
       |      else "Unsupported"
       |    s"entry\t$$kind\t$${encoded(entry.getAbsolutePath)}"
       |  }
       |  val javaContext = sys.env.get("SEMANTIC_SCALA_TARGET_CONTEXT_JAVA").toSeq.map { value =>
       |    s"javaContext\t$${encoded(value)}"
       |  }
       |  IO.writeLines(
       |    file(sys.env("SEMANTIC_SCALA_TARGET_CONTEXT_RECEIPT")),
       |    Seq(
       |      "${SbtTargetContextProtocol.Format}",
       |      s"project\t$${encoded(thisProjectRef.value.project)}",
       |      "configuration\tCompile",
       |      s"classDirectory\t$${encoded(selectedClassDirectory.getAbsolutePath)}",
       |      s"semanticdbTargetRoot\t$${encoded(selectedSemanticdbTargetRoot.getAbsolutePath)}",
       |      s"scalaVersion\t$${encoded(selectedScalaVersion)}"
       |    ) ++ javaContext ++ entries
       |  )
       |}
       |""".stripMargin

  def globalSettings(request: SbtTargetContextRequest): String = Settings

private[sbt_runner] enum SbtTargetContextProcessOutcome:
  case Completed(result: SbtRunResult)
  case TimedOut(stdout: String, stderr: String)
  case FailedToStart(message: String)

private[sbt_runner] trait SbtTargetContextProcess:
  def run(
      request: SbtTargetContextRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtTargetContextProcessOutcome

private[sbt_runner] object SbtTargetContextProcess:
  val default: SbtTargetContextProcess = ProcessSbtTargetContextProcess()

private final case class ProcessSbtTargetContextProcess() extends SbtTargetContextProcess:
  override def run(
      request: SbtTargetContextRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtTargetContextProcessOutcome =
    val environment = Map("SEMANTIC_SCALA_TARGET_CONTEXT_RECEIPT" -> receiptFile.toString) ++
      request.targetJava.map(selected =>
        "SEMANTIC_SCALA_TARGET_CONTEXT_JAVA" -> SbtJavaContext.token(selected)
      )
    SbtProcessLifecycle.run(
      request.workspace,
      globalBase,
      globalBase.getParent.resolve("r"),
      SbtCommandSequence.selected(request.project, task),
      request.targetJava,
      environment,
      timeout
    ) match
      case SbtProcessOutcome.Completed(result) => SbtTargetContextProcessOutcome.Completed(result)
      case SbtProcessOutcome.TimedOut(stdout, stderr) =>
        SbtTargetContextProcessOutcome.TimedOut(stdout, stderr)
      case SbtProcessOutcome.FailedToStart(message) =>
        SbtTargetContextProcessOutcome.FailedToStart(message)
