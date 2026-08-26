package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

trait SbtTastyCompileReceiptAcquirer:
  def acquire(
      request: SbtTastyCompileRequest
  ): Either[SbtTastyCompileFailure, SbtTastyCompileReceipt]

object SbtTastyCompileReceiptAcquirer:
  val DefaultTimeout: FiniteDuration = 300.seconds
  val default: SbtTastyCompileReceiptAcquirer =
    ProcessSbtTastyCompileReceiptAcquirer(DefaultTimeout, SbtTastyCompileProcess.default)

enum SbtTastyCompileFailure:
  case Validation(message: String)
  case Process(message: String)
  case Protocol(message: String)

object SbtTastyCompileFailure:
  def message(failure: SbtTastyCompileFailure): String =
    failure match
      case SbtTastyCompileFailure.Validation(message) => message
      case SbtTastyCompileFailure.Process(message)    => message
      case SbtTastyCompileFailure.Protocol(message)   => message

private[sbt_runner] final case class ProcessSbtTastyCompileReceiptAcquirer(
    timeout: FiniteDuration,
    process: SbtTastyCompileProcess,
    materializer: Option[SbtClasspathMaterializer] = None
) extends SbtTastyCompileReceiptAcquirer:
  override def acquire(
      request: SbtTastyCompileRequest
  ): Either[SbtTastyCompileFailure, SbtTastyCompileReceipt] =
    SbtTastyCompileRequest
      .validate(request)
      .left
      .map(SbtTastyCompileFailure.Validation.apply)
      .flatMap(acquireValidated)

  private def acquireValidated(
      request: SbtTastyCompileRequest
  ): Either[SbtTastyCompileFailure, SbtTastyCompileReceipt] =
    val temporaryRoot = Files.createTempDirectory("ss-sbt-tasty-")
    val globalBase = Files.createDirectory(temporaryRoot.resolve("global"))
    val receiptFile = temporaryRoot.resolve("receipt.protocol")
    try
      Files.writeString(
        globalBase.resolve("global.sbt"),
        SbtTastyCompileInjection.globalSettings(request),
        StandardCharsets.UTF_8
      )
      process.run(request, globalBase, receiptFile, SbtFixedTask.TastyCompileReceipt, timeout) match
        case SbtTastyCompileProcessOutcome.Completed(0) =>
          readReceipt(receiptFile, request)
            .flatMap(receipt =>
              materializer
                .getOrElse(SbtClasspathMaterializer.forWorkspace(request.workspace))
                .materialize(receipt, temporaryRoot)
            )
            .left
            .map(SbtTastyCompileFailure.Protocol.apply)
        case SbtTastyCompileProcessOutcome.Completed(exitCode) =>
          Left(
            SbtTastyCompileFailure.Process(
              s"Unable to acquire TASTy compile receipt for project '${request.project.value}': sbt exited with code $exitCode"
            )
          )
        case SbtTastyCompileProcessOutcome.TimedOut =>
          Left(
            SbtTastyCompileFailure.Process(
              s"Unable to acquire TASTy compile receipt for project '${request.project.value}': sbt exceeded the ${timeout.toSeconds}-second timeout"
            )
          )
        case SbtTastyCompileProcessOutcome.FailedToStart =>
          Left(
            SbtTastyCompileFailure.Process(
              s"Unable to acquire TASTy compile receipt for project '${request.project.value}': unable to start sbt"
            )
          )
    finally deleteRecursively(temporaryRoot)

  private def readReceipt(
      path: Path,
      request: SbtTastyCompileRequest
  ): Either[String, SbtTastyCompileReceipt] =
    if !Files.isRegularFile(path) then
      Left("Invalid TASTy compile receipt: result file was not created")
    else
      val stream = Files.newInputStream(path)
      val bytes =
        try stream.readNBytes(SbtTastyCompileProtocol.MaxProtocolBytes + 1)
        finally stream.close()
      if bytes.length > SbtTastyCompileProtocol.MaxProtocolBytes then
        Left("Invalid TASTy compile receipt: protocol exceeds byte limit")
      else
        SbtTastyCompileProtocol.parse(new String(bytes, StandardCharsets.UTF_8), request)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

private[sbt_runner] enum SbtTastyCompileProcessOutcome:
  case Completed(exitCode: Int)
  case TimedOut
  case FailedToStart

private[sbt_runner] trait SbtTastyCompileProcess:
  def run(
      request: SbtTastyCompileRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtTastyCompileProcessOutcome

private[sbt_runner] object SbtTastyCompileProcess:
  val default: SbtTastyCompileProcess = ProcessSbtTastyCompileProcess()

private final case class ProcessSbtTastyCompileProcess() extends SbtTastyCompileProcess:
  override def run(
      request: SbtTastyCompileRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtTastyCompileProcessOutcome =
    val command = SbtCommandSequence.selected(request.project, task)
    val environment = Map(
      "SEMANTIC_SCALA_TASTY_RECEIPT" -> receiptFile.toString,
      "SEMANTIC_SCALA_TASTY_SOURCE" -> request.source.toString
    ) ++ request.targetJava.map(selected =>
      "SEMANTIC_SCALA_TASTY_JAVA_CONTEXT" -> SbtJavaContext.token(selected)
    )
    SbtProcessLifecycle.run(
      request.workspace,
      globalBase,
      globalBase.getParent.resolve("r"),
      command,
      request.targetJava,
      environment,
      timeout
    ) match
      case SbtProcessOutcome.Completed(result) =>
        SbtTastyCompileProcessOutcome.Completed(result.exitCode)
      case SbtProcessOutcome.TimedOut(_, _) => SbtTastyCompileProcessOutcome.TimedOut
      case SbtProcessOutcome.FailedToStart(_) => SbtTastyCompileProcessOutcome.FailedToStart
