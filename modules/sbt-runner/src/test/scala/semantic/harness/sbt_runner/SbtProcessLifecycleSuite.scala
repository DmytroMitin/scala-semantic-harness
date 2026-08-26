package semantic.harness.sbt_runner

import java.nio.file.Path
import java.nio.charset.StandardCharsets

class SbtProcessLifecycleSuite extends munit.FunSuite:
  test("command vector owns one foreground server and one fixed command"):
    val vector = SbtProcessLifecycle.commandVector(
      Path.of("/tmp/request-global"),
      "project passing; Test / test"
    )

    assertEquals(
      vector,
      List(
        "sbt",
        "--server",
        "--batch",
        "-Dsbt.log.noformat=true",
        "-Dsbt.supershell=false",
        "-Dsbt.global.base=/tmp/request-global",
        "project passing; Test / test"
      )
    )

  test("bounded drain retains late diagnostics after large build output"):
    val input = new java.io.ByteArrayInputStream(
      ("early-build-noise\n" + ("x" * 256) + "\n[error] late selected-project failure\n")
        .getBytes(StandardCharsets.UTF_8)
    )
    val drain = BoundedSbtStreamDrain(input, 96)

    drain.start()
    drain.join()

    assert(drain.result.startsWith("... output truncated to bounded tail ...\n"), clue(drain.result))
    assert(drain.result.contains("late selected-project failure"), clue(drain.result))
    assert(!drain.result.contains("early-build-noise"), clue(drain.result))

  test("workspace paths are sanitized before subprocess output becomes public evidence"):
    val workspace = Path.of("/home/operator/private-project")
    val output =
      "[error] /home/operator/private-project/src/test/scala/example/Suite.scala:5"

    assertEquals(
      SbtProcessLifecycle.sanitizeWorkspace(output, workspace),
      "[error] <workspace>/src/test/scala/example/Suite.scala:5"
    )
