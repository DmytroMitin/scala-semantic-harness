package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt

class SbtClasspathAcquirerSuite extends munit.FunSuite:
  test("acquirer writes isolated injection, parses result, and cleans temporary files"):
    val workspace = Files.createTempDirectory("task069-acquirer-workspace")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    val fake = SuccessfulProcess(classes)
    val acquirer = ProcessSbtClasspathAcquirer(5.seconds, fake)
    val request = SbtClasspathRequest(workspace, project("app-2"), SbtClasspathConfiguration.Compile)
    try
      val result = acquirer.acquire(request)

      assertEquals(result.map(_.entries.map(_.path)), Right(List(classes.toAbsolutePath.normalize())))
      assert(fake.globalSettings.exists(_.contains(SbtClasspathProtocol.Format)))
      assertEquals(fake.task, Some(SbtFixedTask.CompileClasspath))
      assert(fake.temporaryRoot.forall(path => !Files.exists(path)))
    finally
      Files.deleteIfExists(classes)
      Files.deleteIfExists(workspace)

  test("acquirer selects the Test task explicitly"):
    val workspace = Files.createTempDirectory("task069-acquirer-test")
    val classes = Files.createDirectory(workspace.resolve("test-classes"))
    val fake = SuccessfulProcess(classes)
    val request = SbtClasspathRequest(workspace, project("app-2"), SbtClasspathConfiguration.Test)
    try
      assert(ProcessSbtClasspathAcquirer(5.seconds, fake).acquire(request).isRight)
      assertEquals(fake.task, Some(SbtFixedTask.TestClasspath))
    finally
      Files.deleteIfExists(classes)
      Files.deleteIfExists(workspace)

  test("acquirer keeps process, timeout, and protocol failures distinct"):
    val workspace = Files.createTempDirectory("task069-acquirer-failures")
    val request = SbtClasspathRequest(workspace, project("app-2"), SbtClasspathConfiguration.Compile)
    try
      val nonzero = ProcessSbtClasspathAcquirer(
        5.seconds,
        FixedProcess(
          SbtClasspathProcessOutcome.Completed(
            SbtRunResult("task", 1, "[error] Not a valid project ID: app-2", "")
          )
        )
      ).acquire(request)
      val timeout = ProcessSbtClasspathAcquirer(
        5.seconds,
        FixedProcess(SbtClasspathProcessOutcome.TimedOut("", "[error] still running"))
      ).acquire(request)
      val missingProtocol = ProcessSbtClasspathAcquirer(
        5.seconds,
        FixedProcess(SbtClasspathProcessOutcome.Completed(SbtRunResult("task", 0, "", "")))
      ).acquire(request)

      assert(nonzero.left.exists {
        case SbtClasspathFailure.Process(message) =>
          message.contains("exited with code 1") && message.contains("Not a valid project ID")
        case _ => false
      })
      assert(timeout.left.exists {
        case SbtClasspathFailure.Process(message) => message.contains("5-second timeout")
        case _                                    => false
      })
      assert(missingProtocol.left.exists {
        case SbtClasspathFailure.Protocol(message) => message.contains("not created")
        case _                                     => false
      })
    finally Files.deleteIfExists(workspace)

  test("validation failure does not launch a process"):
    val process = CountingProcess()
    val request = SbtClasspathRequest(
      Path.of("target/task069-missing-acquirer-workspace"),
      project("app-2"),
      SbtClasspathConfiguration.Compile
    )

    val result = ProcessSbtClasspathAcquirer(5.seconds, process).acquire(request)

    assert(result.left.exists(_.isInstanceOf[SbtClasspathFailure.Validation]))
    assertEquals(process.calls, 0)

  test("explicit Java acquisition uses v2 token without embedding private paths"):
    val workspace = Files.createTempDirectory("task166-acquirer-v2")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    val selected = SbtClasspathCacheTestSupport.selectedJava(workspace.resolve("private-jdk"))
    val fake = SuccessfulProcess(classes)
    val request = SbtClasspathRequest(
      workspace,
      project("app-2"),
      SbtClasspathConfiguration.Compile,
      Some(selected)
    )
    try
      val result = ProcessSbtClasspathAcquirer(5.seconds, fake).acquire(request)

      assert(result.isRight, clue(result))
      val settings = fake.globalSettings.getOrElse(fail("missing generated settings"))
      assert(settings.contains(SbtClasspathProtocol.FormatV2), clue(settings))
      assert(settings.contains(SbtJavaContext.token(selected)), clue(settings))
      assert(!settings.contains(selected.canonicalHome.toString), clue(settings))
      assertEquals(result.toOption.flatMap(_.javaContextToken), Some(SbtJavaContext.token(selected)))
    finally
      Files.deleteIfExists(classes)
      Files.deleteIfExists(workspace)

  test("injection materializes sbt 1 files and sbt 2 virtual references through fileConverter"):
    val settings = SbtClasspathInjection.GlobalSettings

    assert(settings.contains("xsbti.FileConverter"), clue(settings))
    assert(settings.contains("xsbti.VirtualFileRef"), clue(settings))
    assert(settings.contains("Attributed[_]"), clue(settings))
    assert(settings.contains("fileConverter.value"), clue(settings))
    assert(settings.contains("toPath(reference)"), clue(settings))
    assert(settings.contains("case file: java.io.File"), clue(settings))
    assert(!settings.contains("java.lang.reflect"), clue(settings))
    assert(!settings.contains(".ivy2"), clue(settings))
    assert(!settings.contains("coursier"), clue(settings))

  private final case class SuccessfulProcess(entry: Path) extends SbtClasspathProcess:
    var globalSettings: Option[String] = None
    var temporaryRoot: Option[Path] = None
    var task: Option[SbtFixedTask] = None

    override def run(
        request: SbtClasspathRequest,
        globalBase: Path,
        resultFile: Path,
        selectedTask: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtClasspathProcessOutcome =
      globalSettings = Some(Files.readString(globalBase.resolve("global.sbt")))
      temporaryRoot = Some(globalBase.getParent)
      task = Some(selectedTask)
      Files.writeString(
        resultFile,
        SbtClasspathProtocol.render(
          SbtClasspathResult(
            request.project,
            request.configuration,
            List(SbtClasspathEntry(entry, SbtClasspathEntryKind.Directory)),
            request.targetJava.map(SbtJavaContext.token)
          )
        ),
        StandardCharsets.UTF_8
      )
      SbtClasspathProcessOutcome.Completed(
        SbtRunResult(selectedTask.selectedTask, 0, "ignored", "")
      )

  private final case class FixedProcess(outcome: SbtClasspathProcessOutcome)
      extends SbtClasspathProcess:
    override def run(
        request: SbtClasspathRequest,
        globalBase: Path,
        resultFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtClasspathProcessOutcome = outcome

  private final case class CountingProcess() extends SbtClasspathProcess:
    var calls = 0

    override def run(
        request: SbtClasspathRequest,
        globalBase: Path,
        resultFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtClasspathProcessOutcome =
      calls += 1
      SbtClasspathProcessOutcome.FailedToStart("unexpected")

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)
