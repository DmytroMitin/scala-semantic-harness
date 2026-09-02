package semantic.harness.sbt_runner

import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Comparator
import scala.jdk.CollectionConverters.*

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

  test("no sandbox leaves the operation global base as the only effective setting"):
    val environment = new java.util.HashMap[String, String]()

    SbtSandbox.configure(environment)

    assertEquals(Option(environment.get("SBT_OPTS")), None)
    assertEquals(globalBaseOptions(environment), Nil)
    assertEquals(
      SbtProcessLifecycle.commandVector(Path.of("/tmp/request-global"), "projects")
        .filter(_.startsWith("-Dsbt.global.base=")),
      List("-Dsbt.global.base=/tmp/request-global")
    )

  test("no sandbox removes a conflicting inherited global base"):
    val environment = new java.util.HashMap[String, String]()
    environment.put("SBT_OPTS", "-Dexample.flag=true -Dsbt.global.base=/tmp/inherited-global")

    SbtSandbox.configure(environment)

    assertEquals(environment.get("SBT_OPTS"), "-Dexample.flag=true")
    assertEquals(globalBaseOptions(environment), Nil)

  test("sandbox with empty sbt options does not add a second global base"):
    withSandbox { (sandbox, environment) =>
      SbtSandbox.configure(environment)

      assertEquals(globalBaseOptions(environment), Nil)
      assertEquals(
        environment.get("SBT_OPTS"),
        List(
          s"-Dsbt.boot.directory=${sandbox.resolve("boot")}",
          s"-Dsbt.ivy.home=${sandbox.resolve("ivy2")}",
          "-Dsbt.server.forcestart=true"
        ).mkString(" ")
      )
    }

  test("sandbox preserves unrelated inherited sbt options"):
    withSandbox { (sandbox, environment) =>
      environment.put("SBT_OPTS", "-Xmx768m -Dexample.flag=true")

      SbtSandbox.configure(environment)

      assertEquals(globalBaseOptions(environment), Nil)
      assertEquals(
        environment.get("SBT_OPTS"),
        List(
          "-Xmx768m",
          "-Dexample.flag=true",
          s"-Dsbt.boot.directory=${sandbox.resolve("boot")}",
          s"-Dsbt.ivy.home=${sandbox.resolve("ivy2")}",
          "-Dsbt.server.forcestart=true"
        ).mkString(" ")
      )
    }

  test("sandbox removes a conflicting inherited global base without disturbing other options"):
    withSandbox { (sandbox, environment) =>
      environment.put(
        "SBT_OPTS",
        "-Xmx768m -Dsbt.global.base=/tmp/inherited-global -Dexample.flag=true"
      )

      SbtSandbox.configure(environment)

      assertEquals(globalBaseOptions(environment), Nil)
      assertEquals(
        environment.get("SBT_OPTS"),
        List(
          "-Xmx768m",
          "-Dexample.flag=true",
          s"-Dsbt.boot.directory=${sandbox.resolve("boot")}",
          s"-Dsbt.ivy.home=${sandbox.resolve("ivy2")}",
          "-Dsbt.server.forcestart=true"
        ).mkString(" ")
      )
    }

  test("sandbox removes sbt launcher aliases that can replace the operation global base"):
    withSandbox { (sandbox, environment) =>
      environment.put(
        "SBT_OPTS",
          "-Xmx768m --sbt-dir /tmp/long-sbt-dir -sbt-dir /tmp/short-sbt-dir " +
          "--no-global -no-global --no-share " +
          "--sbt-dir=/tmp/long-equals -sbt-dir=/tmp/short-equals -Dexample.flag=true"
      )

      SbtSandbox.configure(environment)

      assertEquals(
        environment.get("SBT_OPTS"),
        List(
          "-Xmx768m",
          "-Dsbt.boot.directory=project/.boot",
          "-Dsbt.ivy.home=project/.ivy",
          "-Dexample.flag=true",
          s"-Dsbt.boot.directory=${sandbox.resolve("boot")}",
          s"-Dsbt.ivy.home=${sandbox.resolve("ivy2")}",
          "-Dsbt.server.forcestart=true"
        ).mkString(" ")
      )
    }

  test("no-share retains its non-global boot and Ivy choices outside sandbox"):
    val environment = new java.util.HashMap[String, String]()
    environment.put("SBT_OPTS", "-Dfirst=true -no-share -Dlast=true")

    SbtSandbox.configure(environment)

    assertEquals(
      environment.get("SBT_OPTS"),
      List(
        "-Dfirst=true",
        "-Dsbt.boot.directory=project/.boot",
        "-Dsbt.ivy.home=project/.ivy",
        "-Dlast=true"
      ).mkString(" ")
    )

  test("malformed sbt-dir alias does not consume a following unrelated option"):
    withSandbox { (_, environment) =>
      environment.put("SBT_OPTS", "--sbt-dir -Dkeep.after.malformed=true")

      SbtSandbox.configure(environment)

      assert(environment.get("SBT_OPTS").contains("-Dkeep.after.malformed=true"))
      assert(!environment.get("SBT_OPTS").contains("sbt-dir"))
    }

  test("actual lifecycle launch composition has one operation-owned global base"):
    withSandbox { (sandbox, environment) =>
      environment.put(
        "SBT_OPTS",
        "-Dkeep=true --sbt-dir /tmp/inherited-sbt-dir -Dsbt.global.base=/tmp/inherited-global"
      )

      val builder = SbtProcessLifecycle.processBuilder(
        Path.of("/tmp"),
        Path.of("/tmp/request-global"),
        Path.of("/tmp/request-runtime"),
        "projects",
        None,
        environment.asScala.toMap
      )

      assertEquals(
        builder.command().toArray().toList.map(_.toString).filter(_.startsWith("-Dsbt.global.base=")),
        List("-Dsbt.global.base=/tmp/request-global")
      )
      assertEquals(
        Option(builder.environment().get("SBT_OPTS")).toList.flatMap(_.split("\\s+").toList)
          .filter(option => option.contains("global") || option.contains("sbt-dir")),
        Nil
      )
      assert(builder.environment().get("SBT_OPTS").contains("-Dkeep=true"))
      assert(builder.environment().get("SBT_OPTS").contains(s"-Dsbt.boot.directory=${sandbox.resolve("boot")}"))
    }

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

  private def globalBaseOptions(environment: java.util.Map[String, String]): List[String] =
    Option(environment.get("SBT_OPTS")).toList
      .flatMap(_.split("\\s+").toList)
      .filter(_.startsWith("-Dsbt.global.base="))

  private def withSandbox(
      operation: (Path, java.util.HashMap[String, String]) => Unit
  ): Unit =
    val sandbox = Files.createTempDirectory("sbt-process-sandbox-options-")
    val environment = new java.util.HashMap[String, String]()
    environment.put("SEMANTIC_SCALA_SANDBOX_DIR", sandbox.toString)
    try operation(sandbox, environment)
    finally deleteRecursively(sandbox)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
