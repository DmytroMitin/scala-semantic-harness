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
      configuration: SbtClasspathConfiguration = SbtClasspathConfiguration.Compile
  ): SbtClasspathRequest =
    SbtClasspathRequest(workspace, project(projectId), configuration)

  def identity(
      workspace: Path,
      projectId: String = "app-2",
      configuration: SbtClasspathConfiguration = SbtClasspathConfiguration.Compile
  ): SbtClasspathCacheIdentity =
    SbtClasspathCacheIdentity
      .from(request(workspace, projectId, configuration))
      .fold(
        failure => throw new AssertionError(SbtClasspathCacheFailure.message(failure)),
        value => value
      )

  def record(
      identity: SbtClasspathCacheIdentity,
      entry: Path,
      inputDigest: String = "1" * 64,
      entryDigest: String = "2" * 64,
      acquiredAt: Long = 1L
  ): SbtClasspathCacheRecord =
    SbtClasspathCacheRecord(
      format = SbtClasspathCacheRecord.Format,
      acquisitionProtocol = SbtClasspathProtocol.Format,
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
