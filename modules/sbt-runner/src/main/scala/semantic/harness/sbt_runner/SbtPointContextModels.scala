package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

final case class SbtPointContextRequest(
    workspace: Path,
    project: SbtProjectId,
    requestedScalaVersion: Option[SbtScalaVersion] = None,
    targetJava: Option[ValidatedSbtJavaHome] = None,
    includeExistingInternalOutputs: Boolean = false,
    requireFreshInternalOutputs: Boolean = false
)

object SbtPointContextRequest:
  def validate(request: SbtPointContextRequest): Either[String, SbtPointContextRequest] =
    try
      val normalized = request.workspace.toAbsolutePath.normalize()
      if request.requireFreshInternalOutputs && !request.includeExistingInternalOutputs then
        Left("fresh internal outputs require existing internal outputs")
      else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
        Left(s"sbt workspace does not exist: $normalized")
      else if Files.isSymbolicLink(normalized) then
        Left("sbt workspace symbolic links are not permitted")
      else if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then
        Left(s"sbt workspace is not a directory: $normalized")
      else Right(request.copy(workspace = normalized.toRealPath()))
    catch case _: Exception => Left("sbt workspace could not be validated safely")

final case class SbtPointContextReceipt(
    project: SbtProjectId,
    configuration: SbtClasspathConfiguration,
    requestedScalaVersion: Option[SbtScalaVersion],
    effectiveScalaVersion: SbtScalaVersion,
    classDirectory: Path,
    semanticdbTargetRoot: Path,
    classDirectoryPresent: Boolean,
    externalDependencyClasspath: List[SbtClasspathEntry],
    targetJavaContext: Option[String],
    includeExistingInternalOutputs: Boolean = false,
    internalDependencies: List[SbtInternalDependencyReceipt] = Nil,
    internalDependencyExclusions: List[SbtInternalDependencyExclusion] = Nil,
    requireFreshInternalOutputs: Boolean = false
)

enum SbtPointContextFailure:
  case Validation(message: String)
  case ScalaSwitch(message: String)
  case ScalaVersionMismatch(message: String)
  case UnknownProject(message: String)
  case Process(message: String)
  case Protocol(message: String)

object SbtPointContextFailure:
  def message(failure: SbtPointContextFailure): String = failure match
    case SbtPointContextFailure.Validation(message) => message
    case SbtPointContextFailure.ScalaSwitch(message) => message
    case SbtPointContextFailure.ScalaVersionMismatch(message) => message
    case SbtPointContextFailure.UnknownProject(message) => message
    case SbtPointContextFailure.Process(message) => message
    case SbtPointContextFailure.Protocol(message) => message
