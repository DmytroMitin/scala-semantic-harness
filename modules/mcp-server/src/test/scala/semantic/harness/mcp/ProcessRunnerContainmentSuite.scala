package semantic.harness.mcp

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class ProcessRunnerContainmentSuite extends munit.FunSuite:
  test("stdout byte overflow fails closed"):
    withWorkspace { workspace =>
      val result =
        ProcessRunner.default.run(
          List("/bin/sh", "-c", "printf 123456789"),
          workspace,
          execution(ProcessLimits(4, 32, 36))
        )
      assertEquals(result.failure, Some(ProcessFailure.StdoutLimitExceeded))
      assert(result.stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4)
    }

  test("stderr byte overflow fails closed"):
    withWorkspace { workspace =>
      val result =
        ProcessRunner.default.run(
          List("/bin/sh", "-c", "printf 123456789 >&2"),
          workspace,
          execution(ProcessLimits(32, 4, 36))
        )
      assertEquals(result.failure, Some(ProcessFailure.StderrLimitExceeded))
      assert(result.stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4)
    }

  test("aggregate byte overflow fails closed"):
    withWorkspace { workspace =>
      val result =
        ProcessRunner.default.run(
          List("/bin/sh", "-c", "printf 1234; printf 5678 >&2"),
          workspace,
          execution(ProcessLimits(8, 8, 6))
        )
      assertEquals(result.failure, Some(ProcessFailure.AggregateLimitExceeded))
    }

  test("exact byte limits succeed"):
    withWorkspace { workspace =>
      val result =
        ProcessRunner.default.run(
          List("/bin/sh", "-c", "printf 1234; printf 5678 >&2"),
          workspace,
          execution(ProcessLimits(4, 4, 8))
        )
      assertEquals(result.failure, None)
      assertEquals(result.stdout, "1234")
      assertEquals(result.stderr, "5678")
    }

  test("invalid UTF-8 fails closed"):
    withWorkspace { workspace =>
      val result =
        ProcessRunner.default.run(
          List("/bin/sh", "-c", "printf '\\377'"),
          workspace,
          execution(ProcessLimits(8, 8, 16))
        )
      assertEquals(result.failure, Some(ProcessFailure.InvalidUtf8))
    }

  test("timeout terminates and reaps the direct child"):
    withWorkspace { workspace =>
      val started = System.nanoTime()
      val result =
        ProcessRunner.default.run(
          List("/bin/sh", "-c", "sleep 30"),
          workspace,
          ProcessExecution.create(timeout = 100.millis)
        )
      val elapsed = (System.nanoTime() - started).nanos
      assertEquals(result.failure, Some(ProcessFailure.TimedOut))
      assert(elapsed < 5.seconds)
    }

  test("cancellation terminates the owned descendant tree"):
    withWorkspace { workspace =>
      val signal = CancellationSignal.active()
      val result =
        Future(
          ProcessRunner.default.run(
            List("/bin/sh", "-c", "sleep 30 & echo $! > child.pid; wait"),
            workspace,
            ProcessExecution.create(cancellation = signal, timeout = 10.seconds)
          )
        )

      val childPidFile = workspace.resolve("child.pid")
      val deadline = System.nanoTime() + 2.seconds.toNanos
      while !Files.exists(childPidFile) && System.nanoTime() < deadline do Thread.sleep(10L)
      assert(Files.exists(childPidFile))
      val childPid = Files.readString(childPidFile).trim.toLong

      signal.cancel()
      val completed = Await.result(result, 5.seconds)
      assertEquals(completed.failure, Some(ProcessFailure.Cancelled))
      assert(!java.lang.ProcessHandle.of(childPid).map(_.isAlive).orElse(false))
    }

  private def execution(limits: ProcessLimits): ProcessExecution =
    ProcessExecution.create(timeout = 5.seconds, limits = limits)

  private def withWorkspace(test: Path => Unit): Unit =
    val workspace = Files.createTempDirectory("task074-process-runner")
    try test(workspace)
    finally
      Files
        .walk(workspace)
        .iterator()
        .asScala
        .toList
        .sortBy(_.getNameCount)(Ordering.Int.reverse)
        .foreach(Files.deleteIfExists)
