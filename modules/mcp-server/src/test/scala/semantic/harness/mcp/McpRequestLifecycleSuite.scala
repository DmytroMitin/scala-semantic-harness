package semantic.harness.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import io.circe.Json

class McpRequestLifecycleSuite extends munit.FunSuite:
  test("all valid notifications are fire-and-forget, including unknown and cancelled"):
    val writer = CollectingWriter()
    val runtime = testRuntime(FixedLifecycleRunner(success("unused")), writer)
    try
      runtime.acceptLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","method":"notifications/unknown","params":{"value":1}}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","method":"ping"}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"missing"}}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{}}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":true}}""")
      assertEquals(writer.messages, Nil)
    finally runtime.shutdown()

  test("malformed JSON and structurally invalid messages fail deterministically"):
    val writer = CollectingWriter()
    val runtime = testRuntime(FixedLifecycleRunner(success("unused")), writer)
    try
      runtime.acceptLine("{")
      runtime.acceptLine("""{"jsonrpc":"2.0","id":"bad"}""")
      runtime.acceptLine("""{"id":"version","method":"ping"}""")
      assert(writer.awaitSize(3))
      assertEquals(errorCode(writer.messages.head), Some(-32700))
      assertEquals(errorCode(writer.messages(1)), Some(-32600))
      assertEquals(errorCode(writer.messages(2)), Some(-32600))
    finally runtime.shutdown()

  test("unsupported null and boolean IDs are rejected, while string and number IDs are distinct"):
    val writer = CollectingWriter()
    val runtime = testRuntime(FixedLifecycleRunner(success("unused")), writer)
    try
      runtime.acceptLine("""{"jsonrpc":"2.0","id":null,"method":"ping"}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","id":true,"method":"ping"}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
      runtime.acceptLine("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
      assert(writer.awaitSize(4))
      assertEquals(writer.messages.count(_.hcursor.downField("id").focus.contains(Json.Null)), 2)
      assert(writer.messages.exists(_.hcursor.downField("id").as[String].toOption.contains("1")))
      assert(writer.messages.exists(_.hcursor.downField("id").as[Int].toOption.contains(1)))
    finally runtime.shutdown()

  test("a cancellation received during a running tool suppresses its response and frees the ID"):
    withWorkspace("cancel-running") { workspace =>
      val runner = BlockingLifecycleRunner()
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer)
      try
        runtime.acceptLine(effectRequest("work", workspace))
        assert(runner.started.await(2, TimeUnit.SECONDS))
        runtime.acceptLine(cancel("work"))
        assert(awaitActive(runtime, 0))
        assertEquals(writer.messages, Nil)

        runtime.acceptLine("""{"jsonrpc":"2.0","id":"work","method":"ping"}""")
        assert(writer.awaitSize(1))
        assertEquals(writer.messages.head.hcursor.downField("id").as[String].toOption, Some("work"))
      finally
        runner.release.countDown()
        runtime.shutdown()
    }

  test("cancellation before a serialized build launch prevents the queued subprocess call"):
    withWorkspace("cancel-queued") { workspace =>
      val runner = BlockingLifecycleRunner()
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer, McpRuntimeConfig(maxActiveRequests = 2))
      try
        runtime.acceptLine(compileRequest("first", workspace))
        assert(runner.started.await(2, TimeUnit.SECONDS))
        runtime.acceptLine(compileRequest("queued", workspace))
        runtime.acceptLine(cancel("queued"))
        runner.release.countDown()

        assert(writer.awaitSize(1))
        assert(awaitActive(runtime, 0))
        assertEquals(runner.invocations.get(), 1)
        assertEquals(writer.messages.head.hcursor.downField("id").as[String].toOption, Some("first"))
      finally runtime.shutdown()
    }

  test("duplicate active IDs fail without overwriting the original request"):
    withWorkspace("duplicate") { workspace =>
      val runner = BlockingLifecycleRunner()
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer)
      try
        runtime.acceptLine(effectRequest("same", workspace))
        assert(runner.started.await(2, TimeUnit.SECONDS))
        runtime.acceptLine("""{"jsonrpc":"2.0","id":"same","method":"ping"}""")
        assert(writer.awaitSize(1))
        assertEquals(errorCode(writer.messages.head), Some(-32001))

        runner.release.countDown()
        assert(writer.awaitSize(2))
        assertEquals(writer.messages.count(_.hcursor.downField("id").as[String].toOption.contains("same")), 2)
      finally runtime.shutdown()
    }

  test("numerically equivalent active IDs are duplicates"):
    withWorkspace("numeric-duplicate") { workspace =>
      val runner = BlockingLifecycleRunner()
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer)
      try
        runtime.acceptLine(effectRequest("1", workspace).replace("\"1\"", "1"))
        assert(runner.started.await(2, TimeUnit.SECONDS))
        runtime.acceptLine("""{"jsonrpc":"2.0","id":1.0,"method":"ping"}""")
        assert(writer.awaitSize(1))
        assertEquals(errorCode(writer.messages.head), Some(-32001))
      finally
        runtime.acceptLine("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""")
        runner.release.countDown()
        runtime.shutdown()
    }

  test("the active-request bound rejects excess work but still admits cancellation"):
    withWorkspace("capacity") { workspace =>
      val runner = BlockingLifecycleRunner()
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer, McpRuntimeConfig(maxActiveRequests = 1))
      try
        runtime.acceptLine(effectRequest("active", workspace))
        assert(runner.started.await(2, TimeUnit.SECONDS))
        runtime.acceptLine("""{"jsonrpc":"2.0","id":"excess","method":"ping"}""")
        assert(writer.awaitSize(1))
        assertEquals(errorCode(writer.messages.head), Some(-32000))

        runtime.acceptLine(cancel("active"))
        assert(awaitActive(runtime, 0))
        assertEquals(writer.messages.size, 1)
      finally
        runner.release.countDown()
        runtime.shutdown()
    }

  test("build-capable tools are globally serialized"):
    withWorkspace("build-serialization") { workspace =>
      val runner = ConcurrencyRunner(75.millis)
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer, McpRuntimeConfig(maxActiveRequests = 2))
      try
        runtime.acceptLine(compileRequest("compile", workspace))
        runtime.acceptLine(errorsRequest("errors", workspace))
        assert(writer.awaitSize(2))
        assertEquals(runner.maximum.get(), 1)
      finally runtime.shutdown()
    }

  test("independent read tools can finish out of order without mixing IDs or payloads"):
    withTwoWorkspaces { (slow, fast) =>
      val runner = WorkspaceDelayRunner(slow)
      val writer = CollectingWriter()
      val runtime = testRuntime(runner, writer, McpRuntimeConfig(maxActiveRequests = 2))
      try
        runtime.acceptLine(effectRequest("slow-id", slow))
        runtime.acceptLine(effectRequest("fast-id", fast))
        assert(writer.awaitSize(2))
        assertEquals(writer.messages.head.hcursor.downField("id").as[String].toOption, Some("fast-id"))
        assertEquals(payloadToken(writer.messages.head), Some("fast"))
        assertEquals(payloadToken(writer.messages(1)), Some("slow"))
      finally runtime.shutdown()
    }

  test("request timeout returns a bounded typed tool error and leaves no active entry"):
    withWorkspace("timeout") { workspace =>
      val writer = CollectingWriter()
      val runtime = testRuntime(
        BlockingLifecycleRunner(),
        writer,
        McpRuntimeConfig(requestTimeout = 75.millis)
      )
      try
        runtime.acceptLine(effectRequest("timeout-id", workspace))
        assert(writer.awaitSize(1))
        assert(awaitActive(runtime, 0))
        val wrapper = structuredContent(writer.messages.head)
        assertEquals(wrapper.hcursor.downField("error").as[String].toOption, Some("semantic-scala request timed out"))
        assertEquals(wrapper.hcursor.downField("stderr").as[String].toOption, Some(""))
      finally runtime.shutdown()
    }

  test("response overflow is replaced atomically with a bounded JSON-RPC error"):
    withWorkspace("response-overflow") { workspace =>
      val writer = CollectingWriter(maxBytes = 256)
      val runtime = testRuntime(
        FixedLifecycleRunner(success("x" * 2048)),
        writer
      )
      try
        runtime.acceptLine(effectRequest("large", workspace))
        assert(writer.awaitSize(1))
        assertEquals(errorCode(writer.messages.head), Some(-32002))
        assertEquals(writer.rawLines.size, 1)
      finally runtime.shutdown()
    }

  test("public adapter failures redact absolute workspace, executable, argv, stderr, and environment text"):
    withWorkspace("privacy") { workspace =>
      val secretCli = Path.of("/private/machine/semantic-scala")
      val secret =
        s"${workspace.toAbsolutePath} $secretCli --token secret-token HOME=/private/home"
      val runner =
        FixedLifecycleRunner(ProcessResult(7, "", secret))
      val writer = CollectingWriter()
      val runtime =
        McpStdioRuntime(
          SemanticScalaMcpServer(SemanticScalaCli(secretCli, runner)),
          writer
        )
      try
        runtime.acceptLine(effectRequest("privacy-id", workspace))
        assert(writer.awaitSize(1))
        val rendered = writer.messages.head.noSpaces
        assert(!rendered.contains(workspace.toAbsolutePath.toString))
        assert(!rendered.contains(secretCli.toString))
        assert(!rendered.contains("secret-token"))
        assert(!rendered.contains("/private/home"))
        assert(!rendered.contains("--token"))
      finally runtime.shutdown()
    }

  test("shutdown cancels admitted work, suppresses late output, and drains the registry"):
    withWorkspace("shutdown") { workspace =>
      val runner = BlockingLifecycleRunner()
      val writer = CollectingWriter()
      val runtime =
        testRuntime(
          runner,
          writer,
          McpRuntimeConfig(shutdownGrace = 2.seconds)
        )
      runtime.acceptLine(effectRequest("shutdown-id", workspace))
      assert(runner.started.await(2, TimeUnit.SECONDS))
      val report = runtime.shutdown()
      assert(report.clean)
      assertEquals(writer.messages, Nil)
    }

  test("writer failure triggers cancellation and a bounded shutdown"):
    withWorkspace("writer-failure") { workspace =>
      val runner = BlockingLifecycleRunner()
      val runtime =
        testRuntime(
          runner,
          new McpMessageWriter:
            override def write(message: Json, requestId: Json): Boolean = false
        )
      runtime.acceptLine(effectRequest("running", workspace))
      assert(runner.started.await(2, TimeUnit.SECONDS))
      runtime.acceptLine("""{"jsonrpc":"2.0","id":"writer-fails","method":"ping"}""")
      assert(awaitActive(runtime, 0))
      assert(runtime.shutdown().clean)
    }

  private def testRuntime(
    runner: ProcessRunner,
    writer: McpMessageWriter,
    config: McpRuntimeConfig = McpRuntimeConfig()
  ): McpStdioRuntime =
    McpStdioRuntime(
      SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner)),
      writer,
      config
    )

  private def effectRequest(id: String, workspace: Path): String =
    s"""{"jsonrpc":"2.0","id":"$id","method":"tools/call","params":{"name":"semantic_effect_summary","arguments":{"workspace":"${workspace.toString}","file":"Main.scala"}}}"""

  private def compileRequest(id: String, workspace: Path): String =
    s"""{"jsonrpc":"2.0","id":"$id","method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":"${workspace.toString}"}}}"""

  private def errorsRequest(id: String, workspace: Path): String =
    s"""{"jsonrpc":"2.0","id":"$id","method":"tools/call","params":{"name":"semantic_errors","arguments":{"workspace":"${workspace.toString}"}}}"""

  private def cancel(id: String): String =
    s"""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"$id","reason":"test-only"}}"""

  private def success(token: String): ProcessResult =
    ProcessResult(
      0,
      s"""{"schemaVersion":"${SemanticScalaCli.EffectSummarySchemaVersion}","file":"Main.scala","methods":[],"requestToken":"$token"}""",
      ""
    )

  private def errorCode(message: Json): Option[Int] =
    message.hcursor.downField("error").downField("code").as[Int].toOption

  private def structuredContent(message: Json): Json =
    message.hcursor
      .downField("result")
      .downField("structuredContent")
      .focus
      .getOrElse(fail("Missing structuredContent"))

  private def payloadToken(message: Json): Option[String] =
    structuredContent(message)
      .hcursor
      .downField("payload")
      .downField("requestToken")
      .as[String]
      .toOption

  private def awaitActive(runtime: McpStdioRuntime, expected: Int): Boolean =
    val deadline = System.nanoTime() + 2.seconds.toNanos
    while runtime.activeRequestCount != expected && System.nanoTime() < deadline do
      Thread.sleep(10L)
    runtime.activeRequestCount == expected

  private def withWorkspace(name: String)(test: Path => Unit): Unit =
    val workspace = Files.createTempDirectory(s"task074-$name")
    Files.writeString(workspace.resolve("Main.scala"), "object Main")
    try test(workspace)
    finally delete(workspace)

  private def withTwoWorkspaces(test: (Path, Path) => Unit): Unit =
    val parent = Files.createTempDirectory("task074-out-of-order")
    val slow = Files.createDirectory(parent.resolve("slow"))
    val fast = Files.createDirectory(parent.resolve("fast"))
    Files.writeString(slow.resolve("Main.scala"), "object Slow")
    Files.writeString(fast.resolve("Main.scala"), "object Fast")
    try test(slow, fast)
    finally delete(parent)

  private def delete(path: Path): Unit =
    Files
      .walk(path)
      .iterator()
      .asScala
      .toList
      .sortBy(_.getNameCount)(Ordering.Int.reverse)
      .foreach(Files.deleteIfExists)

private final class CollectingWriter(maxBytes: Long = 1024L * 1024L) extends McpMessageWriter:
  private val output = java.io.ByteArrayOutputStream()
  private val delegate = BoundedLineWriter(output, maxBytes)

  override def write(message: Json, requestId: Json): Boolean =
    this.synchronized(delegate.write(message, requestId))

  def rawLines: List[String] =
    this.synchronized {
      output
        .toString(java.nio.charset.StandardCharsets.UTF_8)
        .linesIterator
        .toList
    }

  def messages: List[Json] =
    rawLines.map(line => io.circe.parser.parse(line).fold(throw _, identity))

  def awaitSize(size: Int): Boolean =
    val deadline = System.nanoTime() + 3.seconds.toNanos
    while messages.size < size && System.nanoTime() < deadline do
      Thread.sleep(10L)
    messages.size >= size

private final class FixedLifecycleRunner(result: ProcessResult) extends ProcessRunner:
  override def run(command: List[String], cwd: Path): ProcessResult = result

  override def run(
    command: List[String],
    cwd: Path,
    execution: ProcessExecution
  ): ProcessResult = result

private final class BlockingLifecycleRunner extends ProcessRunner:
  val started = CountDownLatch(1)
  val release = CountDownLatch(1)
  val invocations = AtomicInteger(0)

  override def run(command: List[String], cwd: Path): ProcessResult =
    ProcessResult(-1, "", "", Some(ProcessFailure.Cancelled))

  override def run(
    command: List[String],
    cwd: Path,
    execution: ProcessExecution
  ): ProcessResult =
    invocations.incrementAndGet()
    started.countDown()
    while release.getCount > 0 && !execution.isCancelled && !execution.isTimedOut do
      release.await(10L, TimeUnit.MILLISECONDS)
    if execution.isCancelled then ProcessResult(-1, "", "", Some(ProcessFailure.Cancelled))
    else if execution.isTimedOut then ProcessResult(-1, "", "", Some(ProcessFailure.TimedOut))
    else
      ProcessResult(
        0,
        s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","success":true,"diagnostics":[]}""",
        ""
      )

private final class ConcurrencyRunner(delay: FiniteDuration) extends ProcessRunner:
  private val current = AtomicInteger(0)
  val maximum = AtomicInteger(0)

  override def run(command: List[String], cwd: Path): ProcessResult =
    run(command, cwd, ProcessExecution.default)

  override def run(
    command: List[String],
    cwd: Path,
    execution: ProcessExecution
  ): ProcessResult =
    val active = current.incrementAndGet()
    var updated = false
    while !updated do
      val previous = maximum.get()
      updated = maximum.compareAndSet(previous, Math.max(previous, active))
    try
      Thread.sleep(delay.toMillis)
      val schema =
        if command.contains("errors") then SemanticScalaCli.ErrorsSchemaVersion
        else SemanticScalaCli.CompileSchemaVersion
      ProcessResult(0, s"""{"schemaVersion":"$schema","success":true,"diagnostics":[]}""", "")
    finally current.decrementAndGet()

private final class WorkspaceDelayRunner(slow: Path) extends ProcessRunner:
  override def run(command: List[String], cwd: Path): ProcessResult =
    run(command, cwd, ProcessExecution.default)

  override def run(
    command: List[String],
    cwd: Path,
    execution: ProcessExecution
  ): ProcessResult =
    val token =
      if cwd == slow.toAbsolutePath.normalize() then
        Thread.sleep(125L)
        "slow"
      else "fast"
    ProcessResult(
      0,
      s"""{"schemaVersion":"${SemanticScalaCli.EffectSummarySchemaVersion}","file":"Main.scala","methods":[],"requestToken":"$token"}""",
      ""
    )
