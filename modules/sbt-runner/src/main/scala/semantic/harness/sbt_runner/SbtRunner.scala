package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

final case class SbtRunResult(
  command: String,
  exitCode: Int,
  stdout: String,
  stderr: String
)

trait SbtRunner:
  def compile(projectDir: Path, project: Option[SbtProjectId]): SbtRunResult
  def test(projectDir: Path, project: Option[SbtProjectId]): SbtRunResult

object SbtRunner:
  val default: SbtRunner = ProcessSbtRunner()

final case class ProcessSbtRunner() extends SbtRunner:
  override def compile(projectDir: Path, project: Option[SbtProjectId]): SbtRunResult =
    run(projectDir, project, "Compile", "compile")

  override def test(projectDir: Path, project: Option[SbtProjectId]): SbtRunResult =
    run(projectDir, project, "Test", "test")

  private def run(
      projectDir: Path,
      project: Option[SbtProjectId],
      configuration: String,
      task: String
  ): SbtRunResult =
    val commands = project match
      case None => List(task)
      case Some(selected) =>
        List(s"project ${selected.value}", s"$configuration / $task")
    val builder = ProcessBuilder((List(
      "sbt",
      "-batch",
      "-Dsbt.log.noformat=true"
    ) ++ commands)*)
      .directory(projectDir.toFile)
      .redirectErrorStream(false)

    SbtSandbox.configure(builder.environment())

    val process = builder.start()

    val stdoutThread = StreamCollector(process.getInputStream)
    val stderrThread = StreamCollector(process.getErrorStream)
    stdoutThread.start()
    stderrThread.start()

    val exitCode = process.waitFor()
    stdoutThread.join()
    stderrThread.join()

    SbtRunResult(
      command = commands.mkString("; "),
      exitCode = exitCode,
      stdout = stdoutThread.result,
      stderr = stderrThread.result
    )

private[sbt_runner] object SbtSandbox:
  def configure(environment: java.util.Map[String, String]): Unit =
    Option(environment.get("SEMANTIC_SCALA_SANDBOX_DIR")).filter(_.nonEmpty).foreach { value =>
      val sandbox = Path.of(value).toAbsolutePath.normalize()
      val ivyHome = sandbox.resolve("ivy2")
      Files.createDirectories(sandbox)
      Files.createDirectories(ivyHome)

      val runtimeDir = Option(environment.get("XDG_RUNTIME_DIR")).filter(_.nonEmpty).map(Path.of(_))
      if runtimeDir.forall(path => !Files.isWritable(path)) then
        val sandboxRuntime = sandbox.resolve("runtime")
        Files.createDirectories(sandboxRuntime)
        environment.put("XDG_RUNTIME_DIR", sandboxRuntime.toString)

      Option(environment.get("HOME")).filter(_.nonEmpty).foreach { homeValue =>
        val home = Path.of(homeValue)
        copyDirectoryIfMissing(home.resolve(".sbt").resolve("boot"), sandbox.resolve("boot"))
        symlinkIfMissing(home.resolve(".ivy2").resolve("cache"), ivyHome.resolve("cache"))
        symlinkIfMissing(home.resolve(".ivy2").resolve("local"), ivyHome.resolve("local"))
      }

      val sandboxOptions = Seq(
        s"-Dsbt.boot.directory=${sandbox.resolve("boot")}",
        s"-Dsbt.global.base=${sandbox.resolve("global")}",
        s"-Dsbt.ivy.home=$ivyHome",
        "-Dsbt.server.forcestart=true"
      ).mkString(" ")
      val existingOptions = Option(environment.get("SBT_OPTS")).filter(_.nonEmpty)
      environment.put("SBT_OPTS", existingOptions.fold(sandboxOptions)(options => s"$options $sandboxOptions"))
    }

  private def copyDirectoryIfMissing(source: Path, destination: Path): Unit =
    if Files.isDirectory(source) && !Files.exists(destination) then
      val paths = Files.walk(source)
      try
        paths.forEach { path =>
          val target = destination.resolve(source.relativize(path))
          if Files.isDirectory(path) then Files.createDirectories(target)
          else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
      finally paths.close()

  private def symlinkIfMissing(source: Path, destination: Path): Unit =
    if Files.isDirectory(source) && !Files.exists(destination) then
      try Files.createSymbolicLink(destination, source)
      catch case _: Exception => ()

private final class StreamCollector(stream: java.io.InputStream) extends Thread:
  private val buffer = StringBuilder()

  override def run(): Unit =
    val bytes = stream.readAllBytes()
    buffer.append(String(bytes, StandardCharsets.UTF_8))

  def result: String = buffer.toString
