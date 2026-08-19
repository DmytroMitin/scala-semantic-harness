package semantic.harness.mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*

class CliPathResolverSuite extends munit.FunSuite:
  test("default resolution finds the installed semantic-scala sibling on PATH"):
    withExecutable("semantic-scala") { cli =>
      val result = CliPathResolver.resolve(Nil, Map("PATH" -> cli.getParent.toString))
      assertEquals(result, Right(cli.toAbsolutePath.normalize))
    }

  test("explicit --cli overrides the environment and PATH"):
    withThreeExecutables { (explicit, environment, pathCli) =>
      val result = CliPathResolver.resolve(
        List("--cli", explicit.toString),
        Map(
          CliPathResolver.EnvironmentVariable -> environment.toString,
          "PATH" -> pathCli.getParent.toString
        )
      )
      assertEquals(result, Right(explicit.toAbsolutePath.normalize))
    }

  test("environment override takes precedence over PATH"):
    withTwoExecutables { (environment, pathCli) =>
      val result = CliPathResolver.resolve(
        Nil,
        Map(
          CliPathResolver.EnvironmentVariable -> environment.toString,
          "PATH" -> pathCli.getParent.toString
        )
      )
      assertEquals(result, Right(environment.toAbsolutePath.normalize))
    }

  test("missing CLI fails early without exposing searched paths"):
    val missing = Files.createTempDirectory("semantic-scala-missing-")
    try
      val result = CliPathResolver.resolve(Nil, Map("PATH" -> missing.toString))
      assert(result.isLeft)
      val message = result.swap.toOption.getOrElse("")
      assert(message.contains("semantic-scala executable was not found"))
      assert(!message.contains(missing.toString))
    finally deleteTree(missing)

  test("nonregular and nonexecutable targets fail closed with sanitized errors"):
    val root = Files.createTempDirectory("semantic-scala-unsafe-")
    try
      val directory = Files.createDirectory(root.resolve("directory"))
      val nonExecutable = Files.writeString(root.resolve("plain-file"), "plain", StandardCharsets.UTF_8)
      List(directory, nonExecutable).foreach { candidate =>
        val result = CliPathResolver.resolve(List("--cli", candidate.toString), Map.empty)
        assert(result.isLeft)
        val message = result.swap.toOption.getOrElse("")
        assert(message.contains("not a launchable regular executable"))
        assert(!message.contains(candidate.toString))
      }
    finally deleteTree(root)

  test("empty PATH entries are ignored rather than interpreted as the working directory"):
    val result = CliPathResolver.resolve(Nil, Map("PATH" -> java.io.File.pathSeparator))
    assert(result.isLeft)

  test("unexpected arguments are rejected without reflecting attacker-controlled text"):
    val secret = "/private/operator/secret"
    val result = CliPathResolver.resolve(List("--unexpected", secret), Map.empty)
    assertEquals(result, Left("Usage: semantic-scala-mcp [--cli <executable>]"))

  private def withExecutable[A](name: String)(body: Path => A): A =
    val root = Files.createTempDirectory("semantic-scala-cli-path-")
    try body(executable(root.resolve(name)))
    finally deleteTree(root)

  private def withTwoExecutables[A](body: (Path, Path) => A): A =
    val firstRoot = Files.createTempDirectory("semantic-scala-cli-first-")
    val secondRoot = Files.createTempDirectory("semantic-scala-cli-second-")
    try body(executable(firstRoot.resolve("semantic-scala")), executable(secondRoot.resolve("semantic-scala")))
    finally
      deleteTree(firstRoot)
      deleteTree(secondRoot)

  private def withThreeExecutables[A](body: (Path, Path, Path) => A): A =
    val firstRoot = Files.createTempDirectory("semantic-scala-cli-first-")
    val secondRoot = Files.createTempDirectory("semantic-scala-cli-second-")
    val thirdRoot = Files.createTempDirectory("semantic-scala-cli-third-")
    try
      body(
        executable(firstRoot.resolve("explicit-semantic-scala")),
        executable(secondRoot.resolve("environment-semantic-scala")),
        executable(thirdRoot.resolve("semantic-scala"))
      )
    finally
      deleteTree(firstRoot)
      deleteTree(secondRoot)
      deleteTree(thirdRoot)

  private def executable(path: Path): Path =
    Files.writeString(path, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8)
    Files.setPosixFilePermissions(
      path,
      Set(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
      ).asJava
    )
    path

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()
