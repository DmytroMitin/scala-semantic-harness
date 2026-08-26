package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

final case class SbtRunResult(
  command: String,
  exitCode: Int,
  stdout: String,
  stderr: String,
  structuredTestResult: Option[SbtStructuredTestResult] = None
)

trait SbtRunner:
  def compile(
      projectDir: Path,
      project: Option[SbtProjectId],
      targetJava: Option[ValidatedSbtJavaHome]
  ): SbtRunResult
  def test(
      projectDir: Path,
      project: Option[SbtProjectId],
      targetJava: Option[ValidatedSbtJavaHome]
  ): SbtRunResult

object SbtRunner:
  val DefaultTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(300).seconds
  val default: SbtRunner = ProcessSbtRunner(DefaultTimeout)

final case class ProcessSbtRunner(
    timeout: scala.concurrent.duration.FiniteDuration = SbtRunner.DefaultTimeout
) extends SbtRunner:
  override def compile(
      projectDir: Path,
      project: Option[SbtProjectId],
      targetJava: Option[ValidatedSbtJavaHome]
  ): SbtRunResult =
    run(projectDir, project, targetJava, SbtFixedTask.Compile)

  override def test(
      projectDir: Path,
      project: Option[SbtProjectId],
      targetJava: Option[ValidatedSbtJavaHome]
  ): SbtRunResult =
    run(projectDir, project, targetJava, SbtFixedTask.Test)

  private def run(
      projectDir: Path,
      project: Option[SbtProjectId],
      targetJava: Option[ValidatedSbtJavaHome],
      task: SbtFixedTask
  ): SbtRunResult =
    val temporaryRoot = Files.createTempDirectory("ss-sbt-run-")
    val globalBase = Files.createDirectory(temporaryRoot.resolve("g"))
    val runtimeDirectory = Files.createDirectory(temporaryRoot.resolve("r"))
    val completionFile = temporaryRoot.resolve("test-completion.protocol")
    val selectedTask = if task == SbtFixedTask.Test then SbtFixedTask.StructuredTest else task
    val command = SbtCommandSequence.build(project, selectedTask)
    try
      val environment =
        if task == SbtFixedTask.Test then
          Files.writeString(
            globalBase.resolve("global.sbt"),
            SbtTestResultSource.GlobalSettings,
            StandardCharsets.UTF_8
          )
          Map(
            SbtTestResultSource.CompletionEnvironment -> completionFile.toString
          )
        else Map.empty[String, String]
      SbtProcessLifecycle.run(
        projectDir,
        globalBase,
        runtimeDirectory,
        command,
        targetJava,
        environment,
        timeout
      ) match
        case SbtProcessOutcome.Completed(result) if task == SbtFixedTask.Test =>
          SbtTestResultSource.read(completionFile) match
            case Right(structured) => result.copy(structuredTestResult = Some(structured))
            case Left(message) if result.exitCode == 0 => throw IllegalStateException(message)
            case Left(_) => result
        case SbtProcessOutcome.Completed(result) => result
        case SbtProcessOutcome.TimedOut(_, _) =>
          throw IllegalStateException(s"sbt exceeded the ${timeout.toSeconds}-second timeout")
        case SbtProcessOutcome.FailedToStart(message) =>
          throw IllegalStateException(s"unable to start sbt: $message")
    finally deleteRecursively(temporaryRoot)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()

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
