package semantic.harness.sbt_runner

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import SbtClasspathCacheTestSupport.*

class SbtClasspathEvidenceSuite extends munit.FunSuite:
  private val collector = SbtClasspathEvidenceCollector.default

  test("conventional input evidence is deterministic and excludes generated directories"):
    val workspace = Files.createTempDirectory("task071-input-evidence")
    val second = workspace.resolve("module/src/test/resources/z.conf")
    val first = workspace.resolve("module/src/main/scala/a/Main.scala")
    val build = workspace.resolve("build.sbt")
    val generated = workspace.resolve("module/target/generated.txt")
    try
      List(second, first, generated).foreach(path => Files.createDirectories(path.getParent))
      Files.writeString(second, "z")
      Files.writeString(first, "object Main")
      Files.writeString(build, "scalaVersion := \"3.3.3\"")
      Files.writeString(generated, "ignored-1")

      val initial = collectInputs(workspace)
      val repeated = collectInputs(workspace)
      Files.writeString(generated, "ignored-2")
      val afterGenerated = collectInputs(workspace)

      assertEquals(repeated, initial)
      assertEquals(afterGenerated, initial)
      assertEquals(initial.fileCount, 3L)
      assertEquals(initial.coverageVersion, "conventional-inputs.v1")
      assert(!initial.projectRootPresent)
    finally deleteRecursively(workspace)

  test("build, project, conventional source, and resource changes alter input evidence"):
    val workspace = Files.createTempDirectory("task071-input-drift")
    val build = workspace.resolve("build.sbt")
    val project = workspace.resolve("project/plugins.sbt")
    val source = workspace.resolve("app/src/main/scala/App.scala")
    val resource = workspace.resolve("app/src/test/resources/test.conf")
    try
      List(project, source, resource).foreach(path => Files.createDirectories(path.getParent))
      Files.writeString(build, "lazy val app = project")
      Files.writeString(project, "addSbtPlugin(\"x\" % \"y\" % \"1\")")
      Files.writeString(source, "object App")
      Files.writeString(resource, "value=1")
      val initial = collectInputs(workspace)
      assert(initial.projectRootPresent)

      List(build, project, source, resource).foreach { path =>
        val before = collectInputs(workspace)
        Files.writeString(path, Files.readString(path) + "\nchanged")
        assertNotEquals(collectInputs(workspace).sha256, before.sha256, clue(path))
      }
    finally deleteRecursively(workspace)

  test("symbolic links in required input scopes fail closed"):
    val workspace = Files.createTempDirectory("task071-input-symlink")
    val outside = Files.createTempFile("task071-outside", ".scala")
    val link = workspace.resolve("app/src/main/scala/Linked.scala")
    try
      Files.createDirectories(link.getParent)
      Files.createSymbolicLink(link, outside)
      val result = collector.collectInputs(workspace)
      assert(
        result.left.exists(failure =>
          SbtClasspathCacheFailure.message(failure).contains("symbolic link")
        )
      )
    finally
      deleteRecursively(workspace)
      Files.deleteIfExists(outside)

  test("JAR and directory evidence hashes complete same-path contents deterministically"):
    val root = Files.createTempDirectory("task071-entry-evidence")
    val directory = Files.createDirectory(root.resolve("classes"))
    val classFile = directory.resolve("example/Main.class")
    val jar = root.resolve("library.jar")
    try
      Files.createDirectories(classFile.getParent)
      Files.write(classFile, Array[Byte](1, 2, 3))
      Files.write(jar, Array[Byte](4, 5, 6))
      val entries = List(
        SbtClasspathEntry(directory, SbtClasspathEntryKind.Directory),
        SbtClasspathEntry(jar, SbtClasspathEntryKind.Jar)
      )

      val initial = collectEntries(entries)
      assertEquals(collectEntries(entries), initial)
      assertEquals(initial.map(_.kind), List(SbtClasspathEntryKind.Directory, SbtClasspathEntryKind.Jar))
      assertEquals(initial.map(_.fileCount), List(1L, 1L))

      Files.write(jar, Array[Byte](7, 8, 9))
      val replacedJar = collectEntries(entries)
      assertNotEquals(replacedJar(1).sha256, initial(1).sha256)

      Files.write(classFile, Array[Byte](9, 8, 7))
      val replacedClass = collectEntries(entries)
      assertNotEquals(replacedClass.head.sha256, initial.head.sha256)
    finally deleteRecursively(root)

  test("missing and recreated empty class directories differ and fail safely"):
    val root = Files.createTempDirectory("task071-directory-recreation")
    val directory = Files.createDirectory(root.resolve("classes"))
    val classFile = directory.resolve("Main.class")
    val entry = List(SbtClasspathEntry(directory, SbtClasspathEntryKind.Directory))
    try
      Files.write(classFile, Array[Byte](1))
      val initial = collectEntries(entry).head
      Files.delete(classFile)
      Files.delete(directory)
      assert(collector.collectEntries(entry).isLeft)
      Files.createDirectory(directory)
      val recreated = collectEntries(entry).head
      assertEquals(recreated.fileCount, 0L)
      assertNotEquals(recreated, initial)
    finally deleteRecursively(root)

  test("symbolic links in class directories fail closed"):
    val root = Files.createTempDirectory("task071-entry-symlink")
    val directory = Files.createDirectory(root.resolve("classes"))
    val outside = Files.createTempFile("task071-class", ".class")
    try
      Files.createSymbolicLink(directory.resolve("Linked.class"), outside)
      val result = collector.collectEntries(
        List(SbtClasspathEntry(directory, SbtClasspathEntryKind.Directory))
      )
      assert(
        result.left.exists(failure =>
          SbtClasspathCacheFailure.message(failure).contains("symbolic link")
        )
      )
    finally
      deleteRecursively(root)
      Files.deleteIfExists(outside)

  test("one oversized conventional input fails before hashing its contents"):
    val workspace = Files.createTempDirectory("task071-input-bound")
    val file = workspace.resolve("huge.sbt")
    try
      val channel = java.nio.channels.FileChannel.open(
        file,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      try
        channel.position(SbtClasspathCacheBounds.MaxInputFileBytes)
        channel.write(ByteBuffer.wrap(Array[Byte](1)))
      finally channel.close()

      val result = collector.collectInputs(workspace)
      assert(
        result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.EvidenceBoundsExceeded])
      )
    finally deleteRecursively(workspace)

  private def collectInputs(workspace: Path): SbtClasspathInputEvidence =
    collector
      .collectInputs(workspace)
      .fold(failure => fail(SbtClasspathCacheFailure.message(failure)), value => value)

  private def collectEntries(
      entries: List[SbtClasspathEntry]
  ): List[SbtClasspathEntryEvidence] =
    collector
      .collectEntries(entries)
      .fold(failure => fail(SbtClasspathCacheFailure.message(failure)), value => value)
