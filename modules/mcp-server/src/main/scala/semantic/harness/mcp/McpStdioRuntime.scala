package semantic.harness.mcp

import java.io.BufferedReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal

import io.circe.Json
import io.circe.parser.parse

final case class McpRuntimeConfig(
  maxActiveRequests: Int = 8,
  requestTimeout: FiniteDuration = ProcessExecution.DefaultTimeout,
  processLimits: ProcessLimits = ProcessLimits.Default,
  maxResponseBytes: Long = 68L * 1024L * 1024L,
  shutdownGrace: FiniteDuration = 10.seconds,
  nanoTime: () => Long = () => System.nanoTime()
):
  require(maxActiveRequests > 0, "maxActiveRequests must be positive")
  require(requestTimeout > Duration.Zero, "requestTimeout must be positive")
  require(maxResponseBytes > 0, "maxResponseBytes must be positive")
  require(shutdownGrace > Duration.Zero, "shutdownGrace must be positive")

final case class McpShutdownReport(
  activeRequests: Int,
  executorTerminated: Boolean
):
  def clean: Boolean = activeRequests == 0 && executorTerminated

trait McpMessageWriter:
  def write(message: Json, requestId: Json): Boolean

final class BoundedLineWriter(
  output: OutputStream,
  maxResponseBytes: Long
) extends McpMessageWriter:
  require(maxResponseBytes > 0, "maxResponseBytes must be positive")

  override def write(message: Json, requestId: Json): Boolean =
    val encoded = encode(message)
    val selected =
      if encoded.length <= maxResponseBytes then encoded
      else
        encode(
          SemanticScalaMcpServer.jsonRpcError(
            requestId,
            SemanticScalaMcpServer.JsonRpcErrors.ResponseTooLarge,
            "Response exceeded the configured limit"
          )
        )

    if selected.length > maxResponseBytes then false
    else
      this.synchronized {
        try
          output.write(selected)
          output.write('\n')
          output.flush()
          true
        catch case NonFatal(_) => false
      }

  private def encode(message: Json): Array[Byte] =
    message.noSpaces.getBytes(StandardCharsets.UTF_8)

final class McpStdioRuntime(
  server: SemanticScalaMcpServer,
  writer: McpMessageWriter,
  config: McpRuntimeConfig = McpRuntimeConfig()
):
  import McpStdioRuntime.*

  private val accepting = AtomicBoolean(true)
  private val registry = ActiveRequestRegistry(config.maxActiveRequests)
  private val executor =
    Executors.newFixedThreadPool(config.maxActiveRequests, RuntimeThreadFactory)

  def run(reader: BufferedReader): McpShutdownReport =
    var line = reader.readLine()
    while line != null && accepting.get() do
      acceptLine(line)
      line = reader.readLine()
    shutdown()

  def acceptLine(line: String): Unit =
    if accepting.get() then
      val trimmed = line.trim
      if trimmed.nonEmpty then
        parse(trimmed) match
          case Left(error) =>
            emit(
              SemanticScalaMcpServer.jsonRpcError(
                Json.Null,
                SemanticScalaMcpServer.JsonRpcErrors.ParseError,
                s"Parse error: ${bounded(error.message)}"
              ),
              Json.Null
            )
          case Right(message) =>
            classify(message)

  def activeRequestCount: Int =
    registry.size

  def shutdown(): McpShutdownReport =
    accepting.set(false)
    registry.cancelAll()
    executor.shutdown()
    val firstWait =
      try executor.awaitTermination(config.shutdownGrace.toMillis, TimeUnit.MILLISECONDS)
      catch
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          false
    if !firstWait then
      registry.cancelAll()
      executor.shutdownNow()
    val terminated =
      if firstWait then true
      else
        try executor.awaitTermination(config.shutdownGrace.toMillis, TimeUnit.MILLISECONDS)
        catch
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
            false
    McpShutdownReport(registry.size, terminated)

  private def classify(message: Json): Unit =
    val cursor = message.hcursor
    if cursor.downField("jsonrpc").as[String].toOption != Some("2.0") then
      invalidRequest(message, "Invalid JSON-RPC version")
    else
      cursor.downField("method").as[String] match
        case Left(_) =>
          invalidRequest(message, "Invalid request: missing method")
        case Right(method) =>
          cursor.downField("id").focus match
            case None =>
              handleNotification(method, message)
            case Some(id) =>
              supportedId(id) match
                case None =>
                  emit(
                    SemanticScalaMcpServer.jsonRpcError(
                      Json.Null,
                      SemanticScalaMcpServer.JsonRpcErrors.InvalidRequest,
                      "Invalid request ID"
                    ),
                    Json.Null
                  )
                case Some(key) =>
                  submitRequest(key, id, method, message)

  private def invalidRequest(message: Json, error: String): Unit =
    val candidate = message.hcursor.downField("id").focus.getOrElse(Json.Null)
    val id = supportedId(candidate).map(_ => candidate).getOrElse(Json.Null)
    emit(
      SemanticScalaMcpServer.jsonRpcError(
        id,
        SemanticScalaMcpServer.JsonRpcErrors.InvalidRequest,
        error
      ),
      id
    )

  private def handleNotification(method: String, message: Json): Unit =
    if method == "notifications/cancelled" then
      val requestId =
        message.hcursor.downField("params").downField("requestId").focus
      requestId.flatMap(supportedId).foreach(registry.cancel)

  private def submitRequest(
    key: RequestIdKey,
    id: Json,
    method: String,
    message: Json
  ): Unit =
    val signal = CancellationSignal.active()
    registry.register(key, signal, cancellable = method != "initialize") match
      case RegisterResult.Duplicate =>
        emit(
          SemanticScalaMcpServer.jsonRpcError(
            id,
            SemanticScalaMcpServer.JsonRpcErrors.DuplicateRequestId,
            "Duplicate active request ID"
          ),
          id
        )
      case RegisterResult.Full =>
        emit(
          SemanticScalaMcpServer.jsonRpcError(
            id,
            SemanticScalaMcpServer.JsonRpcErrors.ServerBusy,
            "Server is busy"
          ),
          id
        )
      case RegisterResult.Accepted(entry) =>
        try
          executor.execute(() => executeRequest(key, entry, id, message))
        catch
          case _: RejectedExecutionException =>
            registry.remove(key, entry)
            if entry.claimResponse() then
              emit(
                SemanticScalaMcpServer.jsonRpcError(
                  id,
                  SemanticScalaMcpServer.JsonRpcErrors.ServerBusy,
                  "Server is shutting down"
                ),
                id
              )
            entry.finish()

  private def executeRequest(
    key: RequestIdKey,
    entry: ActiveRequest,
    id: Json,
    message: Json
  ): Unit =
    try
      val execution =
        ProcessExecution.create(
          cancellation = entry.cancellation,
          timeout = config.requestTimeout,
          limits = config.processLimits,
          nanoTime = config.nanoTime
        )
      val response =
        try
          server
            .handleMessage(message, execution)
            .getOrElse(
              SemanticScalaMcpServer.jsonRpcError(
                id,
                SemanticScalaMcpServer.JsonRpcErrors.InternalError,
                "Request produced no response"
              )
            )
        catch
          case NonFatal(_) =>
            SemanticScalaMcpServer.jsonRpcError(
              id,
              SemanticScalaMcpServer.JsonRpcErrors.InternalError,
              "Internal server error"
            )

      if entry.claimResponse() then emit(response, id)
    finally
      entry.finish()
      registry.remove(key, entry)

  private def emit(message: Json, id: Json): Unit =
    if !writer.write(message, id) then
      accepting.set(false)
      registry.cancelAll()
      executor.shutdown()

  private def supportedId(json: Json): Option[RequestIdKey] =
    json.asString
      .map(RequestIdKey.StringId.apply)
      .orElse(
        json.asNumber
          .flatMap(_.toBigDecimal)
          .map(RequestIdKey.NumberId.apply)
      )

  private def bounded(message: String): String =
    if message.length <= 256 then message
    else message.take(256)

private object McpStdioRuntime:
  private val threadCounter = AtomicInteger(0)

  private object RuntimeThreadFactory extends ThreadFactory:
    override def newThread(runnable: Runnable): Thread =
      val thread =
        Thread(
          runnable,
          s"semantic-scala-mcp-request-${threadCounter.incrementAndGet()}"
        )
      thread.setDaemon(false)
      thread

private enum RequestIdKey:
  case StringId(value: String)
  case NumberId(value: BigDecimal)

private enum RegisterResult:
  case Accepted(entry: ActiveRequest)
  case Duplicate
  case Full

private final class ActiveRequest(
  val cancellation: CancellationSignal,
  cancellable: Boolean
):
  private val state = AtomicReference[RequestState](RequestState.Active)

  def cancel(): Unit =
    if cancellable && state.compareAndSet(RequestState.Active, RequestState.Cancelled) then
      cancellation.cancel()

  def claimResponse(): Boolean =
    state.compareAndSet(RequestState.Active, RequestState.Responding)

  def finish(): Unit =
    state.set(RequestState.Finished)

private enum RequestState:
  case Active
  case Cancelled
  case Responding
  case Finished

private final class ActiveRequestRegistry(maxActive: Int):
  private val entries = mutable.HashMap.empty[RequestIdKey, ActiveRequest]

  def register(
    key: RequestIdKey,
    cancellation: CancellationSignal,
    cancellable: Boolean
  ): RegisterResult =
    this.synchronized {
      if entries.contains(key) then RegisterResult.Duplicate
      else if entries.size >= maxActive then RegisterResult.Full
      else
        val entry = ActiveRequest(cancellation, cancellable)
        entries.put(key, entry)
        RegisterResult.Accepted(entry)
    }

  def cancel(key: RequestIdKey): Unit =
    this.synchronized(entries.get(key)).foreach(_.cancel())

  def remove(key: RequestIdKey, expected: ActiveRequest): Unit =
    this.synchronized {
      entries.get(key).filter(_ eq expected).foreach(_ => entries.remove(key))
    }

  def cancelAll(): Unit =
    this.synchronized(entries.values.toList).foreach(_.cancel())

  def size: Int =
    this.synchronized(entries.size)
