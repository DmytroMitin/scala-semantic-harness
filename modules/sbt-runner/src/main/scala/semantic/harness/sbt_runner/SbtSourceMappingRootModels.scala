package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

final case class SbtSourceMappingRootRequest(
    workspace: Path,
    project: SbtProjectId,
    requestedScalaVersion: Option[SbtScalaVersion] = None,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

object SbtSourceMappingRootRequest:
  def validate(
      request: SbtSourceMappingRootRequest
  ): Either[String, SbtSourceMappingRootRequest] =
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

final case class SbtSourceMappingRootReceipt(
    project: SbtProjectId,
    configuration: SbtClasspathConfiguration,
    requestedScalaVersion: Option[SbtScalaVersion],
    effectiveScalaVersion: SbtScalaVersion,
    classDirectory: Path,
    semanticdbTargetRoot: Path,
    targetJavaContext: Option[String]
)

enum SbtSourceMappingRootFailure:
  case Validation(message: String)
  case ScalaSwitch(message: String)
  case ScalaVersionMismatch(message: String)
  case UnknownProject(message: String)
  case Process(message: String)
  case Protocol(message: String)

object SbtSourceMappingRootFailure:
  def message(failure: SbtSourceMappingRootFailure): String = failure match
    case SbtSourceMappingRootFailure.Validation(message) => message
    case SbtSourceMappingRootFailure.ScalaSwitch(message) => message
    case SbtSourceMappingRootFailure.ScalaVersionMismatch(message) => message
    case SbtSourceMappingRootFailure.UnknownProject(message) => message
    case SbtSourceMappingRootFailure.Process(message) => message
    case SbtSourceMappingRootFailure.Protocol(message) => message
