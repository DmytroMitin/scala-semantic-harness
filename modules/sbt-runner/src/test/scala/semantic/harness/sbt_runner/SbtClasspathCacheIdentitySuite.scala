package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import SbtClasspathCacheTestSupport.*

class SbtClasspathCacheIdentitySuite extends munit.FunSuite:
  test("cache mode strings are exact, stable, and lower-case"):
    assertEquals(SbtClasspathCacheMode.parse("fresh"), Right(SbtClasspathCacheMode.Fresh))
    assertEquals(SbtClasspathCacheMode.parse("refresh"), Right(SbtClasspathCacheMode.Refresh))
    assertEquals(SbtClasspathCacheMode.parse("reuse"), Right(SbtClasspathCacheMode.Reuse))
    assertEquals(SbtClasspathCacheMode.value(SbtClasspathCacheMode.Refresh), "refresh")
    assert(SbtClasspathCacheMode.parse("Reuse").isLeft)

  test("identity is deterministic and separates project and configuration"):
    val workspace = Files.createTempDirectory("task071-identity")
    try
      val first = identity(workspace)
      val repeated = identity(workspace.resolve("."))
      val otherProject = identity(workspace, projectId = "api")
      val testScope = identity(
        workspace,
        configuration = SbtClasspathConfiguration.Test
      )

      assertEquals(repeated, first)
      assertNotEquals(otherProject.storageKey, first.storageKey)
      assertNotEquals(testScope.storageKey, first.storageKey)
      assert(first.storageKey.matches("[0-9a-f]{64}"))
      assert(first.workspaceDigest.matches("[0-9a-f]{64}"))
      assert(!first.storageKey.contains(workspace.toString))
    finally deleteRecursively(workspace)

  test("canonical workspace identity shares symlink aliases but separates copies"):
    val root = Files.createTempDirectory("task071-canonical")
    val original = Files.createDirectory(root.resolve("original"))
    val copied = Files.createDirectory(root.resolve("copied"))
    val alias = root.resolve("alias")
    try
      Files.createSymbolicLink(alias, original)
      val originalIdentity = identity(original)
      val aliasIdentity = identity(alias)
      val copiedIdentity = identity(copied)

      assertEquals(aliasIdentity.storageKey, originalIdentity.storageKey)
      assertEquals(aliasIdentity.workspaceDigest, originalIdentity.workspaceDigest)
      assertNotEquals(copiedIdentity.storageKey, originalIdentity.storageKey)
      assertNotEquals(copiedIdentity.workspaceDigest, originalIdentity.workspaceDigest)
    finally deleteRecursively(root)

  test("logical identity includes private cache and acquisition protocol versions"):
    val workspace = Files.createTempDirectory("task071-identity-version")
    try
      val value = identity(workspace)
      assertEquals(value.cacheFormat, SbtClasspathCacheRecord.Format)
      assertEquals(value.acquisitionProtocol, SbtClasspathProtocol.Format)
      assertEquals(value.sbtJavaHomeDigest, None)
      assertEquals(value.sbtJavaRuntimeFingerprint, None)
      assertEquals(value.project.value, "app-2")
      assertEquals(value.configuration, SbtClasspathConfiguration.Compile)
    finally deleteRecursively(workspace)

  test("explicit Java identity uses v2 and separates home locator from runtime fingerprint"):
    val workspace = Files.createTempDirectory("task166-identity-v2")
    val home = Files.createDirectory(workspace.resolveSibling(s"${workspace.getFileName}-jdk"))
    try
      val firstJava = selectedJava(home, "a" * 64, "b" * 64)
      val updatedRuntime = selectedJava(home, "a" * 64, "c" * 64)
      val otherHome = selectedJava(home.resolveSibling("other-jdk"), "d" * 64, "e" * 64)
      val first = identity(workspace, targetJava = Some(firstJava))
      val changed = identity(workspace, targetJava = Some(updatedRuntime))
      val other = identity(workspace, targetJava = Some(otherHome))

      assertEquals(first.cacheFormat, SbtClasspathCacheRecord.FormatV2)
      assertEquals(first.acquisitionProtocol, SbtClasspathProtocol.FormatV2)
      assertEquals(first.sbtJavaHomeDigest, Some("a" * 64))
      assertEquals(first.sbtJavaRuntimeFingerprint, Some("b" * 64))
      assertEquals(changed.storageKey, first.storageKey)
      assertNotEquals(changed, first)
      assertNotEquals(other.storageKey, first.storageKey)
    finally
      Files.deleteIfExists(home)
      deleteRecursively(workspace)
