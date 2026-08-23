package semantic.harness.tasty

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class TastyArtifactInventorySuite extends munit.FunSuite:
  test("source input validates one-based UTF-16 coordinates and freezes a digest"):
    val workspace = Files.createTempDirectory("tasty-source-")
    try
      val source = workspace.resolve("Example.scala")
      Files.writeString(source, "object Example:\n  val face = \"😀\"\n", StandardCharsets.UTF_8)
      val validated = TastySourceInput.validate(workspace, "Example.scala", 2, 17)
      assert(validated.isRight)
      val input = validated.toOption.get
      assertEquals(input.relativePath, "Example.scala")
      assertEquals(input.sha256.length, 64)
      assertEquals(input.utf16Offset, 32)
      assert(TastySourceInput.validate(workspace, "Example.scala", 2, 19).isLeft)
    finally deleteTree(workspace)

  test("artifact inventory is sorted, digested, and rejects fixed bounds"):
    val workspace = Files.createTempDirectory("tasty-artifacts-")
    try
      val classes = Files.createDirectories(workspace.resolve("target/classes/nested"))
      Files.write(classes.resolve("B.tasty"), Array[Byte](2, 3))
      Files.write(workspace.resolve("target/classes/A.tasty"), Array[Byte](1))
      val result = TastyArtifactInventory.inspect(workspace, workspace.resolve("target/classes"))
      assertEquals(result.map(_.map(_.relativePath)), Right(List("A.tasty", "nested/B.tasty")))
      assertEquals(result.toOption.get.map(_.sha256.length), List(64, 64))

      val tooMany = TastyArtifactBounds(maxCandidates = 1)
      assertEquals(
        TastyArtifactInventory.inspect(workspace, workspace.resolve("target/classes"), tooMany).left.toOption,
        Some(TastyArtifactInventoryFailure.RejectedByBounds)
      )
    finally deleteTree(workspace)

  private def deleteTree(root: java.nio.file.Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()
