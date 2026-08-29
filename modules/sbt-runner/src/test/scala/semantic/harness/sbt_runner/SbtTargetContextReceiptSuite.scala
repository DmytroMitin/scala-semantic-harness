package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt

class SbtTargetContextReceiptSuite extends munit.FunSuite:
  test("request validation accepts one canonical workspace and rejects missing or symbolic workspaces"):
    val workspace = Files.createTempDirectory("target-context-request-")
    val link = workspace.getParent.resolve(workspace.getFileName.toString + "-link")
    Files.createSymbolicLink(link, workspace)
    try
      val request = SbtTargetContextRequest(workspace, project("app"))
      assertEquals(SbtTargetContextRequest.validate(request).map(_.workspace), Right(workspace.toRealPath()))
      assert(SbtTargetContextRequest.validate(request.copy(workspace = workspace.resolve("missing"))).isLeft)
      assert(SbtTargetContextRequest.validate(request.copy(workspace = link)).isLeft)
    finally
      Files.deleteIfExists(link)
      Files.deleteIfExists(workspace)

  test("protocol round-trips one fixed Compile receipt with roots classpath Scala and Java context"):
    val workspace = Files.createTempDirectory("target-context-protocol-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val selected = SbtClasspathCacheTestSupport.selectedJava(workspace.resolve("private-jdk"))
    val request = SbtTargetContextRequest(workspace, project("app"), Some(selected))
    val receipt = SbtTargetContextReceipt(
      project = project("app"),
      configuration = SbtClasspathConfiguration.Compile,
      classDirectory = classes,
      semanticdbTargetRoot = semanticdb,
      classpath = List(SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory)),
      scalaVersion = "3.8.4",
      targetJavaContext = Some(SbtJavaContext.token(selected))
    )
    try
      val rendered = SbtTargetContextProtocol.render(receipt)
      assert(rendered.startsWith(SbtTargetContextProtocol.Format + "\n"), clue(rendered))
      assertEquals(SbtTargetContextProtocol.parse(rendered, request), Right(receipt))
      assert(!rendered.contains(selected.canonicalHome.toString), clue(rendered))
    finally deleteRecursively(workspace)

  test("protocol rejects missing duplicate unexpected mismatched and non-Compile fields"):
    val workspace = Files.createTempDirectory("target-context-protocol-invalid-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val request = SbtTargetContextRequest(workspace, project("app"))
    val receipt = SbtTargetContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      classes,
      semanticdb,
      List(SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory)),
      "3.8.4",
      None
    )
    try
      val valid = SbtTargetContextProtocol.render(receipt)
      val invalid = List(
        valid.linesIterator.filterNot(_.startsWith("scalaVersion\t")).mkString("\n") + "\n",
        valid + valid.linesIterator.find(_.startsWith("project\t")).get + "\n",
        valid + "unknown\tvalue\n",
        valid.replace("configuration\tCompile", "configuration\tTest"),
        valid.replace(encoded("app"), encoded("other")),
        valid.replace(SbtTargetContextProtocol.Format, "semantic-scala.invalid")
      )
      invalid.foreach(value => assert(SbtTargetContextProtocol.parse(value, request).isLeft, clue(value)))
      assert(SbtTargetContextProtocol.parse("x" * (SbtTargetContextProtocol.MaxProtocolBytes + 1), request).isLeft)
    finally deleteRecursively(workspace)

  test("injection exports one non-compiling fixed Compile receipt with sbt1 and sbt2 materialization"):
    val workspace = Files.createTempDirectory("target-context-injection-")
    try
      val settings = SbtTargetContextInjection.globalSettings(SbtTargetContextRequest(workspace, project("app")))
      assert(settings.contains("Compile / classDirectory"), clue(settings))
      assert(settings.contains("Compile / semanticdbTargetRoot"), clue(settings))
      assert(settings.contains("Compile / fullClasspath"), clue(settings))
      assert(settings.contains("scalaVersion.value"), clue(settings))
      assert(settings.contains("fileConverter.value"), clue(settings))
      assert(settings.contains("xsbti.VirtualFileRef"), clue(settings))
      assert(!settings.contains("Compile / compile"), clue(settings))
      assert(!settings.contains("compile.value"), clue(settings))
      assertEquals(SbtTargetContextInjection.Task, "semanticScalaInternalTargetContextReceipt")
    finally Files.deleteIfExists(workspace)

  test("process acquirer uses only the fixed receipt task parses one lifecycle and cleans scratch"):
    val workspace = Files.createTempDirectory("target-context-acquirer-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val process = SuccessfulProcess(classes, semanticdb)
    try
      val result = ProcessSbtTargetContextAcquirer(5.seconds, process)
        .acquire(SbtTargetContextRequest(workspace, project("app")))
      assertEquals(result.map(_.semanticdbTargetRoot), Right(semanticdb))
      assertEquals(result.map(_.classpath.map(_.path)), Right(List(classes)))
      assertEquals(process.tasks, List(SbtFixedTask.TargetContextReceipt))
      assert(process.settings.exists(_.contains(SbtTargetContextProtocol.Format)))
      assert(process.temporaryRoots.forall(path => !Files.exists(path)))
    finally deleteRecursively(workspace)

  test("unknown project timeout process failure and missing protocol fail closed without fallback"):
    val workspace = Files.createTempDirectory("target-context-acquirer-failure-")
    val request = SbtTargetContextRequest(workspace, project("missing"))
    try
      val unknown = ProcessSbtTargetContextAcquirer(
        5.seconds,
        FixedProcess(SbtTargetContextProcessOutcome.Completed(SbtRunResult("task", 1, "[error] Not a valid project ID: missing", "")))
      ).acquire(request)
      val timeout = ProcessSbtTargetContextAcquirer(
        5.seconds,
        FixedProcess(SbtTargetContextProcessOutcome.TimedOut("", "[error] still running"))
      ).acquire(request)
      val failed = ProcessSbtTargetContextAcquirer(
        5.seconds,
        FixedProcess(SbtTargetContextProcessOutcome.FailedToStart("missing sbt"))
      ).acquire(request)
      val protocol = ProcessSbtTargetContextAcquirer(
        5.seconds,
        FixedProcess(SbtTargetContextProcessOutcome.Completed(SbtRunResult("task", 0, "", "")))
      ).acquire(request)

      assert(unknown.left.exists(_.isInstanceOf[SbtTargetContextFailure.UnknownProject]))
      assert(timeout.left.exists(_.isInstanceOf[SbtTargetContextFailure.Process]))
      assert(failed.left.exists(_.isInstanceOf[SbtTargetContextFailure.Process]))
      assert(protocol.left.exists(_.isInstanceOf[SbtTargetContextFailure.Protocol]))
    finally Files.deleteIfExists(workspace)

  private final case class SuccessfulProcess(classes: Path, semanticdb: Path)
      extends SbtTargetContextProcess:
    var tasks = List.empty[SbtFixedTask]
    var settings = List.empty[String]
    var temporaryRoots = List.empty[Path]

    override def run(
        request: SbtTargetContextRequest,
        globalBase: Path,
        receiptFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtTargetContextProcessOutcome =
      tasks = tasks :+ task
      settings = settings :+ Files.readString(globalBase.resolve("global.sbt"))
      temporaryRoots = temporaryRoots :+ globalBase.getParent
      val receipt = SbtTargetContextReceipt(
        request.project,
        SbtClasspathConfiguration.Compile,
        classes,
        semanticdb,
        List(SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory)),
        "3.8.4",
        request.targetJava.map(SbtJavaContext.token)
      )
      Files.writeString(receiptFile, SbtTargetContextProtocol.render(receipt))
      SbtTargetContextProcessOutcome.Completed(SbtRunResult(task.selectedTask, 0, "", ""))

  private final case class FixedProcess(outcome: SbtTargetContextProcessOutcome)
      extends SbtTargetContextProcess:
    override def run(
        request: SbtTargetContextRequest,
        globalBase: Path,
        receiptFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtTargetContextProcessOutcome = outcome

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def encoded(value: String): String =
    java.util.Base64.getEncoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
