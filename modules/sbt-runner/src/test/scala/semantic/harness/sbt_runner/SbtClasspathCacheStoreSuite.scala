package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import scala.concurrent.duration.DurationInt
import SbtClasspathCacheTestSupport.*

class SbtClasspathCacheStoreSuite extends munit.FunSuite:
  test("store publishes and rereads one complete owner-only record outside workspace"):
    val workspace = Files.createTempDirectory("task071-store-workspace")
    val root = Files.createTempDirectory("task071-store-root")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val cacheIdentity = identity(workspace)
      val expected = record(cacheIdentity, classes)
      val store = SbtClasspathCacheStore.local(root, 1.second)
      val result = store.withLock(cacheIdentity) { locked =>
        locked.publish(expected).flatMap(_ => locked.read(cacheIdentity))
      }

      assertEquals(result, Right(expected))
      val recordPath = root.resolve(s"${cacheIdentity.storageKey}.json")
      assert(Files.isRegularFile(recordPath))
      assert(!recordPath.startsWith(workspace))
      if Files.getFileStore(recordPath).supportsFileAttributeView("posix") then
        assertEquals(
          Files.getPosixFilePermissions(recordPath),
          java.util.Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
          )
        )
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)

  test("selected Java records use an isolated v2 namespace"):
    val workspace = Files.createTempDirectory("task166-store-workspace")
    val root = Files.createTempDirectory("task166-store-root")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    try
      val v1 = identity(workspace)
      val v2 = identity(
        workspace,
        targetJava = Some(selectedJava(workspace.resolve("jdk")))
      )
      val store = SbtClasspathCacheStore.local(root, 1.second)

      assert(store.withLock(v1)(_.publish(record(v1, classes))).isRight)
      assert(store.withLock(v2)(_.publish(record(v2, classes))).isRight)

      assert(Files.isRegularFile(root.resolve(s"${v1.storageKey}.json")))
      assert(Files.isRegularFile(root.resolve("v2").resolve(s"${v2.storageKey}.json")))
      assertEquals(store.withLock(v1)(_.read(v1)), Right(record(v1, classes)))
      assertEquals(store.withLock(v2)(_.read(v2)), Right(record(v2, classes)))
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)

  test("missing record and record symlink fail closed"):
    val workspace = Files.createTempDirectory("task071-store-missing")
    val root = Files.createTempDirectory("task071-store-missing-root")
    val cacheIdentity = identity(workspace)
    val store = SbtClasspathCacheStore.local(root, 1.second)
    val target = Files.createTempFile("task071-record-target", ".json")
    try
      val missing = store.withLock(cacheIdentity)(_.read(cacheIdentity))
      assert(missing.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Missing]))

      val recordPath = root.resolve(s"${cacheIdentity.storageKey}.json")
      Files.createSymbolicLink(recordPath, target)
      val symlink = store.withLock(cacheIdentity)(_.read(cacheIdentity))
      assert(symlink.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Invalid]))
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)
      Files.deleteIfExists(target)

  test("lock symlink is rejected before channel open"):
    val workspace = Files.createTempDirectory("task071-lock-symlink")
    val root = Files.createTempDirectory("task071-lock-symlink-root")
    val cacheIdentity = identity(workspace)
    val target = Files.createTempFile("task071-lock-target", ".lock")
    try
      Files.createSymbolicLink(
        root.resolve(s"${cacheIdentity.storageKey}.lock"),
        target
      )
      val result =
        SbtClasspathCacheStore.local(root, 1.second).withLock(cacheIdentity)(_ =>
          Right(())
        )
      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Invalid]))
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)
      Files.deleteIfExists(target)

  test("competing same-key operation fails within bounded lock wait"):
    val workspace = Files.createTempDirectory("task071-lock-contention")
    val root = Files.createTempDirectory("task071-lock-contention-root")
    val cacheIdentity = identity(workspace)
    val store = SbtClasspathCacheStore.local(root, 50.millis)
    try
      val result = store.withLock(cacheIdentity) { _ =>
        val competing = store.withLock(cacheIdentity)(_ => Right(()))
        Right(competing)
      }

      assert(
        result.exists(
          _.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.LockTimeout])
        )
      )
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)

  test("atomic publication failure preserves the prior complete record"):
    val workspace = Files.createTempDirectory("task071-store-atomic")
    val root = Files.createTempDirectory("task071-store-atomic-root")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    val cacheIdentity = identity(workspace)
    val initial = record(cacheIdentity, classes, acquiredAt = 1L)
    val replacement = initial.copy(acquiredAtEpochMillis = 2L)
    val normal = SbtClasspathCacheStore.local(root, 1.second)
    val failingMover = new AtomicSbtClasspathCacheMover:
      override def move(source: Path, destination: Path): Unit =
        throw new java.io.IOException("simulated atomic move failure")
    val failing = LocalSbtClasspathCacheStore(
      SbtClasspathCacheRoot.fixed(root),
      1.second,
      failingMover
    )
    try
      assert(normal.withLock(cacheIdentity)(_.publish(initial)).isRight)
      val failed = failing.withLock(cacheIdentity)(_.publish(replacement))
      val reread = normal.withLock(cacheIdentity)(_.read(cacheIdentity))

      assert(failed.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Publication]))
      assertEquals(reread, Right(initial))
      val tempPrefix = s"${cacheIdentity.storageKey}.json.tmp-"
      val stream = Files.list(root)
      try assertEquals(stream.toArray.map(_.asInstanceOf[Path]).count(_.getFileName.toString.startsWith(tempPrefix)), 0)
      finally stream.close()
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)

  test("symbolic cache root is rejected"):
    val workspace = Files.createTempDirectory("task071-root-symlink-workspace")
    val actualRoot = Files.createTempDirectory("task071-root-symlink-actual")
    val parent = Files.createTempDirectory("task071-root-symlink-parent")
    val alias = parent.resolve("cache")
    try
      Files.createSymbolicLink(alias, actualRoot)
      val result =
        SbtClasspathCacheStore.local(alias, 1.second).withLock(identity(workspace))(_ =>
          Right(())
        )
      assert(
        result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.PermissionOrIo])
      )
    finally
      deleteRecursively(parent)
      deleteRecursively(actualRoot)
      deleteRecursively(workspace)
