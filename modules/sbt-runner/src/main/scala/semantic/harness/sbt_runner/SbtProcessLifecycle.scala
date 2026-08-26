package semantic.harness.sbt_runner

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

private[sbt_runner] enum SbtProcessOutcome:
  case Completed(result: SbtRunResult)
  case TimedOut(stdout: String, stderr: String)
  case FailedToStart(message: String)

private[sbt_runner] object SbtProcessLifecycle:
  private val MaxStreamBytes = 2 * 1024 * 1024
  private val GracefulShutdownSeconds = 2L
  private val DrainJoinMillis = 5000L

  def commandVector(globalBase: Path, command: String): List[String] =
    List(
      "sbt",
      "--server",
      "--batch",
      "-Dsbt.log.noformat=true",
      "-Dsbt.supershell=false",
      s"-Dsbt.global.base=$globalBase",
      command
    )

  def run(
      workspace: Path,
      globalBase: Path,
      runtimeDirectory: Path,
      command: String,
      targetJava: Option[ValidatedSbtJavaHome],
      environmentEntries: Map[String, String],
      timeout: FiniteDuration
  ): SbtProcessOutcome =
    Files.createDirectories(runtimeDirectory)
    val builder = ProcessBuilder(commandVector(globalBase, command)*)
      .directory(workspace.toFile)
      .redirectErrorStream(false)
    val environment = builder.environment()
    environmentEntries.foreach { case (name, value) => environment.put(name, value) }
    targetJava.foreach(SbtJavaEnvironment.configure(environment, _))
    SbtSandbox.configure(environment)
    environment.put("XDG_RUNTIME_DIR", runtimeDirectory.toString)

    Try(builder.start()).toEither match
      case Left(exception) =>
        SbtProcessOutcome.FailedToStart(sanitize(message(exception), workspace, targetJava))
      case Right(process) =>
        val stdoutDrain = BoundedSbtStreamDrain(process.getInputStream, MaxStreamBytes)
        val stderrDrain = BoundedSbtStreamDrain(process.getErrorStream, MaxStreamBytes)
        stdoutDrain.start()
        stderrDrain.start()
        val completed = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
        if !completed then terminateOwnedTree(process)
        stdoutDrain.join(DrainJoinMillis)
        stderrDrain.join(DrainJoinMillis)
        val stdout = sanitize(stdoutDrain.result, workspace, targetJava)
        val stderr = sanitize(stderrDrain.result, workspace, targetJava)
        if completed then
          SbtProcessOutcome.Completed(
            SbtRunResult(command, process.exitValue(), stdout, stderr)
          )
        else SbtProcessOutcome.TimedOut(stdout, stderr)

  private def terminateOwnedTree(process: Process): Unit =
    val descendants = process.descendants().iterator().asScala.toList.reverse
    descendants.foreach(_.destroy())
    process.destroy()
    if !process.waitFor(GracefulShutdownSeconds, TimeUnit.SECONDS) then
      descendants.filter(_.isAlive).foreach(_.destroyForcibly())
      process.destroyForcibly()
      process.waitFor(GracefulShutdownSeconds, TimeUnit.SECONDS)

  private def message(exception: Throwable): String =
    Option(exception.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(exception.getClass.getSimpleName)

  private def sanitize(
      value: String,
      workspace: Path,
      targetJava: Option[ValidatedSbtJavaHome]
  ): String =
    sanitizeWorkspace(targetJava.fold(value)(SbtJavaPrivacy.sanitize(value, _)), workspace)

  private[sbt_runner] def sanitizeWorkspace(value: String, workspace: Path): String =
    val normalized = workspace.toAbsolutePath.normalize()
    val candidates =
      (normalized :: Try(normalized.toRealPath()).toOption.toList)
        .map(_.toString)
        .distinct
        .sortBy(path => -path.length)
    candidates.foldLeft(value)((sanitized, path) => sanitized.replace(path, "<workspace>"))

private[sbt_runner] final class BoundedSbtStreamDrain(
    stream: InputStream,
    maxBytes: Int
) extends Thread:
  private val retained = Array.ofDim[Byte](maxBytes)
  private var next = 0
  private var size = 0
  private var truncated = false

  override def run(): Unit =
    val buffer = Array.ofDim[Byte](8192)
    try
      var read = stream.read(buffer)
      while read != -1 do
        var index = 0
        while index < read do
          retained(next) = buffer(index)
          next = (next + 1) % maxBytes
          if size < maxBytes then size += 1
          else truncated = true
          index += 1
        read = stream.read(buffer)
    catch case _: Exception => ()
    finally Try(stream.close())

  def result: String =
    val bytes =
      if !truncated then retained.slice(0, size)
      else retained.slice(next, maxBytes) ++ retained.slice(0, next)
    val text = String(bytes, StandardCharsets.UTF_8)
    if truncated then "... output truncated to bounded tail ...\n" + text else text
