package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import SbtClasspathCacheTestSupport.*

class SbtClasspathCacheServiceSuite extends munit.FunSuite:
  test("fresh uses only acquirer and does not resolve or create cache storage"):
    val workspace = Files.createTempDirectory("task071-service-fresh")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    val rootParent = Files.createTempDirectory("task071-service-fresh-parent")
    val root = rootParent.resolve("must-not-exist")
    val acquirer = MutableAcquirer(success(classes))
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root)
    )
    try
      val result = service.resolve(request(workspace), SbtClasspathCacheMode.Fresh)
      assert(result.exists(_.origin == SbtClasspathCacheResolutionOrigin.FreshSbt))
      assertEquals(acquirer.calls, 1)
      assert(!Files.exists(root))
    finally
      deleteRecursively(rootParent)
      deleteRecursively(workspace)

  test("reuse before refresh fails missing and never invokes acquirer"):
    val workspace = Files.createTempDirectory("task071-service-missing")
    val classes = Files.createDirectory(workspace.resolve("classes"))
    val root = Files.createTempDirectory("task071-service-missing-root")
    val acquirer = MutableAcquirer(success(classes))
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root)
    )
    try
      val result = service.resolve(request(workspace), SbtClasspathCacheMode.Reuse)
      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.Missing]))
      assertEquals(acquirer.calls, 0)
    finally
      deleteRecursively(root)
      deleteRecursively(workspace)

  test("refresh publishes complete evidence and repeated reuse avoids sbt"):
    val fixture = workspaceFixture("task071-service-refresh")
    val root = Files.createTempDirectory("task071-service-refresh-root")
    val acquirer = MutableAcquirer(success(fixture.classes))
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root),
      currentTimeMillis = () => 7L
    )
    try
      val refreshed =
        service.resolve(request(fixture.root), SbtClasspathCacheMode.Refresh)
      val firstReuse =
        service.resolve(request(fixture.root), SbtClasspathCacheMode.Reuse)
      val secondReuse =
        service.resolve(request(fixture.root), SbtClasspathCacheMode.Reuse)

      assert(refreshed.exists(_.origin == SbtClasspathCacheResolutionOrigin.FreshSbt))
      assert(
        firstReuse.exists(
          _.origin == SbtClasspathCacheResolutionOrigin.CachedExplicitReuse
        )
      )
      assertEquals(secondReuse, firstReuse)
      assertEquals(acquirer.calls, 1)
      assertEquals(jsonFiles(root), 1)
    finally
      deleteRecursively(root)
      deleteRecursively(fixture.root)

  test("changed conventional source fails stale and does not invoke sbt"):
    val fixture = workspaceFixture("task071-service-source-drift")
    val root = Files.createTempDirectory("task071-service-source-drift-root")
    val acquirer = MutableAcquirer(success(fixture.classes))
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root)
    )
    try
      assert(service.resolve(request(fixture.root), SbtClasspathCacheMode.Refresh).isRight)
      Files.writeString(fixture.source, "object Main { val changed = true }\n")
      val result = service.resolve(request(fixture.root), SbtClasspathCacheMode.Reuse)

      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.StaleEvidence]))
      assertEquals(acquirer.calls, 1)
    finally
      deleteRecursively(root)
      deleteRecursively(fixture.root)

  test("same-path JAR and class-directory changes fail stale without sbt"):
    val root = Files.createTempDirectory("task071-service-entry-drift")
    val workspace = Files.createDirectory(root.resolve("workspace"))
    val source = workspace.resolve("app/src/main/scala/Main.scala")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val classFile = classes.resolve("Main.class")
    val jar = workspace.resolve("target/library.jar")
    val cacheRoot = Files.createDirectory(root.resolve("cache"))
    Files.createDirectories(source.getParent)
    Files.writeString(source, "object Main\n")
    Files.write(classFile, Array[Byte](1))
    Files.write(jar, Array[Byte](2))
    val acquirer = MutableAcquirer(requestValue =>
      Right(
        SbtClasspathResult(
          requestValue.project,
          requestValue.configuration,
          List(
            SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory),
            SbtClasspathEntry(jar, SbtClasspathEntryKind.Jar)
          )
        )
      )
    )
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(cacheRoot)
    )
    try
      assert(service.resolve(request(workspace), SbtClasspathCacheMode.Refresh).isRight)
      Files.write(jar, Array[Byte](3))
      assert(
        service
          .resolve(request(workspace), SbtClasspathCacheMode.Reuse)
          .left
          .exists(_.isInstanceOf[SbtClasspathCacheFailure.StaleEvidence])
      )
      assert(service.resolve(request(workspace), SbtClasspathCacheMode.Refresh).isRight)
      Files.write(classFile, Array[Byte](4))
      assert(
        service
          .resolve(request(workspace), SbtClasspathCacheMode.Reuse)
          .left
          .exists(_.isInstanceOf[SbtClasspathCacheFailure.StaleEvidence])
      )
      assertEquals(acquirer.calls, 2)
    finally deleteRecursively(root)

  test("refresh detects inputs changed during acquisition and publishes nothing"):
    val fixture = workspaceFixture("task071-service-concurrent-input")
    val root = Files.createTempDirectory("task071-service-concurrent-input-root")
    val acquirer = MutableAcquirer { requestValue =>
      Files.writeString(fixture.source, Files.readString(fixture.source) + "// drift\n")
      success(fixture.classes)(requestValue)
    }
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root)
    )
    try
      val result =
        service.resolve(request(fixture.root), SbtClasspathCacheMode.Refresh)
      assert(result.left.exists(_.isInstanceOf[SbtClasspathCacheFailure.StaleEvidence]))
      assertEquals(jsonFiles(root), 0)
      assertEquals(acquirer.calls, 1)
    finally
      deleteRecursively(root)
      deleteRecursively(fixture.root)

  test("failed refresh preserves old record and never falls back to it"):
    val fixture = workspaceFixture("task071-service-no-fallback")
    val root = Files.createTempDirectory("task071-service-no-fallback-root")
    val acquirer = MutableAcquirer(success(fixture.classes))
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root)
    )
    try
      assert(service.resolve(request(fixture.root), SbtClasspathCacheMode.Refresh).isRight)
      val cacheFile = onlyJson(root)
      val before = Files.readAllBytes(cacheFile).toList
      acquirer.handler = _ =>
        Left(SbtClasspathFailure.Process("deliberate refresh failure"))

      val failed =
        service.resolve(request(fixture.root), SbtClasspathCacheMode.Refresh)
      assert(
        failed.left.exists(
          _.isInstanceOf[SbtClasspathCacheFailure.RefreshAcquisition]
        )
      )
      assertEquals(Files.readAllBytes(cacheFile).toList, before)
      assertEquals(acquirer.calls, 2)

      val laterReuse =
        service.resolve(request(fixture.root), SbtClasspathCacheMode.Reuse)
      assert(laterReuse.isRight)
      assertEquals(acquirer.calls, 2)
    finally
      deleteRecursively(root)
      deleteRecursively(fixture.root)

  test("Compile, Test, and project identities publish distinct records"):
    val fixture = workspaceFixture("task071-service-isolation")
    val root = Files.createTempDirectory("task071-service-isolation-root")
    val acquirer = MutableAcquirer { requestValue =>
      Right(
        SbtClasspathResult(
          requestValue.project,
          requestValue.configuration,
          List(
            SbtClasspathEntry(
              fixture.classes,
              SbtClasspathEntryKind.Directory
            )
          )
        )
      )
    }
    val service = SbtClasspathCacheService.local(
      acquirer,
      SbtClasspathCacheStore.local(root)
    )
    try
      val requests = List(
        request(fixture.root),
        request(fixture.root, configuration = SbtClasspathConfiguration.Test),
        request(fixture.root, projectId = "api")
      )
      requests.foreach(value =>
        assert(service.resolve(value, SbtClasspathCacheMode.Refresh).isRight)
      )
      assertEquals(jsonFiles(root), 3)
      assertEquals(acquirer.calls, 3)
    finally
      deleteRecursively(root)
      deleteRecursively(fixture.root)

  private final case class Fixture(root: Path, source: Path, classes: Path)

  private def workspaceFixture(prefix: String): Fixture =
    val root = Files.createTempDirectory(prefix)
    val source = root.resolve("app/src/main/scala/Main.scala")
    val classes = root.resolve("app/target/classes")
    Files.createDirectories(source.getParent)
    Files.createDirectories(classes)
    Files.writeString(source, "object Main\n")
    Files.write(classes.resolve("Main.class"), Array[Byte](1, 2, 3))
    Fixture(root, source, classes)

  private def success(
      classes: Path
  ): SbtClasspathRequest => Either[SbtClasspathFailure, SbtClasspathResult] =
    requestValue =>
      Right(
        SbtClasspathResult(
          requestValue.project,
          requestValue.configuration,
          List(SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory))
        )
      )

  private final case class MutableAcquirer(
      var handler: SbtClasspathRequest => Either[SbtClasspathFailure, SbtClasspathResult]
  ) extends SbtClasspathAcquirer:
    var calls = 0

    override def acquire(
        request: SbtClasspathRequest
    ): Either[SbtClasspathFailure, SbtClasspathResult] =
      calls += 1
      handler(request)

  private def jsonFiles(root: Path): Int =
    val stream = Files.list(root)
    try
      stream
        .filter(path => path.getFileName.toString.endsWith(".json"))
        .count()
        .toInt
    finally stream.close()

  private def onlyJson(root: Path): Path =
    val stream = Files.list(root)
    try
      val values = stream
        .filter(path => path.getFileName.toString.endsWith(".json"))
        .toArray
        .map(_.asInstanceOf[Path])
        .toList
      assertEquals(values.size, 1)
      values.head
    finally stream.close()
