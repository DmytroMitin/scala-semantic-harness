package semantic.harness.semanticdb_reader

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays

class UsagesManifestLoaderSuite extends munit.FunSuite:
  test("strict manifest loader accepts and canonically sorts one explicit inventory"):
    val workspace = Files.createTempDirectory("usages-manifest-valid-")
    file(workspace, "z/Z.scala", "object Z")
    file(workspace, "a/A.scala", "object A")
    file(workspace, "out/z.semanticdb", "z")
    file(workspace, "out/a.semanticdb", "a")
    manifest(
      workspace,
      valid(
        sources =
          """[{"path":"z/Z.scala","module":"z","sourceSet":"main","generated":false},{"path":"a/A.scala","module":"a","sourceSet":"main","generated":false}]""",
        artifacts =
          """[{"path":"out/z.semanticdb","module":"z","sourceSet":"main","generated":false},{"path":"out/a.semanticdb","module":"a","sourceSet":"main","generated":false}]"""
      )
    )

    val loaded = UsagesManifestLoader.load(workspace, "usages.json").fold(failFailure, identity)
    assertEquals(loaded.sources.map(_.path), List("a/A.scala", "z/Z.scala"))
    assertEquals(loaded.artifacts.map(_.path), List("out/a.semanticdb", "out/z.semanticdb"))
    assert(loaded.inventoryClosed)

  test("strict manifest loader rejects malformed UTF-8 duplicate keys and unknown contract fields"):
    val workspace = minimalWorkspace("strict")
    Files.write(workspace.resolve("bad-utf8.json"), Array(0xc3.toByte, 0x28.toByte))
    val invalid = List(
      "bad-utf8.json" -> UsagesPublicFailureKind.InvalidInput,
      writeManifest(workspace, "duplicate.json", valid().replaceFirst("\"inventoryClosed\":true", "\"inventoryClosed\":true,\"\\u0069nventoryClosed\":true")) -> UsagesPublicFailureKind.InvalidInput,
      writeManifest(workspace, "version.json", valid().replace(UsagesManifestLoader.SchemaVersion, "semantic-scala.usages-manifest.v2")) -> UsagesPublicFailureKind.InvalidInput,
      writeManifest(workspace, "unknown.json", valid().replaceFirst("\"inventoryClosed\":true", "\"inventoryClosed\":true,\"workspace\":\"private\"")) -> UsagesPublicFailureKind.InvalidInput,
      writeManifest(workspace, "syntax.json", "{\"schemaVersion\":") -> UsagesPublicFailureKind.InvalidInput
    )
    invalid.foreach { case (path, kind) =>
      assertEquals(UsagesManifestLoader.load(workspace, path).left.map(_.failureKind), Left(kind), path)
    }

  test("strict manifest loader rejects duplicate normalized declaration paths"):
    val workspace = minimalWorkspace("duplicate-path")
    val sources =
      """[{"path":"src/A.scala","module":"core","sourceSet":"main","generated":false},{"path":"src/A.scala","module":"other","sourceSet":"test","generated":false}]"""
    manifest(workspace, valid(sources = sources))
    val failure = UsagesManifestLoader.load(workspace, "usages.json").left.getOrElse(fail("Expected failure"))
    assertEquals(failure.failureKind, UsagesPublicFailureKind.InvalidInput)
    assert(failure.message.contains("duplicate normalized source path"))

  test("strict manifest loader rejects absolute traversal dot backslash and invalid extensions"):
    val workspace = minimalWorkspace("paths")
    val badPaths = List(
      "/tmp/A.scala",
      "../A.scala",
      "src/./A.scala",
      "src\\A.scala",
      "src//A.scala",
      "src/A.txt"
    )
    badPaths.zipWithIndex.foreach { case (path, index) =>
      val sources = s"""[{"path":"${jsonEscape(path)}","module":"core","sourceSet":"main","generated":false}]"""
      val name = s"bad-$index.json"
      manifest(workspace, valid(sources = sources), name)
      val failure = UsagesManifestLoader.load(workspace, name).left.getOrElse(fail("Expected failure"))
      assertEquals(failure.failureKind, UsagesPublicFailureKind.InvalidInput, path)
    }

  test("strict manifest loader rejects symlink non-file missing and containment-unsafe files"):
    val workspace = minimalWorkspace("filesystem")
    Files.createDirectories(workspace.resolve("directory.scala"))
    Files.createSymbolicLink(workspace.resolve("linked.scala"), workspace.resolve("src/A.scala"))
    val paths = List("directory.scala", "linked.scala", "missing.scala")
    paths.zipWithIndex.foreach { case (path, index) =>
      val sources = s"""[{"path":"$path","module":"core","sourceSet":"main","generated":false}]"""
      val name = s"unsafe-$index.json"
      manifest(workspace, valid(sources = sources), name)
      val failure = UsagesManifestLoader.load(workspace, name).left.getOrElse(fail("Expected failure"))
      assertEquals(failure.failureKind, UsagesPublicFailureKind.UnsafeFilesystem, path)
    }

    Files.createSymbolicLink(workspace.resolve("manifest-link.json"), workspace.resolve("usages.json"))
    assertEquals(
      UsagesManifestLoader.load(workspace, "manifest-link.json").left.map(_.failureKind),
      Left(UsagesPublicFailureKind.UnsafeFilesystem)
    )

  test("strict manifest loader enforces its byte bound before parsing"):
    val workspace = minimalWorkspace("size")
    val bytes = Array.fill[Byte](UsagesManifestLoader.MaxManifestBytes + 1)(' '.toByte)
    Files.write(workspace.resolve("large.json"), bytes)
    val failure = UsagesManifestLoader.load(workspace, "large.json").left.getOrElse(fail("Expected failure"))
    assertEquals(failure.failureKind, UsagesPublicFailureKind.InvalidInput)
    assert(failure.message.contains("byte limit"))

  test("bounded manifest read rejects crossing size after consuming only limit plus one byte"):
    val workspace = minimalWorkspace("crossing-size")
    val stream = SyntheticInputStream(
      prefix = valid().getBytes(StandardCharsets.UTF_8),
      totalBytes = UsagesManifestLoader.MaxManifestBytes + 1024
    )
    val access = TestFileAccess(UsagesManifestLoader.MaxManifestBytes.toLong, stream)

    val failure = UsagesManifestLoader
      .loadWithFileAccess(workspace, "usages.json", access)
      .left
      .getOrElse(fail("Expected failure"))

    assertEquals(failure.failureKind, UsagesPublicFailureKind.InvalidInput)
    assert(failure.message.contains("byte limit"))
    assertEquals(stream.consumedBytes, UsagesManifestLoader.MaxManifestBytes + 1)
    assert(stream.closed)

  test("bounded manifest read admits an otherwise valid exact-limit manifest"):
    val workspace = minimalWorkspace("exact-size")
    val stream = SyntheticInputStream(
      prefix = valid().getBytes(StandardCharsets.UTF_8),
      totalBytes = UsagesManifestLoader.MaxManifestBytes
    )
    val access = TestFileAccess(UsagesManifestLoader.MaxManifestBytes.toLong, stream)

    val loaded = UsagesManifestLoader
      .loadWithFileAccess(workspace, "usages.json", access)
      .fold(failFailure, identity)

    assertEquals(loaded.sources.map(_.path), List("src/A.scala"))
    assertEquals(loaded.artifacts.map(_.path), List("out/a.semanticdb"))
    assertEquals(stream.consumedBytes, UsagesManifestLoader.MaxManifestBytes)
    assert(stream.closed)

  test("bounded manifest read closes its stream on success and I/O failure"):
    val workspace = minimalWorkspace("close")
    val content = valid().getBytes(StandardCharsets.UTF_8)
    val successStream = SyntheticInputStream(prefix = content, totalBytes = content.length)
    val success = UsagesManifestLoader.loadWithFileAccess(
      workspace,
      "usages.json",
      TestFileAccess(content.length.toLong, successStream)
    )
    assert(success.isRight)
    assert(successStream.closed)

    val failingStream = SyntheticInputStream(prefix = content, totalBytes = content.length, failAt = Some(1))
    val failure = UsagesManifestLoader
      .loadWithFileAccess(
        workspace,
        "usages.json",
        TestFileAccess(content.length.toLong, failingStream)
      )
      .left
      .getOrElse(fail("Expected failure"))
    assertEquals(failure.failureKind, UsagesPublicFailureKind.IoFailure)
    assert(failingStream.closed)

  test("linear manifest traversal preserves order and stops at the first failure"):
    val visited = List.newBuilder[Int]
    val result = UsagesManifestLoader.traverse(List(1, 2, 3, 4)) { value =>
      visited += value
      if value == 3 then Left(invalidForTest("stop")) else Right(value * 10)
    }

    assertEquals(result.left.map(_.message), Left("stop"))
    assertEquals(visited.result(), List(1, 2, 3))

    val ordered = UsagesManifestLoader.traverse(List(3, 1, 2))(value => Right(value * 10))
    assertEquals(ordered, Right(List(30, 10, 20)))

  test("linear manifest traversal visits each admitted source exactly once"):
    val sourceCount = 50000
    var visits = 0
    val values = List.range(0, sourceCount)

    val result = UsagesManifestLoader.traverse(values) { value =>
      visits += 1
      Right(value)
    }

    assertEquals(visits, sourceCount)
    assertEquals(result.map(_.size), Right(sourceCount))
    assertEquals(result.map(_.headOption), Right(Some(0)))
    assertEquals(result.map(_.lastOption), Right(Some(sourceCount - 1)))

  private def minimalWorkspace(label: String): Path =
    val workspace = Files.createTempDirectory(s"usages-manifest-$label-")
    file(workspace, "src/A.scala", "object A")
    file(workspace, "out/a.semanticdb", "artifact")
    manifest(workspace, valid())
    workspace

  private def valid(
    sources: String = """[{"path":"src/A.scala","module":"core","sourceSet":"main","generated":false}]""",
    artifacts: String = """[{"path":"out/a.semanticdb","module":"core","sourceSet":"main","generated":false}]"""
  ): String =
    s"""{"schemaVersion":"${UsagesManifestLoader.SchemaVersion}","inventoryClosed":true,"sources":$sources,"artifacts":$artifacts}"""

  private def manifest(workspace: Path, content: String, name: String = "usages.json"): String =
    writeManifest(workspace, name, content)

  private def writeManifest(workspace: Path, name: String, content: String): String =
    Files.writeString(workspace.resolve(name), content, StandardCharsets.UTF_8)
    name

  private def file(workspace: Path, relative: String, content: String): Unit =
    val path = workspace.resolve(relative)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, content, StandardCharsets.UTF_8)

  private def jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
  private def failFailure(value: UsagesPublicFailure): Nothing = fail(s"Unexpected failure: $value")
  private def invalidForTest(message: String): UsagesPublicFailure =
    UsagesPublicFailure(failureKind = UsagesPublicFailureKind.InvalidInput, message = message)

  private final case class TestFileAccess(
    reportedSize: Long,
    stream: InputStream
  ) extends UsagesManifestLoader.FileAccess:
    override def size(path: Path): Long = reportedSize
    override def open(path: Path): InputStream = stream

  private final case class SyntheticInputStream(
    prefix: Array[Byte],
    totalBytes: Int,
    failAt: Option[Int] = None
  ) extends InputStream:
    require(prefix.length <= totalBytes)

    var consumedBytes = 0
    var closed = false

    override def read(): Int =
      val one = Array.ofDim[Byte](1)
      val count = read(one, 0, 1)
      if count < 0 then -1 else one(0) & 0xff

    override def read(target: Array[Byte], offset: Int, length: Int): Int =
      if length == 0 then 0
      else if failAt.exists(consumedBytes >= _) then throw IOException("synthetic read failure")
      else if consumedBytes >= totalBytes then -1
      else
        val beforeFailure = failAt.map(_ - consumedBytes).getOrElse(length)
        val count = math.min(length, math.min(totalBytes - consumedBytes, beforeFailure))
        if count <= 0 then throw IOException("synthetic read failure")
        Arrays.fill(target, offset, offset + count, ' '.toByte)
        val prefixBytes = math.min(count, math.max(0, prefix.length - consumedBytes))
        if prefixBytes > 0 then
          System.arraycopy(prefix, consumedBytes, target, offset, prefixBytes)
        consumedBytes += count
        count

    override def close(): Unit =
      closed = true
