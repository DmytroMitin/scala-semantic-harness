package semantic.harness.mcp

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

final class CancellationSignal private[mcp] (private val cancelled: AtomicBoolean):
  def isCancelled: Boolean = cancelled.get()

  private[mcp] def cancel(): Unit =
    cancelled.set(true)

object CancellationSignal:
  private[mcp] def active(): CancellationSignal =
    CancellationSignal(AtomicBoolean(false))

  val never: CancellationSignal =
    CancellationSignal(AtomicBoolean(false))

final case class ProcessLimits(
  stdoutBytes: Long,
  stderrBytes: Long,
  aggregateBytes: Long
):
  require(stdoutBytes > 0, "stdoutBytes must be positive")
  require(stderrBytes > 0, "stderrBytes must be positive")
  require(aggregateBytes > 0, "aggregateBytes must be positive")

object ProcessLimits:
  val Default: ProcessLimits =
    ProcessLimits(
      stdoutBytes = 32L * 1024L * 1024L,
      stderrBytes = 4L * 1024L * 1024L,
      aggregateBytes = 36L * 1024L * 1024L
    )

final case class ProcessExecution private (
  cancellation: CancellationSignal,
  deadlineNanos: Long,
  limits: ProcessLimits,
  nanoTime: () => Long
):
  def isCancelled: Boolean = cancellation.isCancelled

  def isTimedOut: Boolean =
    nanoTime() - deadlineNanos >= 0L

object ProcessExecution:
  val DefaultTimeout: FiniteDuration = 10.minutes

  def create(
    cancellation: CancellationSignal = CancellationSignal.never,
    timeout: FiniteDuration = DefaultTimeout,
    limits: ProcessLimits = ProcessLimits.Default,
    nanoTime: () => Long = () => System.nanoTime()
  ): ProcessExecution =
    require(timeout > Duration.Zero, "timeout must be positive")
    val now = nanoTime()
    val timeoutNanos = timeout.toNanos
    val deadline =
      try Math.addExact(now, timeoutNanos)
      catch case _: ArithmeticException => Long.MaxValue
    ProcessExecution(cancellation, deadline, limits, nanoTime)

  def default: ProcessExecution = create()

enum ProcessFailure:
  case Cancelled
  case TimedOut
  case StdoutLimitExceeded
  case StderrLimitExceeded
  case AggregateLimitExceeded
  case InvalidUtf8
  case CleanupFailed
