package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

final case class SbtTargetContextRequest(
    workspace: Path,
    project: SbtProjectId,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

object SbtTargetContextRequest:
  def validate(request: SbtTargetContextRequest): Either[String, SbtTargetContextRequest] =
    try
      val normalized = request.workspace.toAbsolutePath.normalize()
      if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
        Left(s"sbt workspace does not exist: $normalized")
      else if Files.isSymbolicLink(normalized) then
        Left("sbt workspace symbolic links are not permitted")
      else if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then
        Left(s"sbt workspace is not a directory: $normalized")
      else Right(request.copy(workspace = normalized.toRealPath()))
    catch case _: Exception => Left("sbt workspace could not be validated safely")

final case class SbtTargetContextReceipt(
    project: SbtProjectId,
    configuration: SbtClasspathConfiguration,
    classDirectory: Path,
    semanticdbTargetRoot: Path,
    classpath: List[SbtClasspathEntry],
    scalaVersion: String,
    targetJavaContext: Option[String]
)
