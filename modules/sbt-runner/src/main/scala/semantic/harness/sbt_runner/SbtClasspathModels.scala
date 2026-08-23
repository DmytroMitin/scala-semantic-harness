package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import semantic.harness.core.SbtProjectIdSyntax

final case class SbtProjectId private (value: String)

object SbtProjectId:
  def parse(value: String): Either[String, SbtProjectId] =
    SbtProjectIdSyntax.validate(value).map(SbtProjectId.apply)

enum SbtClasspathConfiguration:
  case Compile
  case Test

object SbtClasspathConfiguration:
  val CompileValue = "Compile"
  val TestValue = "Test"

  def value(configuration: SbtClasspathConfiguration): String =
    configuration match
      case SbtClasspathConfiguration.Compile => CompileValue
      case SbtClasspathConfiguration.Test    => TestValue

  def parse(value: String): Either[String, SbtClasspathConfiguration] =
    value match
      case CompileValue => Right(SbtClasspathConfiguration.Compile)
      case TestValue    => Right(SbtClasspathConfiguration.Test)
      case other =>
        Left(s"unsupported sbt configuration '$other' (supported: Compile, Test)")

enum SbtClasspathEntryKind:
  case Directory
  case Jar

object SbtClasspathEntryKind:
  val DirectoryValue = "Directory"
  val JarValue = "Jar"

  def value(kind: SbtClasspathEntryKind): String =
    kind match
      case SbtClasspathEntryKind.Directory => DirectoryValue
      case SbtClasspathEntryKind.Jar       => JarValue

  def parse(value: String): Either[String, SbtClasspathEntryKind] =
    value match
      case DirectoryValue => Right(SbtClasspathEntryKind.Directory)
      case JarValue       => Right(SbtClasspathEntryKind.Jar)
      case other          => Left(s"unsupported classpath entry kind '$other'")

final case class SbtClasspathRequest(
  workspace: Path,
  project: SbtProjectId,
  configuration: SbtClasspathConfiguration,
  targetJava: Option[ValidatedSbtJavaHome] = None
)

object SbtClasspathRequest:
  def validate(request: SbtClasspathRequest): Either[String, SbtClasspathRequest] =
    val workspace = request.workspace.toAbsolutePath.normalize()
    if !Files.exists(workspace) then Left(s"sbt workspace does not exist: $workspace")
    else if !Files.isDirectory(workspace) then Left(s"sbt workspace is not a directory: $workspace")
    else Right(request.copy(workspace = workspace))

final case class SbtClasspathEntry(
  path: Path,
  kind: SbtClasspathEntryKind
)

object SbtClasspathEntry:
  def validate(entry: SbtClasspathEntry): Either[String, SbtClasspathEntry] =
    val normalized = entry.path.toAbsolutePath.normalize()
    entry.kind match
      case SbtClasspathEntryKind.Directory =>
        if Files.isDirectory(normalized) then Right(entry.copy(path = normalized))
        else Left("acquired Directory entry does not exist as a directory")
      case SbtClasspathEntryKind.Jar =>
        if Files.isRegularFile(normalized) &&
            normalized.getFileName.toString.toLowerCase(Locale.ROOT).endsWith(".jar")
        then Right(entry.copy(path = normalized))
        else Left("acquired Jar entry does not exist as a .jar regular file")

final case class SbtClasspathResult(
  project: SbtProjectId,
  configuration: SbtClasspathConfiguration,
  entries: List[SbtClasspathEntry],
  javaContextToken: Option[String] = None
)

enum SbtClasspathFailure:
  case Validation(message: String)
  case Process(message: String)
  case Protocol(message: String)

object SbtClasspathFailure:
  def message(failure: SbtClasspathFailure): String =
    failure match
      case SbtClasspathFailure.Validation(message) => message
      case SbtClasspathFailure.Process(message)    => message
      case SbtClasspathFailure.Protocol(message)   => message
