package semantic.harness.mcp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

final case class ProcessResult(
  exitCode: Int,
  stdout: String,
  stderr: String,
  failure: Option[ProcessFailure] = None
)

trait ProcessRunner:
  def run(command: List[String], cwd: Path): ProcessResult

  def run(command: List[String], cwd: Path, execution: ProcessExecution): ProcessResult =
    run(command, cwd)

object ProcessRunner:
  val default: ProcessRunner = new ProcessRunner:
    override def run(command: List[String], cwd: Path): ProcessResult =
      run(command, cwd, ProcessExecution.default)

    override def run(
      command: List[String],
      cwd: Path,
      execution: ProcessExecution
    ): ProcessResult =
      if execution.isCancelled then failed(ProcessFailure.Cancelled)
      else if execution.isTimedOut then failed(ProcessFailure.TimedOut)
      else runOwnedProcess(command, cwd, execution)

  private final case class Capture(bytes: Array[Byte], failure: Option[ProcessFailure])

  private def runOwnedProcess(
    command: List[String],
    cwd: Path,
    execution: ProcessExecution
  ): ProcessResult =
    val process = new ProcessBuilder(command.asJava)
      .directory(cwd.toFile)
      .start()

    val aggregate = AtomicLong(0L)
    val sharedFailure = AtomicReference[ProcessFailure | Null](null)
    val stdoutTask = captureTask(
      process.getInputStream,
      execution.limits.stdoutBytes,
      ProcessFailure.StdoutLimitExceeded,
      aggregate,
      execution.limits.aggregateBytes,
      sharedFailure
    )
    val stderrTask = captureTask(
      process.getErrorStream,
      execution.limits.stderrBytes,
      ProcessFailure.StderrLimitExceeded,
      aggregate,
      execution.limits.aggregateBytes,
      sharedFailure
    )
    val stdoutThread = startCapture("semantic-scala-stdout", stdoutTask)
    val stderrThread = startCapture("semantic-scala-stderr", stderrTask)

    var terminalFailure: Option[ProcessFailure] = None
    var completed = false
    try
      while !completed && terminalFailure.isEmpty do
        completed = process.waitFor(25L, TimeUnit.MILLISECONDS)
        if !completed then
          terminalFailure =
            if execution.isCancelled then Some(ProcessFailure.Cancelled)
            else if execution.isTimedOut then Some(ProcessFailure.TimedOut)
            else Option(sharedFailure.get())
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        terminalFailure = Some(ProcessFailure.Cancelled)

    val cleanupOk =
      terminalFailure match
        case Some(_) => terminateOwnedTree(process)
        case None    => true

    if terminalFailure.nonEmpty then
      closeQuietly(process.getInputStream)
      closeQuietly(process.getErrorStream)
    closeQuietly(process.getOutputStream)

    val stdoutCapture = awaitCapture(stdoutTask, stdoutThread)
    val stderrCapture = awaitCapture(stderrTask, stderrThread)
    closeQuietly(process.getInputStream)
    closeQuietly(process.getErrorStream)
    val captureFailure =
      terminalFailure
        .orElse(stdoutCapture.failure)
        .orElse(stderrCapture.failure)
        .orElse(Option(sharedFailure.get()))
    val finalFailure =
      if cleanupOk then captureFailure
      else Some(ProcessFailure.CleanupFailed)

    val stdout = decode(stdoutCapture.bytes)
    val stderr = decode(stderrCapture.bytes)
    val decodeFailure =
      if stdout.isLeft || stderr.isLeft then Some(ProcessFailure.InvalidUtf8)
      else None

    ProcessResult(
      exitCode = if process.isAlive then -1 else process.exitValue(),
      stdout = stdout.getOrElse(""),
      stderr = stderr.getOrElse(""),
      failure = finalFailure.orElse(decodeFailure)
    )

  private def captureTask(
    stream: InputStream,
    streamLimit: Long,
    streamFailure: ProcessFailure,
    aggregate: AtomicLong,
    aggregateLimit: Long,
    sharedFailure: AtomicReference[ProcessFailure | Null]
  ): FutureTask[Capture] =
    FutureTask(
      new Callable[Capture]:
        override def call(): Capture =
          val output = ByteArrayOutputStream()
          val buffer = Array.ofDim[Byte](8192)
          var localBytes = 0L
          var failure: Option[ProcessFailure] = None
          try
            var read = stream.read(buffer)
            while read >= 0 && failure.isEmpty do
              if read > 0 then
                val nextLocal = localBytes + read
                val nextAggregate = aggregate.addAndGet(read.toLong)
                if nextLocal > streamLimit then
                  failure = Some(streamFailure)
                else if nextAggregate > aggregateLimit then
                  failure = Some(ProcessFailure.AggregateLimitExceeded)
                else
                  output.write(buffer, 0, read)
                  localBytes = nextLocal
                failure.foreach(value => sharedFailure.compareAndSet(null, value))
              if failure.isEmpty then read = stream.read(buffer)
            Capture(output.toByteArray, failure)
          finally closeQuietly(stream)
    )

  private def startCapture(name: String, task: FutureTask[Capture]): Thread =
    val thread = Thread(task, name)
    thread.setDaemon(true)
    thread.start()
    thread

  private def awaitCapture(task: FutureTask[Capture], thread: Thread): Capture =
    try task.get(2L, TimeUnit.SECONDS)
    catch
      case _: Exception =>
        thread.interrupt()
        Capture(Array.emptyByteArray, Some(ProcessFailure.CleanupFailed))

  private def terminateOwnedTree(process: Process): Boolean =
    val descendants = process.toHandle.descendants().iterator().asScala.toList.reverse
    descendants.foreach(_.destroy())
    process.destroy()

    val graceful = awaitOwnedTreeExit(process, descendants, 500L)

    if !graceful then
      descendants.filter(_.isAlive).foreach(_.destroyForcibly())
      if process.isAlive then process.destroyForcibly()
      awaitOwnedTreeExit(process, descendants, 2000L)

    !process.isAlive && descendants.forall(!_.isAlive)

  private def awaitOwnedTreeExit(
    process: Process,
    descendants: List[ProcessHandle],
    timeoutMillis: Long
  ): Boolean =
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    def exited: Boolean = !process.isAlive && descendants.forall(!_.isAlive)

    var complete = exited
    try
      while !complete && System.nanoTime() < deadline do
        val remainingNanos = deadline - System.nanoTime()
        val waitMillis =
          math.max(
            1L,
            math.min(10L, TimeUnit.NANOSECONDS.toMillis(math.max(0L, remainingNanos)))
          )
        if process.isAlive then process.waitFor(waitMillis, TimeUnit.MILLISECONDS)
        else Thread.sleep(waitMillis)
        complete = exited
      complete
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        false

  private def decode(bytes: Array[Byte]): Either[Unit, String] =
    try
      val decoder =
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
      Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch case _: Exception => Left(())

  private def failed(failure: ProcessFailure): ProcessResult =
    ProcessResult(-1, "", "", Some(failure))

  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch case _: Exception => ()
