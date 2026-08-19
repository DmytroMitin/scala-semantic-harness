package semantic.harness.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

private[mcp] object CliPathResolver:
  val EnvironmentVariable = "SEMANTIC_SCALA_CLI"

  private val CommandName = "semantic-scala"
  private val MaximumPathCharacters = 32768
  private val MaximumPathEntries = 256
  private val Usage = "Usage: semantic-scala-mcp [--cli <executable>]"
  private val Missing = "semantic-scala executable was not found on the bounded PATH"
  private val Unsafe = "semantic-scala target is not a launchable regular executable"

  def resolve(
    args: List[String],
    environment: Map[String, String] = sys.env
  ): Either[String, Path] =
    args match
      case "--cli" :: rawPath :: Nil => validate(rawPath)
      case Nil =>
        environment
          .get(EnvironmentVariable)
          .filter(_.nonEmpty)
          .map(validate)
          .getOrElse(resolveFromPath(environment.getOrElse("PATH", "")))
      case _ => Left(Usage)

  private def resolveFromPath(pathValue: String): Either[String, Path] =
    if pathValue.length > MaximumPathCharacters then Left(Missing)
    else
      val entries = pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator), -1).toList
      if entries.size > MaximumPathEntries then Left(Missing)
      else
        entries.iterator
          .filter(_.nonEmpty)
          .flatMap(path(_, CommandName))
          .find(isLaunchable)
          .map(path => Right(path.toAbsolutePath.normalize))
          .getOrElse(Left(Missing))

  private def validate(rawPath: String): Either[String, Path] =
    path(rawPath) match
      case Some(candidate) if isLaunchable(candidate) => Right(candidate.toAbsolutePath.normalize)
      case _                                           => Left(Unsafe)

  private def path(rawPath: String): Option[Path] =
    try Some(Path.of(rawPath))
    catch case _: InvalidPathException => None

  private def path(directory: String, child: String): Option[Path] =
    path(directory).flatMap { base =>
      try Some(base.resolve(child))
      catch case _: InvalidPathException => None
    }

  private def isLaunchable(path: Path): Boolean =
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(path)
