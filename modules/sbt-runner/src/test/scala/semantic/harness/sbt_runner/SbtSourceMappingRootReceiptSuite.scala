package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt

class SbtSourceMappingRootReceiptSuite extends munit.FunSuite:
  test("protocol round-trips requested and effective axis with only canonical roots"):
    val workspace = Files.createTempDirectory("source-root-protocol-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val axis = scalaVersion("3.3.7")
    val request = SbtSourceMappingRootRequest(workspace, project("kernelJVM"), Some(axis))
    val receipt = SbtSourceMappingRootReceipt(
      project("kernelJVM"),
      SbtClasspathConfiguration.Compile,
      Some(axis),
      axis,
      classes.toRealPath(),
      semanticdb.toRealPath(),
      None
    )
    try
      val rendered = SbtSourceMappingRootProtocol.render(receipt)
      assertEquals(SbtSourceMappingRootProtocol.parse(rendered, request), Right(receipt))
      assert(!rendered.contains("classpath"), clue(rendered))
    finally deleteRecursively(workspace)

  test("protocol rejects missing duplicate malformed oversized and requested/effective mismatch"):
    val workspace = Files.createTempDirectory("source-root-invalid-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val requested = scalaVersion("3.3.7")
    val request = SbtSourceMappingRootRequest(workspace, project("kernelJVM"), Some(requested))
    val receipt = SbtSourceMappingRootReceipt(
      project("kernelJVM"),
      SbtClasspathConfiguration.Compile,
      Some(requested),
      requested,
      classes,
      semanticdb,
      None
    )
    try
      val valid = SbtSourceMappingRootProtocol.render(receipt)
      val invalid = List(
        valid.linesIterator.filterNot(_.startsWith("effectiveScalaVersion\t")).mkString("\n") + "\n",
        valid + valid.linesIterator.find(_.startsWith("project\t")).get + "\n",
        valid + "unknown\tvalue\n",
        valid.replace(encoded("3.3.7"), encoded("3.3.8")),
        valid.replace(
          s"effectiveScalaVersion\t${encoded("3.3.7")}",
          s"effectiveScalaVersion\t${encoded("3.3.8")}"
        ),
        valid.replace(SbtSourceMappingRootProtocol.Format, "semantic-scala.invalid")
      )
      invalid.foreach(value => assert(SbtSourceMappingRootProtocol.parse(value, request).isLeft, clue(value)))
      assert(SbtSourceMappingRootProtocol.parse("x" * (SbtSourceMappingRootProtocol.MaxProtocolBytes + 1), request).isLeft)
    finally deleteRecursively(workspace)

  test("root-only injection does not request classpath products compile or arbitrary tasks"):
    val workspace = Files.createTempDirectory("source-root-injection-")
    try
      val settings = SbtSourceMappingRootInjection.globalSettings(
        SbtSourceMappingRootRequest(workspace, project("kernelJVM"), Some(scalaVersion("3.3.7")))
      )
      assert(settings.contains("Compile / classDirectory"), clue(settings))
      assert(settings.contains("Compile / semanticdbTargetRoot"), clue(settings))
      assert(settings.contains("scalaVersion.value"), clue(settings))
      List("fullClasspath", "products", "exportedProducts", "Compile / compile", "compile.value")
        .foreach(forbidden => assert(!settings.contains(forbidden), clue(settings)))
    finally Files.deleteIfExists(workspace)

  test("process acquisition classifies switch unknown timeout protocol and never retries"):
    val workspace = Files.createTempDirectory("source-root-failure-")
    val request = SbtSourceMappingRootRequest(workspace, project("kernelJVM"), Some(scalaVersion("9.9.9")))
    try
      val switch = CountingProcess(SbtSourceMappingRootProcessOutcome.Completed(
        SbtRunResult("task", 1, "[error] Switching to Scala 9.9.9 is not supported", "")
      ))
      val unknown = CountingProcess(SbtSourceMappingRootProcessOutcome.Completed(
        SbtRunResult("task", 1, "[error] Not a valid project ID: kernelJVM", "")
      ))
      val timeout = CountingProcess(SbtSourceMappingRootProcessOutcome.TimedOut("", "[error] timeout"))
      val missing = CountingProcess(SbtSourceMappingRootProcessOutcome.Completed(SbtRunResult("task", 0, "", "")))

      assert(ProcessSbtSourceMappingRootAcquirer(1.second, switch).acquire(request).left.exists(_.isInstanceOf[SbtSourceMappingRootFailure.ScalaSwitch]))
      assert(ProcessSbtSourceMappingRootAcquirer(1.second, unknown).acquire(request).left.exists(_.isInstanceOf[SbtSourceMappingRootFailure.UnknownProject]))
      assert(ProcessSbtSourceMappingRootAcquirer(1.second, timeout).acquire(request).left.exists(_.isInstanceOf[SbtSourceMappingRootFailure.Process]))
      assert(ProcessSbtSourceMappingRootAcquirer(1.second, missing).acquire(request).left.exists(_.isInstanceOf[SbtSourceMappingRootFailure.Protocol]))
      List(switch, unknown, timeout, missing).foreach(process => assertEquals(process.calls, 1))
    finally Files.deleteIfExists(workspace)

  private final case class CountingProcess(outcome: SbtSourceMappingRootProcessOutcome)
      extends SbtSourceMappingRootProcess:
    var calls = 0
    override def run(
        request: SbtSourceMappingRootRequest,
        globalBase: Path,
        receiptFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtSourceMappingRootProcessOutcome =
      calls += 1
      outcome

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def scalaVersion(value: String): SbtScalaVersion =
    SbtScalaVersion.parse(value).fold(message => fail(message), identity)

  private def encoded(value: String): String =
    java.util.Base64.getEncoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
