package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import SbtClasspathCacheTestSupport.*

class SbtClasspathCacheCodecSuite extends munit.FunSuite:
  test("strict private cache record round-trips exactly"):
    val workspace = Files.createTempDirectory("task071-codec")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val cacheIdentity = identity(workspace)
      val expected = record(cacheIdentity, classes)
      val bytes = SbtClasspathCacheCodec.encode(expected)

      assertEquals(SbtClasspathCacheCodec.decode(bytes, cacheIdentity), Right(expected))
      assert(bytes.length < SbtClasspathCacheBounds.MaxCacheFileBytes)
      assert(String(bytes, StandardCharsets.UTF_8).contains(SbtClasspathCacheRecord.Format))
    finally deleteRecursively(workspace)

  test("selected Java cache record round-trips as strict v2 without storing its path"):
    val workspace = Files.createTempDirectory("task166-codec-v2")
    val javaHome = workspace.resolve("private-selected-java")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val cacheIdentity = identity(
        workspace,
        targetJava = Some(selectedJava(javaHome))
      )
      val expected = record(cacheIdentity, classes)
      val bytes = SbtClasspathCacheCodec.encode(expected)
      val content = String(bytes, StandardCharsets.UTF_8)

      assertEquals(SbtClasspathCacheCodec.decode(bytes, cacheIdentity), Right(expected))
      assert(content.contains(SbtClasspathCacheRecord.FormatV2))
      assert(content.contains("\"sbtJavaHomeDigest\""))
      assert(content.contains("\"sbtJavaRuntimeFingerprint\""))
      assert(!content.contains(javaHome.toString))
    finally deleteRecursively(workspace)

  test("selected Java cache rejects an in-place runtime change with a typed mismatch"):
    val workspace = Files.createTempDirectory("task166-codec-runtime-drift")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val first = identity(
        workspace,
        targetJava = Some(selectedJava(workspace.resolve("jdk"), runtimeFingerprint = "b" * 64))
      )
      val changed = identity(
        workspace,
        targetJava = Some(selectedJava(workspace.resolve("jdk"), runtimeFingerprint = "c" * 64))
      )
      assertEquals(first.storageKey, changed.storageKey)

      val result = SbtClasspathCacheCodec.decode(
        SbtClasspathCacheCodec.encode(record(first, classes)),
        changed
      )
      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.TargetJavaMismatch]))
    finally deleteRecursively(workspace)

  test("codec rejects unsupported marker, unknown fields, and unknown entry kinds"):
    val workspace = Files.createTempDirectory("task071-codec-strict")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val cacheIdentity = identity(workspace)
      val encoded = String(
        SbtClasspathCacheCodec.encode(record(cacheIdentity, classes)),
        StandardCharsets.UTF_8
      )
      val unsupported =
        encoded.replace(SbtClasspathCacheRecord.Format, "semantic-scala.internal-cache.v2")
      val unknownField = encoded.dropRight(1) + ",\"unexpected\":true}"
      val unknownKind = encoded.replace("\"Directory\"", "\"Zip\"")

      List(unsupported, unknownField, unknownKind).foreach { content =>
        assert(
          SbtClasspathCacheCodec
            .decode(content.getBytes(StandardCharsets.UTF_8), cacheIdentity)
            .isLeft,
          clue(content.take(120))
        )
      }
    finally deleteRecursively(workspace)

  test("codec revalidates full identity rather than trusting storage filename"):
    val root = Files.createTempDirectory("task071-codec-identity")
    val firstWorkspace = Files.createDirectory(root.resolve("first"))
    val secondWorkspace = Files.createDirectory(root.resolve("second"))
    val classes = Files.createDirectory(firstWorkspace.resolve("classes"))
    try
      val first = identity(firstWorkspace)
      val second = identity(secondWorkspace)
      val bytes = SbtClasspathCacheCodec.encode(record(first, classes))

      val result = SbtClasspathCacheCodec.decode(bytes, second)
      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Invalid]))
    finally deleteRecursively(root)

  test("codec rejects duplicate and empty entry arrays"):
    val workspace = Files.createTempDirectory("task071-codec-entries")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val cacheIdentity = identity(workspace)
      val base = record(cacheIdentity, classes)
      val duplicate = base.copy(entries = base.entries ++ base.entries, entryCount = 2)
      val empty = base.copy(entries = Nil, entryCount = 0)

      assert(SbtClasspathCacheCodec.decode(SbtClasspathCacheCodec.encode(duplicate), cacheIdentity).isLeft)
      assert(SbtClasspathCacheCodec.decode(SbtClasspathCacheCodec.encode(empty), cacheIdentity).isLeft)
    finally deleteRecursively(workspace)

  test("codec rejects oversized bytes before JSON parsing"):
    val workspace = Files.createTempDirectory("task071-codec-oversized")
    try
      val cacheIdentity = identity(workspace)
      val bytes = Array.ofDim[Byte](
        (SbtClasspathCacheBounds.MaxCacheFileBytes + 1L).toInt
      )
      val result = SbtClasspathCacheCodec.decode(bytes, cacheIdentity)

      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Invalid]))
      assert(
        result.left.exists(failure =>
          SbtClasspathCacheFailure.message(failure).contains("exceeds")
        )
      )
    finally deleteRecursively(workspace)

  test("codec rejects malformed UTF-8 and invalid SHA-256 evidence"):
    val workspace = Files.createTempDirectory("task071-codec-invalid")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val cacheIdentity = identity(workspace)
      assert(SbtClasspathCacheCodec.decode(Array(0xc3.toByte), cacheIdentity).isLeft)

      val invalidDigest = record(cacheIdentity, classes, inputDigest = "not-a-digest")
      assert(
        SbtClasspathCacheCodec
          .decode(SbtClasspathCacheCodec.encode(invalidDigest), cacheIdentity)
          .isLeft
      )
    finally deleteRecursively(workspace)

  test("codec enforces per-entry and aggregate evidence bounds"):
    val workspace = Files.createTempDirectory("task071-codec-bounds")
    try
      val cacheIdentity = identity(workspace)
      val digest = "3" * 64
      val oversizedJar = SbtClasspathEntryEvidence(
        workspace.resolve("oversized.jar").toString,
        SbtClasspathEntryKind.Jar,
        1L,
        SbtClasspathCacheBounds.MaxJarBytes + 1L,
        digest
      )
      val tooManyDirectoryFiles = List("first", "second").map { name =>
        SbtClasspathEntryEvidence(
          workspace.resolve(name).toString,
          SbtClasspathEntryKind.Directory,
          SbtClasspathCacheBounds.MaxClassDirectoryFiles / 2L + 1L,
          0L,
          digest
        )
      }
      val tooManyJarBytes = (1 to 5).toList.map { index =>
        SbtClasspathEntryEvidence(
          workspace.resolve(s"$index.jar").toString,
          SbtClasspathEntryKind.Jar,
          1L,
          SbtClasspathCacheBounds.MaxJarBytes,
          digest
        )
      }
      val base = record(cacheIdentity, workspace.resolve("classes"))
      List(List(oversizedJar), tooManyDirectoryFiles, tooManyJarBytes).foreach {
        entries =>
          val candidate = base.copy(entries = entries, entryCount = entries.size)
          assert(
            SbtClasspathCacheCodec
              .decode(SbtClasspathCacheCodec.encode(candidate), cacheIdentity)
              .isLeft
          )
      }
    finally deleteRecursively(workspace)
