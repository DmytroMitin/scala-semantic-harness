package semantic.harness.sbt_runner

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import scala.collection.mutable.ListBuffer
import scala.util.Try

trait SbtClasspathEvidenceCollector:
  def collectInputs(
      workspace: Path
  ): Either[SbtClasspathCacheFailure, SbtClasspathInputEvidence]

  def collectEntries(
      entries: List[SbtClasspathEntry]
  ): Either[SbtClasspathCacheFailure, List[SbtClasspathEntryEvidence]]

object SbtClasspathEvidenceCollector:
  val default: SbtClasspathEvidenceCollector = DeterministicSbtClasspathEvidenceCollector()

private[sbt_runner] final case class DeterministicSbtClasspathEvidenceCollector()
    extends SbtClasspathEvidenceCollector:
  import SbtClasspathCacheBounds.*

  private val InputCoverageVersion = "conventional-inputs.v1"
  private val EntryCoverageVersion = "classpath-entry-content.v1"
  private val ExcludedNames =
    Set(".git", "target", ".bloop", ".metals", ".idea", ".scala-build", "node_modules")

  override def collectInputs(
      workspace: Path
  ): Either[SbtClasspathCacheFailure, SbtClasspathInputEvidence] =
    try
      val canonicalWorkspace = workspace.toRealPath()
      val projectPath = canonicalWorkspace.resolve("project")
      if Files.isSymbolicLink(projectPath) then
        Left(invalidEvidence("a symbolic link was found in the required project evidence scope"))
      else
        collectWorkspaceFiles(canonicalWorkspace).flatMap { files =>
          if files.size.toLong > MaxInputFiles then
            Left(bounds(s"Conventional input evidence exceeded $MaxInputFiles files"))
          else
            val digest = MessageDigest.getInstance("SHA-256")
            val projectRootPresent =
              Files.isDirectory(projectPath, LinkOption.NOFOLLOW_LINKS)
            SbtClasspathDigest.updateBytes(
              digest,
              InputCoverageVersion.getBytes(StandardCharsets.UTF_8)
            )
            SbtClasspathDigest.updateBytes(
              digest,
              (if projectRootPresent then "project-present" else "project-missing")
                .getBytes(StandardCharsets.UTF_8)
            )
            hashFiles(
              files,
              canonicalWorkspace,
              digest,
              MaxInputFileBytes,
              MaxTotalInputBytes,
              "Conventional input"
            ).map { totalBytes =>
              SbtClasspathInputEvidence(
                coverageVersion = InputCoverageVersion,
                fileCount = files.size.toLong,
                totalBytes = totalBytes,
                sha256 = SbtClasspathDigest.hex(digest.digest()),
                projectRootPresent = projectRootPresent
              )
            }
        }
    catch
      case exception: Exception =>
        Left(ioFailure("Unable to collect conventional sbt cache input evidence", exception))

  override def collectEntries(
      entries: List[SbtClasspathEntry]
  ): Either[SbtClasspathCacheFailure, List[SbtClasspathEntryEvidence]] =
    if entries.isEmpty then Left(invalidEvidence("cached classpath contains no entries"))
    else if entries.size > MaxClasspathEntries then
      Left(bounds(s"Classpath evidence exceeded $MaxClasspathEntries entries"))
    else
      var directoryFileCount = 0L
      var directoryBytes = 0L
      var jarBytes = 0L
      entries.foldLeft[Either[SbtClasspathCacheFailure, List[SbtClasspathEntryEvidence]]](
        Right(Nil)
      ) { (result, entry) =>
        result.flatMap { collected =>
          val path = entry.path.toAbsolutePath.normalize()
          val pathBytes = path.toString.getBytes(StandardCharsets.UTF_8)
          if pathBytes.length > MaxStoredPathBytes then
            Left(bounds(s"Classpath entry path exceeded $MaxStoredPathBytes UTF-8 bytes"))
          else if Files.isSymbolicLink(path) then
            Left(invalidEvidence("a symbolic link was found at a classpath entry"))
          else
            entry.kind match
              case SbtClasspathEntryKind.Jar =>
                collectJar(path).flatMap { evidence =>
                  addBounded(jarBytes, evidence.totalBytes, MaxTotalJarBytes, "Total JAR evidence")
                    .map { updated =>
                      jarBytes = updated
                      collected :+ evidence
                    }
                }
              case SbtClasspathEntryKind.Directory =>
                collectDirectory(path).flatMap { evidence =>
                  for
                    updatedFiles <- addBounded(
                      directoryFileCount,
                      evidence.fileCount,
                      MaxClassDirectoryFiles,
                      "Class-directory evidence"
                    )
                    updatedBytes <- addBounded(
                      directoryBytes,
                      evidence.totalBytes,
                      MaxTotalClassDirectoryBytes,
                      "Total class-directory evidence"
                    )
                  yield
                    directoryFileCount = updatedFiles
                    directoryBytes = updatedBytes
                    collected :+ evidence
                }
        }
      }

  def entryCoverageVersion: String = EntryCoverageVersion

  private def collectWorkspaceFiles(
      workspace: Path
  ): Either[SbtClasspathCacheFailure, List[Path]] =
    val files = ListBuffer.empty[Path]
    var failure: Option[SbtClasspathCacheFailure] = None
    Files.walkFileTree(
      workspace,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          val relative = workspace.relativize(directory)
          if relative.getNameCount > 0 && excluded(relative) then
            FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE

        override def visitFile(
            file: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          val relative = workspace.relativize(file)
          if excluded(relative) then FileVisitResult.CONTINUE
          else if attributes.isSymbolicLink && requiredEvidenceScope(relative) then
            failure = Some(
              invalidEvidence(
                "a symbolic link was found in a required conventional input evidence scope"
              )
            )
            FileVisitResult.TERMINATE
          else
            if attributes.isRegularFile && includedInput(relative) then files += file
            FileVisitResult.CONTINUE
    )
    failure.toLeft(sortPaths(files.toList, workspace))

  private def collectJar(
      path: Path
  ): Either[SbtClasspathCacheFailure, SbtClasspathEntryEvidence] =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
        !path.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
    then Left(invalidEvidence("a cached JAR entry is missing or has the wrong filesystem kind"))
    else
      val size = Files.size(path)
      if size > MaxJarBytes then Left(bounds(s"One JAR exceeded $MaxJarBytes bytes"))
      else
        hashRegularFile(path, size, MaxJarBytes, "JAR").map { digest =>
          SbtClasspathEntryEvidence(
            path = path.toString,
            kind = SbtClasspathEntryKind.Jar,
            fileCount = 1L,
            totalBytes = size,
            sha256 = digest
          )
        }

  private def collectDirectory(
      path: Path
  ): Either[SbtClasspathCacheFailure, SbtClasspathEntryEvidence] =
    if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
      Left(
        invalidEvidence(
          "a cached class-directory entry is missing or has the wrong filesystem kind"
        )
      )
    else
      collectDirectoryFiles(path).flatMap { files =>
        if files.size.toLong > MaxClassDirectoryFiles then
          Left(bounds(s"One class directory exceeded $MaxClassDirectoryFiles files"))
        else
          val digest = MessageDigest.getInstance("SHA-256")
          SbtClasspathDigest.updateBytes(
            digest,
            EntryCoverageVersion.getBytes(StandardCharsets.UTF_8)
          )
          hashFiles(
            files,
            path,
            digest,
            MaxClassDirectoryFileBytes,
            MaxTotalClassDirectoryBytes,
            "Class-directory"
          ).map { totalBytes =>
            SbtClasspathEntryEvidence(
              path = path.toString,
              kind = SbtClasspathEntryKind.Directory,
              fileCount = files.size.toLong,
              totalBytes = totalBytes,
              sha256 = SbtClasspathDigest.hex(digest.digest())
            )
          }
      }

  private def collectDirectoryFiles(
      root: Path
  ): Either[SbtClasspathCacheFailure, List[Path]] =
    val files = ListBuffer.empty[Path]
    var failure: Option[SbtClasspathCacheFailure] = None
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def visitFile(
            file: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if attributes.isSymbolicLink then
            failure = Some(
              invalidEvidence("a symbolic link was found inside a class-directory entry")
            )
            FileVisitResult.TERMINATE
          else if attributes.isRegularFile then
            files += file
            FileVisitResult.CONTINUE
          else
            failure = Some(
              invalidEvidence("an unsupported filesystem entry was found in class-directory evidence")
            )
            FileVisitResult.TERMINATE
    )
    failure.toLeft(sortPaths(files.toList, root))

  private def hashFiles(
      files: List[Path],
      root: Path,
      aggregate: MessageDigest,
      maxOneFileBytes: Long,
      maxTotalBytes: Long,
      label: String
  ): Either[SbtClasspathCacheFailure, Long] =
    files.foldLeft[Either[SbtClasspathCacheFailure, Long]](Right(0L)) {
      (totalResult, file) =>
        totalResult.flatMap { total =>
          val relative = slashPath(root.relativize(file))
          val relativeBytes = relative.getBytes(StandardCharsets.UTF_8)
          if relativeBytes.length > SbtClasspathCacheBounds.MaxStoredPathBytes then
            Left(bounds(s"$label relative path exceeded ${SbtClasspathCacheBounds.MaxStoredPathBytes} UTF-8 bytes"))
          else
            val size = Files.size(file)
            if size > maxOneFileBytes then
              Left(bounds(s"$label file exceeded $maxOneFileBytes bytes"))
            else
              addBounded(total, size, maxTotalBytes, s"$label total").flatMap {
                updatedTotal =>
                  SbtClasspathDigest.updateBytes(aggregate, relativeBytes)
                  SbtClasspathDigest.updateLong(aggregate, size)
                  updateFromFile(file, size, aggregate, maxOneFileBytes, label).map(_ =>
                    updatedTotal
                  )
              }
        }
    }

  private def hashRegularFile(
      path: Path,
      expectedSize: Long,
      maxBytes: Long,
      label: String
  ): Either[SbtClasspathCacheFailure, String] =
    val digest = MessageDigest.getInstance("SHA-256")
    updateFromFile(path, expectedSize, digest, maxBytes, label)
      .map(_ => SbtClasspathDigest.hex(digest.digest()))

  private def updateFromFile(
      path: Path,
      expectedSize: Long,
      digest: MessageDigest,
      maxBytes: Long,
      label: String
  ): Either[SbtClasspathCacheFailure, Unit] =
    var stream: InputStream = null
    try
      stream = Files.newInputStream(path)
      val buffer = Array.ofDim[Byte](64 * 1024)
      var total = 0L
      var read = stream.read(buffer)
      while read >= 0 do
        if read > 0 then
          total = Math.addExact(total, read.toLong)
          if total > maxBytes then
            return Left(bounds(s"$label file exceeded $maxBytes bytes while reading"))
          digest.update(buffer, 0, read)
        read = stream.read(buffer)
      if total != expectedSize || Files.size(path) != expectedSize then
        Left(invalidEvidence(s"$label changed while evidence was collected"))
      else Right(())
    catch
      case exception: ArithmeticException =>
        Left(bounds(s"$label byte count overflowed"))
      case exception: Exception =>
        Left(ioFailure(s"Unable to hash $label evidence", exception))
    finally if stream != null then Try(stream.close())

  private def includedInput(relative: Path): Boolean =
    val names = pathNames(relative)
    val rootSbt = names.size == 1 && names.head.endsWith(".sbt")
    val projectInput = names.headOption.contains("project")
    val conventional = names.indices.exists { index =>
      index + 2 < names.size &&
      names(index) == "src" &&
      (names(index + 1) == "main" || names(index + 1) == "test") &&
      Set("scala", "java", "resources").contains(names(index + 2))
    }
    rootSbt || projectInput || conventional

  private def requiredEvidenceScope(relative: Path): Boolean =
    val names = pathNames(relative)
    names.headOption.contains("project") || names.contains("src")

  private def excluded(relative: Path): Boolean =
    pathNames(relative).exists(ExcludedNames.contains)

  private def pathNames(path: Path): List[String] =
    (0 until path.getNameCount).map(index => path.getName(index).toString).toList

  private def slashPath(path: Path): String =
    pathNames(path).mkString("/")

  private def sortPaths(paths: List[Path], root: Path): List[Path] =
    paths.sortWith { (left, right) =>
      compareUnsigned(
        slashPath(root.relativize(left)).getBytes(StandardCharsets.UTF_8),
        slashPath(root.relativize(right)).getBytes(StandardCharsets.UTF_8)
      ) < 0
    }

  private def compareUnsigned(left: Array[Byte], right: Array[Byte]): Int =
    val shared = math.min(left.length, right.length)
    var index = 0
    while index < shared do
      val comparison = (left(index) & 0xff) - (right(index) & 0xff)
      if comparison != 0 then return comparison
      index += 1
    left.length - right.length

  private def addBounded(
      current: Long,
      increment: Long,
      maximum: Long,
      label: String
  ): Either[SbtClasspathCacheFailure, Long] =
    try
      val updated = Math.addExact(current, increment)
      if updated > maximum then Left(bounds(s"$label exceeded $maximum"))
      else Right(updated)
    catch case _: ArithmeticException => Left(bounds(s"$label overflowed"))

  private def invalidEvidence(message: String): SbtClasspathCacheFailure =
    SbtClasspathCacheFailure.Invalid(s"Unable to validate sbt classpath cache evidence: $message")

  private def bounds(message: String): SbtClasspathCacheFailure =
    SbtClasspathCacheFailure.EvidenceBoundsExceeded(message)

  private def ioFailure(prefix: String, exception: Exception): SbtClasspathCacheFailure =
    val detail = Option(exception.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(
      exception.getClass.getSimpleName
    )
    SbtClasspathCacheFailure.PermissionOrIo(s"$prefix: $detail")
