package semantic.harness.sbt_runner

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.EnumSet
import java.util.zip.ZipFile
import scala.collection.mutable

private[sbt_runner] trait SbtClasspathMaterializer:
  def materialize(
      result: SbtClasspathResult,
      transientRoot: Path
  ): Either[String, SbtClasspathResult]

  def materialize(
      receipt: SbtTastyCompileReceipt,
      transientRoot: Path
  ): Either[String, SbtTastyCompileReceipt]

  def materialize(
      receipt: SbtTargetContextReceipt,
      transientRoot: Path
  ): Either[String, SbtTargetContextReceipt]

private[sbt_runner] object SbtClasspathMaterializer:
  def forWorkspace(workspace: Path): SbtClasspathMaterializer =
    local(
      workspace
        .resolve("target")
        .resolve("semantic-scala")
        .resolve("sbt-materialized-classpath")
        .resolve("v1")
    )

  def local(root: Path): SbtClasspathMaterializer =
    LocalSbtClasspathMaterializer(() => Right(root))

private final case class LocalSbtClasspathMaterializer(
    resolveRoot: () => Either[String, Path]
) extends SbtClasspathMaterializer:
  override def materialize(
      result: SbtClasspathResult,
      transientRoot: Path
  ): Either[String, SbtClasspathResult] =
    materializePaths(result.entries.map(_.path), transientRoot).map { paths =>
      result.copy(entries = result.entries.zip(paths).map { case (entry, path) =>
        entry.copy(path = path)
      })
    }

  override def materialize(
      receipt: SbtTastyCompileReceipt,
      transientRoot: Path
  ): Either[String, SbtTastyCompileReceipt] =
    materializePaths(receipt.dependencyClasspath, transientRoot).map { paths =>
      receipt.copy(dependencyClasspath = paths)
    }

  override def materialize(
      receipt: SbtTargetContextReceipt,
      transientRoot: Path
  ): Either[String, SbtTargetContextReceipt] =
    materializePaths(receipt.classpath.map(_.path), transientRoot).map { paths =>
      receipt.copy(classpath = receipt.classpath.zip(paths).map { case (entry, path) =>
        entry.copy(path = path)
      })
    }

  private def materializePaths(
      paths: List[Path],
      transientRoot: Path
  ): Either[String, List[Path]] =
    if paths.size > SbtClasspathCacheBounds.MaxClasspathEntries then
      Left("Unable to materialize sbt classpath: entry count exceeds the fixed bound")
    else
      validateTransientRoot(transientRoot).flatMap { roots =>
        val (normalizedTransientRoot, realTransientRoot) = roots
        val normalizedPaths = paths.map(_.toAbsolutePath.normalize())
        if normalizedPaths.forall(path => !path.startsWith(normalizedTransientRoot)) then
          Right(normalizedPaths)
        else resolveRoot().flatMap(ensureStableRoot).flatMap { stableRoot =>
          val copied = mutable.Map.empty[Path, Path]
          var totalBytes = 0L
          normalizedPaths.foldLeft[Either[String, List[Path]]](Right(Nil)) { (result, normalized) =>
            result.flatMap { values =>
              if !normalized.startsWith(normalizedTransientRoot) then Right(values :+ normalized)
              else
                copied.get(normalized) match
                  case Some(materialized) => Right(values :+ materialized)
                  case None =>
                    validateTransientJar(normalized, realTransientRoot).flatMap { source =>
                      val size = Files.size(source)
                      if size > SbtClasspathCacheBounds.MaxJarBytes then
                        Left("Unable to materialize sbt classpath: one JAR exceeds the fixed bound")
                      else if totalBytes > SbtClasspathCacheBounds.MaxTotalJarBytes - size then
                        Left("Unable to materialize sbt classpath: total JAR bytes exceed the fixed bound")
                      else
                        persist(source, stableRoot).map { materialized =>
                          totalBytes += size
                          copied.update(normalized, materialized)
                          values :+ materialized
                        }
                    }
            }
          }
        }
      }

  private def validateTransientRoot(root: Path): Either[String, (Path, Path)] =
    try
      val normalized = root.toAbsolutePath.normalize()
      if Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
      then Left("Unable to materialize sbt classpath: transient root is invalid")
      else Right((normalized, normalized.toRealPath()))
    catch case _: Exception => Left("Unable to materialize sbt classpath: transient root is unavailable")

  private def ensureStableRoot(root: Path): Either[String, Path] =
    try
      val normalized = root.toAbsolutePath.normalize()
      if Files.isSymbolicLink(normalized) then
        Left("Unable to materialize sbt classpath: private root is a symbolic link")
      else
        Files.createDirectories(normalized)
        if Files.isSymbolicLink(normalized) ||
            !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        then Left("Unable to materialize sbt classpath: private root is invalid")
        else
          setOwnerDirectoryPermissions(normalized)
          Right(normalized)
    catch case _: Exception => Left("Unable to materialize sbt classpath: private root is unavailable")

  private def validateTransientJar(path: Path, realRoot: Path): Either[String, Path] =
    try
      if Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
        Left("Unable to materialize sbt classpath: transient entry is not a regular file")
      else
        val real = path.toRealPath()
        if !real.startsWith(realRoot) then
          Left("Unable to materialize sbt classpath: transient entry escaped its root")
        else if !Files.isReadable(real) || !isReadableArchive(real) then
          Left("Unable to materialize sbt classpath: transient entry is not a readable JAR")
        else Right(real)
    catch case _: Exception => Left("Unable to materialize sbt classpath: transient entry is unavailable")

  private def persist(source: Path, root: Path): Either[String, Path] =
    try
      val digest = sha256(source)
      val destination = root.resolve(s"$digest.jar")
      if Files.exists(destination, LinkOption.NOFOLLOW_LINKS) then
        verifyExisting(destination, digest).map(_ => destination)
      else
        val temporary = Files.createTempFile(root, ".materializing-", ".tmp")
        try
          Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
          setOwnerFilePermissions(temporary)
          try Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
          catch
            case _: FileAlreadyExistsException => ()
            case _: AtomicMoveNotSupportedException =>
              try Files.move(temporary, destination)
              catch case _: FileAlreadyExistsException => ()
          verifyExisting(destination, digest).map(_ => destination)
        finally Files.deleteIfExists(temporary)
    catch case _: Exception => Left("Unable to materialize sbt classpath: private copy failed")

  private def verifyExisting(path: Path, expectedDigest: String): Either[String, Unit] =
    if Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      Left("Unable to materialize sbt classpath: private copy is invalid")
    else if Files.size(path) > SbtClasspathCacheBounds.MaxJarBytes || sha256(path) != expectedDigest
    then Left("Unable to materialize sbt classpath: private copy digest mismatch")
    else
      setOwnerFilePermissions(path)
      Right(())

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.newInputStream(path)
    try updateDigest(digest, stream)
    finally stream.close()
    SbtClasspathDigest.hex(digest.digest())

  private def updateDigest(digest: MessageDigest, stream: InputStream): Unit =
    val buffer = Array.ofDim[Byte](64 * 1024)
    var read = stream.read(buffer)
    while read != -1 do
      digest.update(buffer, 0, read)
      read = stream.read(buffer)

  private def isReadableArchive(path: Path): Boolean =
    try
      val archive = ZipFile(path.toFile)
      try archive.entries()
      finally archive.close()
      true
    catch case _: Exception => false

  private def setOwnerDirectoryPermissions(path: Path): Unit =
    try
      Files.setPosixFilePermissions(
        path,
        EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE
        )
      )
    catch case _: UnsupportedOperationException => ()

  private def setOwnerFilePermissions(path: Path): Unit =
    try
      Files.setPosixFilePermissions(
        path,
        EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE
        )
      )
    catch case _: UnsupportedOperationException => ()
