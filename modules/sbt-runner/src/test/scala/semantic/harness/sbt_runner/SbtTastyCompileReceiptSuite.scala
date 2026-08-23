package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt

class SbtTastyCompileReceiptSuite extends munit.FunSuite:
  test("receipt request requires one contained workspace-relative regular Scala source"):
    val workspace = Files.createTempDirectory("tasty-receipt-request-")
    val source = workspace.resolve("src/main/scala/example/Main.scala")
    Files.createDirectories(source.getParent)
    Files.writeString(source, "package example\nobject Main\n")
    val outside = Files.createTempFile("tasty-receipt-outside-", ".scala")
    val text = workspace.resolve("README.md")
    Files.writeString(text, "not Scala")
    val symlink = workspace.resolve("Linked.scala")
    Files.createSymbolicLink(symlink, source)
    try
      val valid = SbtTastyCompileRequest.validate(
        SbtTastyCompileRequest(workspace, project("app"), Path.of("src/main/scala/example/Main.scala"))
      )
      assertEquals(valid.map(_.workspace), Right(workspace.toRealPath()))
      assertEquals(valid.map(_.source), Right(source.toRealPath()))
      assertEquals(valid.map(_.sourceRelative), Right("src/main/scala/example/Main.scala"))

      val invalid = List(
        SbtTastyCompileRequest(workspace, project("app"), source.toAbsolutePath),
        SbtTastyCompileRequest(workspace, project("app"), Path.of("../" + outside.getFileName)),
        SbtTastyCompileRequest(workspace, project("app"), workspace.relativize(text)),
        SbtTastyCompileRequest(workspace, project("app"), workspace.relativize(symlink))
      )
      invalid.foreach(request => assert(SbtTastyCompileRequest.validate(request).isLeft))
    finally
      Files.deleteIfExists(symlink)
      Files.deleteIfExists(text)
      Files.deleteIfExists(source)
      Files.deleteIfExists(source.getParent)
      Files.deleteIfExists(source.getParent.getParent)
      Files.deleteIfExists(source.getParent.getParent.getParent)
      Files.deleteIfExists(source.getParent.getParent.getParent.getParent)
      Files.deleteIfExists(workspace.resolve("src"))
      Files.deleteIfExists(workspace)
      Files.deleteIfExists(outside)

  test("receipt model freezes Compile scope and selected Java context without public paths"):
    val workspace = Files.createTempDirectory("tasty-receipt-model-")
    val source = workspace.resolve("Main.scala")
    val output = workspace.resolve("target/classes")
    Files.writeString(source, "object Main\n")
    Files.createDirectories(output)
    try
      val receipt = SbtTastyCompileReceipt(
        project = project("app"),
        configuration = SbtClasspathConfiguration.Compile,
        compileStatus = SbtTastyCompileStatus.Succeeded,
        scalaVersion = Some("3.8.4"),
        classDirectory = Some(output),
        sourceIncluded = true,
        targetJavaContext = Some("sha256:private-token")
      )
      assertEquals(receipt.configuration, SbtClasspathConfiguration.Compile)
      assertEquals(SbtTastyCompileStatus.value(receipt.compileStatus), "Succeeded")
      assertEquals(receipt.targetJavaContext, Some("sha256:private-token"))
    finally
      Files.deleteIfExists(output)
      Files.deleteIfExists(output.getParent)
      Files.deleteIfExists(source)
      Files.deleteIfExists(workspace)

  test("receipt protocol round-trips one successful fixed-Compile receipt"):
    val workspace = Files.createTempDirectory("tasty-receipt-protocol-")
    val output = workspace.resolve("target/classes")
    Files.createDirectories(output)
    try
      val request = SbtTastyCompileRequest(
        workspace,
        project("pluginTests"),
        Path.of("Example.scala"),
        sourceRelative = "Example.scala"
      )
      val receipt = SbtTastyCompileReceipt(
        project("pluginTests"),
        SbtClasspathConfiguration.Compile,
        SbtTastyCompileStatus.Succeeded,
        Some("3.8.4"),
        Some(output),
        sourceIncluded = true,
        targetJavaContext = None
      )
      val encoded = SbtTastyCompileProtocol.render(receipt)
      assert(encoded.startsWith(SbtTastyCompileProtocol.Format + "\n"))
      assertEquals(SbtTastyCompileProtocol.parse(encoded, request), Right(receipt))
    finally
      Files.deleteIfExists(output)
      Files.deleteIfExists(output.getParent)
      Files.deleteIfExists(workspace)

  test("receipt protocol rejects malformed unknown duplicate and oversized records"):
    val workspace = Files.createTempDirectory("tasty-receipt-protocol-invalid-")
    try
      val request = SbtTastyCompileRequest(
        workspace,
        project("app"),
        Path.of("Main.scala"),
        sourceRelative = "Main.scala"
      )
      val malformed = List(
        "wrong-format\n",
        SbtTastyCompileProtocol.Format + "\nunknown\tvalue\n",
        SbtTastyCompileProtocol.Format + "\nstatus\tSucceeded\nstatus\tFailed\n"
      )
      malformed.foreach(value => assert(SbtTastyCompileProtocol.parse(value, request).isLeft))
      assert(
        SbtTastyCompileProtocol
          .parse("x" * (SbtTastyCompileProtocol.MaxProtocolBytes + 1), request)
          .isLeft
      )
    finally Files.deleteIfExists(workspace)

  test("receipt injection owns the fixed task and never exports target compiler options"):
    val workspace = Files.createTempDirectory("tasty-receipt-injection-")
    val source = workspace.resolve("Main.scala")
    Files.writeString(source, "object Main\n")
    try
      val request = SbtTastyCompileRequest
        .validate(SbtTastyCompileRequest(workspace, project("app"), Path.of("Main.scala")))
        .fold(message => fail(message), identity)
      val settings = SbtTastyCompileInjection.globalSettings(request)
      assert(settings.contains(SbtTastyCompileProtocol.Format))
      assert(settings.contains("Compile / compile"))
      assert(settings.contains("Compile / classDirectory"))
      assert(settings.contains("Compile / sources"))
      assert(!settings.contains("scalacOptions"))
      assert(!settings.contains("-Xplugin"))
      assert(!settings.contains("-P:"))
      assertEquals(SbtTastyCompileInjection.Task, "semanticScalaInternalTastyCompileReceipt")
    finally
      Files.deleteIfExists(source)
      Files.deleteIfExists(workspace)

  test("process acquirer writes isolated settings parses the receipt and cleans scratch"):
    val workspace = Files.createTempDirectory("tasty-receipt-acquirer-")
    val source = workspace.resolve("Main.scala")
    val output = workspace.resolve("target/classes")
    Files.writeString(source, "object Main\n")
    Files.createDirectories(output)
    val process = SuccessfulReceiptProcess(output)
    try
      val request = SbtTastyCompileRequest(
        workspace,
        project("app"),
        Path.of("Main.scala")
      )
      val result = ProcessSbtTastyCompileReceiptAcquirer(5.seconds, process).acquire(request)
      assertEquals(result.map(_.compileStatus), Right(SbtTastyCompileStatus.Succeeded))
      assertEquals(result.flatMap(_.scalaVersion.toRight("missing")), Right("3.8.4"))
      assertEquals(process.tasks, List(SbtTastyCompileInjection.Task))
      assert(process.settings.exists(_.contains(SbtTastyCompileProtocol.Format)))
      assert(process.globalBases.forall(path => !Files.exists(path.getParent)))
    finally
      Files.deleteIfExists(output)
      Files.deleteIfExists(output.getParent)
      Files.deleteIfExists(source)
      Files.deleteIfExists(workspace)

  test("process acquirer keeps compile failure distinct from launch and protocol failure"):
    val workspace = Files.createTempDirectory("tasty-receipt-acquirer-failure-")
    val source = workspace.resolve("Main.scala")
    Files.writeString(source, "object Main\n")
    val request = SbtTastyCompileRequest(workspace, project("app"), Path.of("Main.scala"))
    try
      val compileFailed = ProcessSbtTastyCompileReceiptAcquirer(
        5.seconds,
        ReceiptWritingProcess(
          SbtTastyCompileReceipt(
            project("app"),
            SbtClasspathConfiguration.Compile,
            SbtTastyCompileStatus.Failed,
            Some("3.8.4"),
            Some(workspace.resolve("target/classes")),
            sourceIncluded = true,
            targetJavaContext = None
          )
        )
      ).acquire(request)
      assertEquals(compileFailed.map(_.compileStatus), Right(SbtTastyCompileStatus.Failed))

      val timeout = ProcessSbtTastyCompileReceiptAcquirer(
        5.seconds,
        FixedReceiptProcess(SbtTastyCompileProcessOutcome.TimedOut)
      ).acquire(request)
      val missing = ProcessSbtTastyCompileReceiptAcquirer(
        5.seconds,
        FixedReceiptProcess(SbtTastyCompileProcessOutcome.Completed(0))
      ).acquire(request)
      assert(timeout.left.exists(_.isInstanceOf[SbtTastyCompileFailure.Process]))
      assert(missing.left.exists(_.isInstanceOf[SbtTastyCompileFailure.Protocol]))
    finally
      Files.deleteIfExists(source)
      Files.deleteIfExists(workspace)

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private final case class SuccessfulReceiptProcess(output: Path)
      extends SbtTastyCompileProcess:
    var tasks = List.empty[String]
    var settings = List.empty[String]
    var globalBases = List.empty[Path]

    override def run(
        request: SbtTastyCompileRequest,
        globalBase: Path,
        receiptFile: Path,
        task: String,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtTastyCompileProcessOutcome =
      tasks = tasks :+ task
      globalBases = globalBases :+ globalBase
      settings = settings :+ Files.readString(globalBase.resolve("global.sbt"))
      val receipt = SbtTastyCompileReceipt(
        request.project,
        SbtClasspathConfiguration.Compile,
        SbtTastyCompileStatus.Succeeded,
        Some("3.8.4"),
        Some(output),
        sourceIncluded = true,
        targetJavaContext = request.targetJava.map(SbtJavaContext.token)
      )
      Files.writeString(receiptFile, SbtTastyCompileProtocol.render(receipt))
      SbtTastyCompileProcessOutcome.Completed(0)

  private final case class ReceiptWritingProcess(receipt: SbtTastyCompileReceipt)
      extends SbtTastyCompileProcess:
    override def run(
        request: SbtTastyCompileRequest,
        globalBase: Path,
        receiptFile: Path,
        task: String,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtTastyCompileProcessOutcome =
      Files.writeString(receiptFile, SbtTastyCompileProtocol.render(receipt))
      SbtTastyCompileProcessOutcome.Completed(0)

  private final case class FixedReceiptProcess(outcome: SbtTastyCompileProcessOutcome)
      extends SbtTastyCompileProcess:
    override def run(
        request: SbtTastyCompileRequest,
        globalBase: Path,
        receiptFile: Path,
        task: String,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtTastyCompileProcessOutcome = outcome
