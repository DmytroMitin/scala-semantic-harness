package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

final case class SbtTastyCompileRequest(
    workspace: Path,
    project: SbtProjectId,
    source: Path,
    targetJava: Option[ValidatedSbtJavaHome] = None,
    sourceRelative: String = ""
)

object SbtTastyCompileRequest:
  val MaxSourceBytes: Long = 2L * 1024L * 1024L

  def validate(request: SbtTastyCompileRequest): Either[String, SbtTastyCompileRequest] =
    try
      val workspace = request.workspace.toAbsolutePath.normalize()
      if !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS) then
        Left("Workspace does not exist")
      else if Files.isSymbolicLink(workspace) then
        Left("Workspace symbolic links are not permitted")
      else if !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) then
        Left("Workspace is not a directory")
      else if request.source.isAbsolute then
        Left("TASTy point source must be workspace-relative")
      else
        val source = workspace.resolve(request.source).normalize()
        if !source.startsWith(workspace) then Left("TASTy point source escapes the workspace")
        else if Files.isSymbolicLink(source) then
          Left("TASTy point source symbolic links are not permitted")
        else if !Files.exists(source, LinkOption.NOFOLLOW_LINKS) then
          Left("TASTy point source does not exist")
        else if !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) then
          Left("TASTy point source is not a regular file")
        else if source.getFileName == null || !source.getFileName.toString.endsWith(".scala") then
          Left("TASTy point source must be a .scala file")
        else if Files.size(source) > MaxSourceBytes then
          Left(s"TASTy point source exceeds $MaxSourceBytes bytes")
        else
          val workspaceReal = workspace.toRealPath()
          val sourceReal = source.toRealPath()
          if !sourceReal.startsWith(workspaceReal) then
            Left("TASTy point source resolves outside the workspace")
          else
            Right(
              request.copy(
                workspace = workspaceReal,
                source = sourceReal,
                sourceRelative = workspaceReal
                  .relativize(sourceReal)
                  .toString
                  .replace(java.io.File.separatorChar, '/')
              )
            )
    catch
      case _: Exception => Left("TASTy point source could not be validated safely")

enum SbtTastyCompileStatus:
  case Succeeded
  case Failed

object SbtTastyCompileStatus:
  val SucceededValue = "Succeeded"
  val FailedValue = "Failed"

  def value(status: SbtTastyCompileStatus): String =
    status match
      case SbtTastyCompileStatus.Succeeded => SucceededValue
      case SbtTastyCompileStatus.Failed    => FailedValue

  def parse(value: String): Either[String, SbtTastyCompileStatus] =
    value match
      case SucceededValue => Right(SbtTastyCompileStatus.Succeeded)
      case FailedValue    => Right(SbtTastyCompileStatus.Failed)
      case _              => Left("Invalid TASTy compile receipt status")

final case class SbtTastyCompileReceipt(
    project: SbtProjectId,
    configuration: SbtClasspathConfiguration,
    compileStatus: SbtTastyCompileStatus,
    scalaVersion: Option[String],
    classDirectory: Option[Path],
    sourceIncluded: Boolean,
    targetJavaContext: Option[String],
    dependencyClasspath: List[Path] = Nil
)
