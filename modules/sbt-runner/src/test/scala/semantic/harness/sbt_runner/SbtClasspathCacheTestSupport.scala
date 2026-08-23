package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

object SbtClasspathCacheTestSupport:
  def project(value: String): SbtProjectId =
    SbtProjectId
      .parse(value)
      .fold(message => throw new AssertionError(message), value => value)

  def request(
      workspace: Path,
      projectId: String = "app-2",
      configuration: SbtClasspathConfiguration = SbtClasspathConfiguration.Compile,
      targetJava: Option[ValidatedSbtJavaHome] = None
  ): SbtClasspathRequest =
    SbtClasspathRequest(workspace, project(projectId), configuration, targetJava)

  def identity(
      workspace: Path,
      projectId: String = "app-2",
      configuration: SbtClasspathConfiguration = SbtClasspathConfiguration.Compile,
      targetJava: Option[ValidatedSbtJavaHome] = None
  ): SbtClasspathCacheIdentity =
    SbtClasspathCacheIdentity
      .from(request(workspace, projectId, configuration, targetJava))
      .fold(
        failure => throw new AssertionError(SbtClasspathCacheFailure.message(failure)),
        value => value
      )

  def selectedJava(
      home: Path,
      homeDigest: String = "a" * 64,
      runtimeFingerprint: String = "b" * 64
  ): ValidatedSbtJavaHome =
    val canonical = home.toAbsolutePath.normalize()
    ValidatedSbtJavaHome(
      canonicalHome = canonical,
      binDirectory = canonical.resolve("bin"),
      launcher = canonical.resolve("bin/java"),
      sbtJavaHomeDigest = homeDigest,
      sbtJavaRuntimeFingerprint = runtimeFingerprint
    )

  def record(
      identity: SbtClasspathCacheIdentity,
      entry: Path,
      inputDigest: String = "1" * 64,
      entryDigest: String = "2" * 64,
      acquiredAt: Long = 1L
  ): SbtClasspathCacheRecord =
    SbtClasspathCacheRecord(
      format = identity.cacheFormat,
      acquisitionProtocol = identity.acquisitionProtocol,
      identity = identity,
      acquiredAtEpochMillis = acquiredAt,
      inputEvidence = SbtClasspathInputEvidence(
        "conventional-inputs.v1",
        0L,
        0L,
        inputDigest,
        projectRootPresent = false
      ),
      entryEvidenceCoverageVersion = SbtClasspathEntryEvidence.CoverageVersion,
      entries = List(
        SbtClasspathEntryEvidence(
          entry.toAbsolutePath.normalize().toString,
          SbtClasspathEntryKind.Directory,
          0L,
          0L,
          entryDigest
        )
      ),
      entryCount = 1
    )

  def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
