package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path

class SbtClasspathRequestSuite extends munit.FunSuite:
  test("project IDs accept ordinary letters, hyphens, underscores, and digits"):
    assertEquals(SbtProjectId.parse("app-2").map(_.value), Right("app-2"))
    assertEquals(SbtProjectId.parse("api_v3").map(_.value), Right("api_v3"))
    assertEquals(SbtProjectId.parse("root").map(_.value), Right("root"))

  test("project IDs reject empty, whitespace, scoped, quoted, control, and command syntax"):
    val unsafe = List(
      "",
      " ",
      "2app",
      "app / Compile",
      "app/Compile",
      "app;test",
      "app\"",
      "app\ncompile",
      "app$command"
    )

    unsafe.foreach(value => assert(SbtProjectId.parse(value).isLeft, clue(value)))

  test("configuration strings are exact and stable"):
    assertEquals(
      SbtClasspathConfiguration.parse("Compile"),
      Right(SbtClasspathConfiguration.Compile)
    )
    assertEquals(
      SbtClasspathConfiguration.parse("Test"),
      Right(SbtClasspathConfiguration.Test)
    )
    assertEquals(SbtClasspathConfiguration.parse("compile").isLeft, true)
    assertEquals(SbtClasspathConfiguration.parse("IntegrationTest").isLeft, true)
    assertEquals(
      SbtClasspathConfiguration.value(SbtClasspathConfiguration.Compile),
      "Compile"
    )

  test("request validation normalizes an existing workspace"):
    val workspace = Files.createTempDirectory("task069-request-workspace")
    try
      val request = SbtClasspathRequest(
        workspace.resolve("."),
        project("app-2"),
        SbtClasspathConfiguration.Compile
      )
      assertEquals(
        SbtClasspathRequest.validate(request).map(_.workspace),
        Right(workspace.toAbsolutePath.normalize())
      )
    finally Files.deleteIfExists(workspace)

  test("request validation rejects missing and non-directory workspaces"):
    val file = Files.createTempFile("task069-request", ".txt")
    try
      val missing = SbtClasspathRequest(
        Path.of("target/task069-missing-workspace"),
        project("app-2"),
        SbtClasspathConfiguration.Compile
      )
      val regularFile = missing.copy(workspace = file)
      assert(SbtClasspathRequest.validate(missing).left.exists(_.contains("does not exist")))
      assert(SbtClasspathRequest.validate(regularFile).left.exists(_.contains("not a directory")))
    finally Files.deleteIfExists(file)

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)
