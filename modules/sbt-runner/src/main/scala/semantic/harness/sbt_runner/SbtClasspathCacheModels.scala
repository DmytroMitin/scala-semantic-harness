package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

enum SbtClasspathCacheMode:
  case Fresh
  case Refresh
  case Reuse

object SbtClasspathCacheMode:
  val FreshValue = "fresh"
  val RefreshValue = "refresh"
  val ReuseValue = "reuse"

  def value(mode: SbtClasspathCacheMode): String =
    mode match
      case SbtClasspathCacheMode.Fresh   => FreshValue
      case SbtClasspathCacheMode.Refresh => RefreshValue
      case SbtClasspathCacheMode.Reuse   => ReuseValue

  def parse(value: String): Either[String, SbtClasspathCacheMode] =
    value match
      case FreshValue   => Right(SbtClasspathCacheMode.Fresh)
      case RefreshValue => Right(SbtClasspathCacheMode.Refresh)
      case ReuseValue   => Right(SbtClasspathCacheMode.Reuse)
      case other =>
        Left(
          s"unsupported sbt cache mode '$other' (supported: fresh, refresh, reuse)"
        )

final case class SbtClasspathCacheIdentity(
  cacheFormat: String,
  acquisitionProtocol: String,
  workspaceDigest: String,
  project: SbtProjectId,
  configuration: SbtClasspathConfiguration,
  storageKey: String,
  sbtJavaHomeDigest: Option[String] = None,
  sbtJavaRuntimeFingerprint: Option[String] = None
)

object SbtClasspathCacheIdentity:
  def from(
      request: SbtClasspathRequest
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheIdentity] =
    SbtClasspathRequest
      .validate(request)
      .left
      .map(SbtClasspathCacheFailure.Invalid.apply)
      .flatMap { validated =>
        try
          val canonicalWorkspace = validated.workspace.toRealPath()
          val workspaceDigest = sha256(canonicalWorkspace.toString.getBytes(StandardCharsets.UTF_8))
          val cacheFormat = validated.targetJava.fold(SbtClasspathCacheRecord.Format)(_ =>
            SbtClasspathCacheRecord.FormatV2
          )
          val acquisitionProtocol = validated.targetJava.fold(SbtClasspathProtocol.Format)(_ =>
            SbtClasspathProtocol.FormatV2
          )
          val keyFields = List(
            cacheFormat,
            acquisitionProtocol,
            workspaceDigest,
            validated.project.value,
            SbtClasspathConfiguration.value(validated.configuration)
          ) ++ validated.targetJava.toList.map(_.sbtJavaHomeDigest)
          Right(
            SbtClasspathCacheIdentity(
              cacheFormat = cacheFormat,
              acquisitionProtocol = acquisitionProtocol,
              workspaceDigest = workspaceDigest,
              project = validated.project,
              configuration = validated.configuration,
              storageKey = sha256(SbtClasspathDigest.lengthDelimited(keyFields)),
              sbtJavaHomeDigest = validated.targetJava.map(_.sbtJavaHomeDigest),
              sbtJavaRuntimeFingerprint = validated.targetJava.map(
                _.sbtJavaRuntimeFingerprint
              )
            )
          )
        catch
          case exception: Exception =>
            Left(
              SbtClasspathCacheFailure.PermissionOrIo(
                s"Unable to resolve canonical sbt workspace identity: ${safeMessage(exception)}"
              )
            )
      }

  private def sha256(bytes: Array[Byte]): String =
    SbtClasspathDigest.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def safeMessage(exception: Exception): String =
    Option(exception.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(
      exception.getClass.getSimpleName
    )

final case class SbtClasspathInputEvidence(
  coverageVersion: String,
  fileCount: Long,
  totalBytes: Long,
  sha256: String,
  projectRootPresent: Boolean
)

final case class SbtClasspathEntryEvidence(
  path: String,
  kind: SbtClasspathEntryKind,
  fileCount: Long,
  totalBytes: Long,
  sha256: String
):
  def entry: SbtClasspathEntry =
    SbtClasspathEntry(Path.of(path), kind)

object SbtClasspathEntryEvidence:
  val CoverageVersion = "classpath-entry-content.v1"

final case class SbtClasspathCacheRecord(
  format: String,
  acquisitionProtocol: String,
  identity: SbtClasspathCacheIdentity,
  acquiredAtEpochMillis: Long,
  inputEvidence: SbtClasspathInputEvidence,
  entryEvidenceCoverageVersion: String,
  entries: List[SbtClasspathEntryEvidence],
  entryCount: Int
)

object SbtClasspathCacheRecord:
  val Format = "semantic-scala.internal-sbt-classpath-cache.v1"
  val FormatV2 = "semantic-scala.internal-sbt-classpath-cache.v2"

enum SbtClasspathCacheResolutionOrigin:
  case FreshSbt
  case CachedExplicitReuse

final case class SbtClasspathCacheResolution(
  result: SbtClasspathResult,
  origin: SbtClasspathCacheResolutionOrigin
)

enum SbtClasspathCacheFailure:
  case Missing(message: String)
  case Invalid(message: String)
  case TargetJavaMismatch(message: String)
  case StaleEvidence(category: String)
  case EvidenceBoundsExceeded(message: String)
  case LockTimeout(message: String)
  case PermissionOrIo(message: String)
  case RefreshAcquisition(failure: SbtClasspathFailure)
  case Publication(message: String)

object SbtClasspathCacheFailure:
  def message(failure: SbtClasspathCacheFailure): String =
    failure match
      case SbtClasspathCacheFailure.Missing(message) => message
      case SbtClasspathCacheFailure.Invalid(message) => message
      case SbtClasspathCacheFailure.TargetJavaMismatch(message) => message
      case SbtClasspathCacheFailure.StaleEvidence(category) =>
        s"Cached sbt classpath $category evidence no longer matches the selected " +
          "workspace/project/configuration; rerun with --sbt-cache-mode refresh."
      case SbtClasspathCacheFailure.EvidenceBoundsExceeded(message) =>
        s"$message; use --sbt-cache-mode fresh or reduce the selected workspace/context."
      case SbtClasspathCacheFailure.LockTimeout(message)       => message
      case SbtClasspathCacheFailure.PermissionOrIo(message)    => message
      case SbtClasspathCacheFailure.RefreshAcquisition(value)  => SbtClasspathFailure.message(value)
      case SbtClasspathCacheFailure.Publication(message)       => message

object SbtClasspathCacheBounds:
  val MaxCacheFileBytes: Long = 32L * 1024L * 1024L
  val MaxClasspathEntries: Int = 4096
  val MaxStoredPathBytes: Int = 16 * 1024
  val MaxInputFiles: Long = 50000L
  val MaxInputFileBytes: Long = 64L * 1024L * 1024L
  val MaxTotalInputBytes: Long = 512L * 1024L * 1024L
  val MaxClassDirectoryFiles: Long = 200000L
  val MaxClassDirectoryFileBytes: Long = 256L * 1024L * 1024L
  val MaxTotalClassDirectoryBytes: Long = 2L * 1024L * 1024L * 1024L
  val MaxJarBytes: Long = 1L * 1024L * 1024L * 1024L
  val MaxTotalJarBytes: Long = 4L * 1024L * 1024L * 1024L

private[sbt_runner] object SbtClasspathDigest:
  def lengthDelimited(values: List[String]): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    values.foreach(value => updateBytes(digest, value.getBytes(StandardCharsets.UTF_8)))
    digest.digest()

  def updateBytes(digest: MessageDigest, bytes: Array[Byte]): Unit =
    digest.update(
      Array(
        ((bytes.length >>> 24) & 0xff).toByte,
        ((bytes.length >>> 16) & 0xff).toByte,
        ((bytes.length >>> 8) & 0xff).toByte,
        (bytes.length & 0xff).toByte
      )
    )
    digest.update(bytes)

  def updateLong(digest: MessageDigest, value: Long): Unit =
    digest.update(
      Array.tabulate[Byte](8)(index => ((value >>> ((7 - index) * 8)) & 0xff).toByte)
    )

  def hex(bytes: Array[Byte]): String =
    bytes.map(value => f"${value & 0xff}%02x").mkString
