package semantic.harness.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import io.circe.Json
import io.circe.parser.parse

class McpStatelessReadinessSuite extends munit.FunSuite:
  test("tool listing is deterministic before initialize, after initialize, and after restart"):
    val runner = FixedRunner(success("unused"))
    val first = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))
    val second = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val beforeInitialize = tools(first)
    response(
      first,
      """{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    )
    val afterInitialize = tools(first)
    val afterRestart = tools(second)

    assertEquals(afterInitialize, beforeInitialize)
    assertEquals(afterRestart, beforeInitialize)
    assertEquals(
      beforeInitialize.flatMap(_.hcursor.downField("name").as[String].toOption),
      List(
        "semantic_compile",
        "semantic_errors",
        "semantic_test",
        "semantic_effect_summary",
        "semantic_symbol_at",
        "semantic_symbols",
        "semantic_reconcile_symbol",
        "semantic_point_evidence"
      )
    )

  test("concurrent core calls keep independent workspace, id, payload, and stderr"):
    withTwoWorkspaces { (successfulWorkspace, failingWorkspace) =>
      val runner = BarrierRunner(successfulWorkspace, failingWorkspace)
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val successful = Future(
        response(server, readRequest("success-id", successfulWorkspace))
      )
      val failing = Future(
        response(server, readRequest("failure-id", failingWorkspace))
      )

      val successfulResponse = Await.result(successful, 5.seconds)
      val failingResponse = Await.result(failing, 5.seconds)
      val successfulWrapper = structuredContent(successfulResponse)
      val failingWrapper = structuredContent(failingResponse)

      assertEquals(successfulResponse.hcursor.downField("id").as[String].toOption, Some("success-id"))
      assertEquals(failingResponse.hcursor.downField("id").as[String].toOption, Some("failure-id"))
      assertEquals(successfulResponse.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(failingResponse.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(successfulWrapper.hcursor.downField("payload").downField("requestToken").as[String].toOption, Some("successful"))
      assertEquals(successfulWrapper.hcursor.downField("stderr").as[String].toOption, Some("success-stderr"))
      assertEquals(failingWrapper.hcursor.downField("stderr").as[String].toOption, Some(""))
      assertEquals(
        failingWrapper.hcursor.downField("error").as[String].toOption,
        Some("semantic-scala command failed with exit code 7")
      )
    }

  test("core permits out-of-order completion and treats repeated ids as request local"):
    withTwoWorkspaces { (slowWorkspace, fastWorkspace) =>
      val runner = OrderedRunner(slowWorkspace)
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val slow = Future(response(server, readRequest("repeated-id", slowWorkspace)))
      assert(runner.slowStarted.await(2, TimeUnit.SECONDS), "slow call did not start")

      val fast = Future(response(server, readRequest("repeated-id", fastWorkspace)))
      val fastResponse = Await.result(fast, 2.seconds)
      runner.releaseSlow.countDown()
      val slowResponse = Await.result(slow, 2.seconds)

      assertEquals(fastResponse.hcursor.downField("id").as[String].toOption, Some("repeated-id"))
      assertEquals(slowResponse.hcursor.downField("id").as[String].toOption, Some("repeated-id"))
      assertEquals(
        structuredContent(fastResponse).hcursor.downField("payload").downField("requestToken").as[String].toOption,
        Some("fast")
      )
      assertEquals(
        structuredContent(slowResponse).hcursor.downField("payload").downField("requestToken").as[String].toOption,
        Some("slow")
      )
    }

  private def readRequest(id: String, workspace: Path): String =
    s"""{"jsonrpc":"2.0","id":"$id","method":"tools/call","params":{"name":"semantic_effect_summary","arguments":{"workspace":"${workspace.toString}","file":"Main.scala"}}}"""

  private def tools(server: SemanticScalaMcpServer): List[Json] =
    response(server, """{"jsonrpc":"2.0","id":"tools","method":"tools/list"}""")
      .hcursor
      .downField("result")
      .downField("tools")
      .as[List[Json]]
      .fold(error => fail(error.message), identity)

  private def response(server: SemanticScalaMcpServer, request: String): Json =
    val raw = server.handleLine(request).getOrElse(fail("Expected response"))
    parse(raw).fold(error => fail(error.message), identity)

  private def structuredContent(response: Json): Json =
    response.hcursor
      .downField("result")
      .downField("structuredContent")
      .focus
      .getOrElse(fail("Missing structuredContent"))

  private def success(token: String, stderr: String = ""): ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout =
        s"""{"schemaVersion":"${SemanticScalaCli.EffectSummarySchemaVersion}","file":"Main.scala","methods":[],"requestToken":"$token"}""",
      stderr = stderr
    )

  private def withTwoWorkspaces(test: (Path, Path) => Unit): Unit =
    val parent = Files.createTempDirectory("semantic-scala-mcp-readiness")
    val first = Files.createDirectory(parent.resolve("first"))
    val second = Files.createDirectory(parent.resolve("second"))
    Files.writeString(first.resolve("Main.scala"), "object First")
    Files.writeString(second.resolve("Main.scala"), "object Second")
    try test(first, second)
    finally
      Files
        .walk(parent)
        .iterator()
        .asScala
        .toList
        .sortBy(_.getNameCount)(Ordering.Int.reverse)
        .foreach(Files.deleteIfExists)

private final class FixedRunner(result: ProcessResult) extends ProcessRunner:
  override def run(command: List[String], cwd: Path): ProcessResult = result

private final class BarrierRunner(
  successfulWorkspace: Path,
  failingWorkspace: Path
) extends ProcessRunner:
  private val barrier = java.util.concurrent.CyclicBarrier(2)

  override def run(command: List[String], cwd: Path): ProcessResult =
    barrier.await(2, TimeUnit.SECONDS)
    if cwd == successfulWorkspace.toAbsolutePath.normalize() then
      ProcessResult(
        exitCode = 0,
        stdout =
          s"""{"schemaVersion":"${SemanticScalaCli.EffectSummarySchemaVersion}","file":"Main.scala","methods":[],"requestToken":"successful"}""",
        stderr = "success-stderr"
      )
    else if cwd == failingWorkspace.toAbsolutePath.normalize() then
      ProcessResult(exitCode = 7, stdout = "failure-stdout", stderr = "failure-stderr")
    else throw IllegalArgumentException(s"Unexpected workspace: $cwd")

private final class OrderedRunner(slowWorkspace: Path) extends ProcessRunner:
  val slowStarted = CountDownLatch(1)
  val releaseSlow = CountDownLatch(1)

  override def run(command: List[String], cwd: Path): ProcessResult =
    val token =
      if cwd == slowWorkspace.toAbsolutePath.normalize() then
        slowStarted.countDown()
        if !releaseSlow.await(2, TimeUnit.SECONDS) then
          throw RuntimeException("slow request was not released")
        "slow"
      else "fast"

    ProcessResult(
      exitCode = 0,
      stdout =
        s"""{"schemaVersion":"${SemanticScalaCli.EffectSummarySchemaVersion}","file":"Main.scala","methods":[],"requestToken":"$token"}""",
      stderr = s"$token-stderr"
    )
