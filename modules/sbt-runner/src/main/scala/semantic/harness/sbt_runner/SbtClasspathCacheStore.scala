package semantic.harness.sbt_runner

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait SbtClasspathCacheRoot:
  def resolve(): Either[SbtClasspathCacheFailure, Path]

object SbtClasspathCacheRoot:
  val default: SbtClasspathCacheRoot = DefaultSbtClasspathCacheRoot()

  def fixed(path: Path): SbtClasspathCacheRoot =
    FixedSbtClasspathCacheRoot(path)

private final case class FixedSbtClasspathCacheRoot(path: Path)
    extends SbtClasspathCacheRoot:
  override def resolve(): Either[SbtClasspathCacheFailure, Path] =
    val normalized = path.toAbsolutePath.normalize()
    if path.toString.trim.isEmpty then
      Left(
        SbtClasspathCacheFailure.PermissionOrIo(
          "Unable to resolve sbt classpath cache root: injected root was empty"
        )
      )
    else Right(normalized)

private final case class DefaultSbtClasspathCacheRoot()
    extends SbtClasspathCacheRoot:
  override def resolve(): Either[SbtClasspathCacheFailure, Path] =
    val xdg = sys.env
      .get("XDG_CACHE_HOME")
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(value => Try(Path.of(value)).toOption)
      .filter(_.isAbsolute)
    val selected = xdg
      .map(_.resolve("semantic-scala").resolve("sbt-classpath").resolve("v1"))
      .orElse(
        Option(System.getProperty("user.home"))
          .map(_.trim)
          .filter(_.nonEmpty)
          .flatMap(value => Try(Path.of(value)).toOption)
          .filter(_.isAbsolute)
          .map(
            _.resolve(".cache")
              .resolve("semantic-scala")
              .resolve("sbt-classpath")
              .resolve("v1")
          )
      )
    selected
      .map(_.toAbsolutePath.normalize())
      .toRight(
        SbtClasspathCacheFailure.PermissionOrIo(
          "Unable to resolve private sbt classpath cache root; set an absolute XDG_CACHE_HOME or user.home."
        )
      )

trait SbtClasspathLockedCache:
  def read(
      identity: SbtClasspathCacheIdentity
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheRecord]

  def publish(
      record: SbtClasspathCacheRecord
  ): Either[SbtClasspathCacheFailure, Unit]

trait SbtClasspathCacheStore:
  def withLock[A](
      identity: SbtClasspathCacheIdentity
  )(
      operation: SbtClasspathLockedCache => Either[SbtClasspathCacheFailure, A]
  ): Either[SbtClasspathCacheFailure, A]

object SbtClasspathCacheStore:
  val DefaultLockTimeout: FiniteDuration =
    SbtClasspathAcquirer.DefaultTimeout + 10.seconds

  def default: SbtClasspathCacheStore =
    LocalSbtClasspathCacheStore(
      SbtClasspathCacheRoot.default,
      DefaultLockTimeout,
      AtomicSbtClasspathCacheMover.default
    )

  def local(
      root: Path,
      lockTimeout: FiniteDuration = DefaultLockTimeout
  ): SbtClasspathCacheStore =
    LocalSbtClasspathCacheStore(
      SbtClasspathCacheRoot.fixed(root),
      lockTimeout,
      AtomicSbtClasspathCacheMover.default
    )

private[sbt_runner] trait AtomicSbtClasspathCacheMover:
  def move(source: Path, destination: Path): Unit

private[sbt_runner] object AtomicSbtClasspathCacheMover:
  val default: AtomicSbtClasspathCacheMover =
    new AtomicSbtClasspathCacheMover:
      override def move(source: Path, destination: Path): Unit =
        Files.move(
          source,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )

private[sbt_runner] final case class LocalSbtClasspathCacheStore(
    root: SbtClasspathCacheRoot,
    lockTimeout: FiniteDuration,
    mover: AtomicSbtClasspathCacheMover
) extends SbtClasspathCacheStore:
  override def withLock[A](
      identity: SbtClasspathCacheIdentity
  )(
      operation: SbtClasspathLockedCache => Either[SbtClasspathCacheFailure, A]
  ): Either[SbtClasspathCacheFailure, A] =
    root.resolve().flatMap { resolvedRoot =>
      ensureRoot(resolvedRoot).flatMap { _ =>
        val lockPath = resolvedRoot.resolve(s"${identity.storageKey}.lock")
        val recordPath = resolvedRoot.resolve(s"${identity.storageKey}.json")
        if Files.isSymbolicLink(lockPath) then
          Left(
            SbtClasspathCacheFailure.Invalid(
              "Invalid sbt classpath cache lock: symbolic links are not allowed"
            )
          )
        else
          acquireLock(lockPath).flatMap { channelAndLock =>
            val (channel, lock) = channelAndLock
            try
              operation(LocalSbtClasspathLockedCache(recordPath, mover))
            finally
              Try(lock.release())
              Try(channel.close())
          }
      }
    }

  private def ensureRoot(path: Path): Either[SbtClasspathCacheFailure, Unit] =
    try
      if Files.isSymbolicLink(path) then
        Left(
          SbtClasspathCacheFailure.PermissionOrIo(
            "Unable to use sbt classpath cache root: symbolic links are not allowed"
          )
        )
      else
        Files.createDirectories(path)
        if Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        then
          Left(
            SbtClasspathCacheFailure.PermissionOrIo(
              "Unable to use sbt classpath cache root as a private directory"
            )
          )
        else
          setOwnerDirectoryPermissions(path)
          Right(())
    catch
      case exception: Exception =>
        Left(ioFailure("Unable to create private sbt classpath cache root", exception))

  private def acquireLock(
      lockPath: Path
  ): Either[SbtClasspathCacheFailure, (FileChannel, java.nio.channels.FileLock)] =
    var channel: FileChannel = null
    try
      channel = FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
      )
      setOwnerFilePermissions(lockPath)
      val deadline = Math.addExact(System.nanoTime(), lockTimeout.toNanos)
      var acquired: java.nio.channels.FileLock = null
      while acquired == null && System.nanoTime() < deadline do
        try acquired = channel.tryLock()
        catch case _: OverlappingFileLockException => ()
        if acquired == null then Thread.sleep(10L)
      if acquired == null then
        channel.close()
        Left(
          SbtClasspathCacheFailure.LockTimeout(
            s"Timed out after ${lockTimeout.toSeconds} seconds waiting for the selected sbt classpath cache lock."
          )
        )
      else Right((channel, acquired))
    catch
      case exception: Exception =>
        if channel != null then Try(channel.close())
        Left(ioFailure("Unable to acquire private sbt classpath cache lock", exception))

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

  private def ioFailure(
      prefix: String,
      exception: Exception
  ): SbtClasspathCacheFailure =
    val detail = Option(exception.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(
      exception.getClass.getSimpleName
    )
    SbtClasspathCacheFailure.PermissionOrIo(s"$prefix: $detail")

private final case class LocalSbtClasspathLockedCache(
    recordPath: Path,
    mover: AtomicSbtClasspathCacheMover
) extends SbtClasspathLockedCache:
  override def read(
      identity: SbtClasspathCacheIdentity
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheRecord] =
    readPath(recordPath, identity, missingIsFailure = true)

  override def publish(
      record: SbtClasspathCacheRecord
  ): Either[SbtClasspathCacheFailure, Unit] =
    val bytes = SbtClasspathCacheCodec.encode(record)
    if bytes.length.toLong > SbtClasspathCacheBounds.MaxCacheFileBytes then
      Left(
        SbtClasspathCacheFailure.Publication(
          "Unable to publish sbt classpath cache: encoded record exceeds the cache-file bound"
        )
      )
    else if Files.isSymbolicLink(recordPath) then
      Left(
        SbtClasspathCacheFailure.Publication(
          "Unable to publish sbt classpath cache: destination is a symbolic link"
        )
      )
    else
      val temporary = recordPath.resolveSibling(
        s"${recordPath.getFileName}.tmp-${UUID.randomUUID()}"
      )
      var channel: FileChannel = null
      try
        channel = FileChannel.open(
          temporary,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS
        )
        setOwnerFilePermissions(temporary)
        val buffer = ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
        channel.close()
        channel = null
        readPath(temporary, record.identity, missingIsFailure = false).flatMap {
          reread =>
            if reread != record then
              Left(
                SbtClasspathCacheFailure.Publication(
                  "Unable to publish sbt classpath cache: temporary record did not round-trip exactly"
                )
              )
            else
              Try(mover.move(temporary, recordPath)).toEither
                .left
                .map(exception =>
                  SbtClasspathCacheFailure.Publication(
                    s"Unable to atomically publish sbt classpath cache: ${safeMessage(exception)}"
                  )
                )
                .map(_ => ())
        }
      catch
        case exception: Exception =>
          Left(
            SbtClasspathCacheFailure.Publication(
              s"Unable to publish sbt classpath cache: ${safeMessage(exception)}"
            )
          )
      finally
        if channel != null then Try(channel.close())
        Try(Files.deleteIfExists(temporary))

  private def readPath(
      path: Path,
      identity: SbtClasspathCacheIdentity,
      missingIsFailure: Boolean
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheRecord] =
    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val message =
        "No cached sbt classpath exists for the selected workspace/project/configuration; rerun with --sbt-cache-mode refresh."
      if missingIsFailure then Left(SbtClasspathCacheFailure.Missing(message))
      else
        Left(
          SbtClasspathCacheFailure.Publication(
            "Unable to validate temporary sbt classpath cache record"
          )
        )
    else if Files.isSymbolicLink(path) then
      Left(
        SbtClasspathCacheFailure.Invalid(
          "Invalid sbt classpath cache record: symbolic links are not allowed"
        )
      )
    else if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      Left(
        SbtClasspathCacheFailure.Invalid(
          "Invalid sbt classpath cache record: expected a regular file"
        )
      )
    else
      try
        val size = Files.size(path)
        if size > SbtClasspathCacheBounds.MaxCacheFileBytes then
          Left(
            SbtClasspathCacheFailure.Invalid(
              s"Invalid sbt classpath cache record: file exceeds ${SbtClasspathCacheBounds.MaxCacheFileBytes} bytes"
            )
          )
        else
          val channel = FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
          )
          try
            val bytes = Array.ofDim[Byte](size.toInt)
            val buffer = ByteBuffer.wrap(bytes)
            while buffer.hasRemaining && channel.read(buffer) >= 0 do ()
            if buffer.hasRemaining then
              Left(
                SbtClasspathCacheFailure.Invalid(
                  "Invalid sbt classpath cache record: file changed while being read"
                )
              )
            else SbtClasspathCacheCodec.decode(bytes, identity)
          finally channel.close()
      catch
        case exception: Exception =>
          Left(
            SbtClasspathCacheFailure.PermissionOrIo(
              s"Unable to read private sbt classpath cache record: ${safeMessage(exception)}"
            )
          )

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

  private def safeMessage(exception: Throwable): String =
    Option(exception.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(
      exception.getClass.getSimpleName
    )
