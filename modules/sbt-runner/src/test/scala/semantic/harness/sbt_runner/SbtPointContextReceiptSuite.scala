package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt

class SbtPointContextReceiptSuite extends munit.FunSuite:
  test("protocol round-trips fixed Compile partial-existing-output context with axis and redacted JDK"):
    val workspace = Files.createTempDirectory("point-context-protocol-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val dependency = Files.createFile(workspace.resolve("dependency.jar"))
    val selected = SbtClasspathCacheTestSupport.selectedJava(workspace.resolve("private-jdk"))
    val axis = scalaVersion("3.3.7")
    val request = SbtPointContextRequest(workspace, project("app"), Some(axis), Some(selected))
    val receipt = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(axis),
      axis,
      classes,
      semanticdb,
      classDirectoryPresent = true,
      List(SbtClasspathEntry(dependency, SbtClasspathEntryKind.Jar)),
      Some(SbtJavaContext.token(selected))
    )
    try
      val rendered = SbtPointContextProtocol.render(receipt)
      assertEquals(SbtPointContextProtocol.parse(rendered, request), Right(receipt))
      assert(!rendered.contains(selected.canonicalHome.toString), clue(rendered))
    finally deleteRecursively(workspace)

  test("protocol rejects missing duplicate malformed oversized and requested/effective mismatch"):
    val workspace = Files.createTempDirectory("point-context-invalid-")
    val classes = workspace.resolve("target/classes")
    val semanticdb = workspace.resolve("target/meta")
    val requested = scalaVersion("3.3.7")
    val request = SbtPointContextRequest(workspace, project("app"), Some(requested))
    val receipt = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(requested),
      requested,
      classes,
      semanticdb,
      classDirectoryPresent = false,
      Nil,
      None
    )
    try
      val valid = SbtPointContextProtocol.render(receipt)
      val invalid = List(
        valid.linesIterator.filterNot(_.startsWith("classDirectoryPresent\t")).mkString("\n") + "\n",
        valid + valid.linesIterator.find(_.startsWith("project\t")).get + "\n",
        valid + "unknown\tvalue\n",
        valid.replace("classDirectoryPresent\tfalse", "classDirectoryPresent\tmaybe"),
        valid.replace(
          s"effectiveScalaVersion\t${encoded("3.3.7")}",
          s"effectiveScalaVersion\t${encoded("3.3.8")}"
        ),
        valid.replace(SbtPointContextProtocol.Format, "semantic-scala.invalid")
      )
      invalid.foreach(value => assert(SbtPointContextProtocol.parse(value, request).isLeft, clue(value)))
      assert(SbtPointContextProtocol.parse("x" * (SbtPointContextProtocol.MaxProtocolBytes + 1), request).isLeft)
    finally deleteRecursively(workspace)

  test("injection requests only roots external dependencies and presence with sbt1 and sbt2 materialization"):
    val workspace = Files.createTempDirectory("point-context-injection-")
    try
      val settings = SbtPointContextInjection.globalSettings(
        SbtPointContextRequest(workspace, project("app"), Some(scalaVersion("3.3.7")))
      )
      assert(settings.contains("Compile / classDirectory"), clue(settings))
      assert(settings.contains("Compile / semanticdbTargetRoot"), clue(settings))
      assert(settings.contains("Compile / externalDependencyClasspath"), clue(settings))
      assert(settings.contains("scalaVersion.value"), clue(settings))
      assert(settings.contains("fileConverter.value"), clue(settings))
      assert(settings.contains("xsbti.VirtualFileRef"), clue(settings))
      List("fullClasspath", "products", "exportedProducts", "Compile / compile", "compile.value")
        .foreach(forbidden => assert(!settings.contains(forbidden), clue(settings)))
      assertEquals(SbtPointContextInjection.Task, "semanticScalaInternalPointContextReceipt")
    finally Files.deleteIfExists(workspace)

  test("process acquisition uses one fixed task and classifies switch unknown timeout protocol without fallback"):
    val workspace = Files.createTempDirectory("point-context-acquirer-")
    val request = SbtPointContextRequest(workspace, project("app"), Some(scalaVersion("9.9.9")))
    try
      val switch = CountingProcess(SbtPointContextProcessOutcome.Completed(
        SbtRunResult("task", 1, "[error] Switching to Scala 9.9.9 is not supported", "")
      ))
      val unknown = CountingProcess(SbtPointContextProcessOutcome.Completed(
        SbtRunResult("task", 1, "[error] Not a valid project ID: app", "")
      ))
      val timeout = CountingProcess(SbtPointContextProcessOutcome.TimedOut("", "[error] timeout"))
      val missing = CountingProcess(SbtPointContextProcessOutcome.Completed(SbtRunResult("task", 0, "", "")))

      assert(ProcessSbtPointContextAcquirer(1.second, switch).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.ScalaSwitch]))
      assert(ProcessSbtPointContextAcquirer(1.second, unknown).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.UnknownProject]))
      assert(ProcessSbtPointContextAcquirer(1.second, timeout).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.Process]))
      assert(ProcessSbtPointContextAcquirer(1.second, missing).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.Protocol]))
      List(switch, unknown, timeout, missing).foreach(process => assertEquals(process.calls, 1))
    finally Files.deleteIfExists(workspace)

  private final case class CountingProcess(outcome: SbtPointContextProcessOutcome)
      extends SbtPointContextProcess:
    var calls = 0
    override def run(
        request: SbtPointContextRequest,
        globalBase: Path,
        receiptFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtPointContextProcessOutcome =
      calls += 1
      outcome

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def scalaVersion(value: String): SbtScalaVersion =
    SbtScalaVersion.parse(value).fold(message => fail(message), identity)

  private def encoded(value: String): String =
    java.util.Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
