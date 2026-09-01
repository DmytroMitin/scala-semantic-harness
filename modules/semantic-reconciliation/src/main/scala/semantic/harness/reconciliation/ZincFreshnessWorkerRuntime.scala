package semantic.harness.reconciliation

import coursierapi.{Cache, Dependency, Fetch, MavenRepository}
import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.Try

private[reconciliation] trait ZincFreshnessWorkerRuntime:
  def assess(
      inputs: List[ZincFreshnessWorkerInput]
  ): Either[String, Map[String, InternalOutputFreshnessAssessment]]

private[reconciliation] object ZincFreshnessWorkerRuntime:
  val default: ZincFreshnessWorkerRuntime = OnDemandZincFreshnessWorkerRuntime(
    WorkerDependencyResolver.default,
    BoundedWorkerProcess.default
  )

private[reconciliation] trait WorkerDependencyResolver:
  def resolve(): Either[String, List[Path]]

private[reconciliation] object WorkerDependencyResolver:
  val default: WorkerDependencyResolver = ExactCoursierWorkerDependencyResolver()

private final case class WorkerDependencyRecord(relativePath: String, bytes: Long, sha256: String):
  val fileName: String = relativePath.substring(relativePath.lastIndexOf('/') + 1)

private[reconciliation] final case class ExactCoursierWorkerDependencyResolver(
    cacheRoot: Path = Cache.create().getLocation.toPath,
    acquire: () => List[Path] = () => ExactCoursierWorkerDependencyResolver.acquire()
) extends WorkerDependencyResolver:
  private val Resource = "/semantic/harness/reconciliation/semantic-scala-zinc-freshness-worker-dependencies.tsv"
  private val CentralCachePrefix = Path.of("https", "repo1.maven.org", "maven2")

  override def resolve(): Either[String, List[Path]] =
    loadInventory().flatMap { inventory =>
      val cached = inventory.map(record => cacheRoot.resolve(CentralCachePrefix).resolve(record.relativePath))
      if valid(inventory, cached) then Right(cached.sortBy(_.getFileName.toString))
      else acquireOnce(inventory)
    }

  private def acquireOnce(inventory: List[WorkerDependencyRecord]): Either[String, List[Path]] =
    Try(acquire()).toEither.left.map(_ => "exact worker dependency acquisition failed").flatMap { paths =>
      Either.cond(
        valid(inventory, paths),
        paths.sortBy(_.getFileName.toString),
        "resolved worker dependency inventory drifted"
      )
    }

  private def loadInventory(): Either[String, List[WorkerDependencyRecord]] =
    Option(getClass.getResourceAsStream(Resource)).toRight("worker dependency inventory is unavailable").flatMap { stream =>
      Try {
        val bytes = try stream.readNBytes(ZincFreshnessWorkerProtocol.MaxProtocolBytes + 1)
        finally stream.close()
        if bytes.length > ZincFreshnessWorkerProtocol.MaxProtocolBytes then
          throw IllegalStateException("worker dependency inventory is oversized")
        if sha256(bytes) != ZincFreshnessWorkerIdentity.DependencyContentInventorySha256 then
          throw IllegalStateException("worker dependency inventory hash mismatch")
        String(bytes, StandardCharsets.UTF_8).linesIterator.toList.map { line =>
          line.split("\t", -1).toList match
            case path :: bytesRaw :: digest :: Nil
                if !path.startsWith("/") && !path.contains("..") && digest.matches("[0-9a-f]{64}") =>
              WorkerDependencyRecord(path, bytesRaw.toLong, digest)
            case _ => throw IllegalStateException("malformed worker dependency inventory")
        }
      }.toEither.left.map(_ => "worker dependency inventory is invalid")
    }.flatMap { records =>
      val controllerInventory = records.sortBy(_.fileName)
        .map(record => s"${record.fileName}\\t${record.bytes}\n")
        .mkString.getBytes(StandardCharsets.UTF_8)
      Either.cond(
        records.size == ZincFreshnessWorkerIdentity.DependencyArtifactCount &&
          records.map(_.bytes).sum == ZincFreshnessWorkerIdentity.DependencyArtifactBytes &&
          records.map(_.fileName).distinct.size == records.size &&
          sha256(controllerInventory) == ZincFreshnessWorkerIdentity.DependencyInventorySha256,
        records,
        "worker dependency inventory identity mismatch"
      )
    }

  private def valid(inventory: List[WorkerDependencyRecord], paths: List[Path]): Boolean =
    val expected = inventory.map(record => record.fileName -> record).toMap
    paths.size == inventory.size && paths.forall { path =>
      Try {
        expected.get(path.getFileName.toString).exists(record =>
          Files.isRegularFile(path) && Files.size(path) == record.bytes && sha256(path) == record.sha256
        )
      }.getOrElse(false)
    } && paths.map(_.getFileName.toString).distinct.size == paths.size

  private def sha256(path: Path): String = sha256(Files.readAllBytes(path))

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

private[reconciliation] object ExactCoursierWorkerDependencyResolver:
  def acquire(): List[Path] =
    Fetch.create()
      .withRepositories(MavenRepository.of("https://repo1.maven.org/maven2"))
      .addDependencies(
        Dependency.of("org.scala-sbt", "zinc-persist_2.13", ZincFreshnessWorkerIdentity.ZincVersion),
        Dependency.of("net.java.dev.jna", "jna", ZincFreshnessWorkerIdentity.JnaVersion)
      )
      .fetch()
      .asScala.toList.map(_.toPath)

private[reconciliation] trait BoundedWorkerProcess:
  def run(command: List[String], directory: Path, request: Array[Byte]): Either[String, Array[Byte]]

private[reconciliation] object BoundedWorkerProcess:
  val default: BoundedWorkerProcess = JvmBoundedWorkerProcess()

private[reconciliation] final case class JvmBoundedWorkerProcess(
    deadline: FiniteDuration = 30.seconds,
    gracefulCleanup: FiniteDuration = 2.seconds,
    forcedCleanup: FiniteDuration = 3.seconds
) extends BoundedWorkerProcess:
  private val MaxStderrBytes = 64 * 1024
  private val ProcessPollMillis = 10L

  override def run(command: List[String], directory: Path, request: Array[Byte]): Either[String, Array[Byte]] =
    if request.length > ZincFreshnessWorkerProtocol.MaxProtocolBytes then Left("worker request exceeds bound")
    else
      val builder = ProcessBuilder(command*).directory(directory.toFile)
      val environment = builder.environment()
      environment.clear()
      environment.put("LANG", "C.UTF-8")
      Try(builder.start()).toEither.left.map(_ => "worker process launch failed").flatMap { process =>
        val ownedDescendants = mutable.LinkedHashMap.empty[Long, ProcessHandle]
        val stdout = LimitedProcessDrain(process.getInputStream, ZincFreshnessWorkerProtocol.MaxProtocolBytes)
        val stderr = LimitedProcessDrain(process.getErrorStream, MaxStderrBytes)
        val writer = ProcessInputWriter(process.getOutputStream, request)
        stdout.start()
        stderr.start()
        writer.start()
        try
          val operationDeadline = System.nanoTime() + deadline.toNanos
          val completed = waitFor(process, operationDeadline, ownedDescendants, writer)
          joinUntil(writer, operationDeadline)
          observeDescendants(process, ownedDescendants)
          val processAndWriterValid = completed && writer.succeeded && !writer.isAlive &&
            Try(process.exitValue() == 0).getOrElse(false) &&
            !ownedDescendants.values.exists(_.isAlive)
          if processAndWriterValid then
            joinUntil(stdout, operationDeadline)
            joinUntil(stderr, operationDeadline)
          val valid = processAndWriterValid && !stdout.overflow && !stderr.overflow &&
            !stdout.isAlive && !stderr.isAlive
          if valid then Right(stdout.bytes)
          else
            cleanup(process, ownedDescendants, writer, stdout, stderr)
            Left("worker process failed, timed out, or exceeded a fixed transport bound")
        catch
          case NonFatal(_) =>
            cleanup(process, ownedDescendants, writer, stdout, stderr)
            Left("worker process failed, timed out, or exceeded a fixed transport bound")
      }

  private def waitFor(
      process: Process,
      deadlineNanos: Long,
      ownedDescendants: mutable.LinkedHashMap[Long, ProcessHandle],
      writer: ProcessInputWriter
  ): Boolean =
    var completed = false
    while !completed && !writer.failed && System.nanoTime() < deadlineNanos do
      observeDescendants(process, ownedDescendants)
      val remainingMillis = math.max(1L, (deadlineNanos - System.nanoTime()).nanos.toMillis)
      completed = Try(process.waitFor(math.min(ProcessPollMillis, remainingMillis), TimeUnit.MILLISECONDS))
        .getOrElse(false)
    completed

  private def cleanup(
      process: Process,
      ownedDescendants: mutable.LinkedHashMap[Long, ProcessHandle],
      writer: ProcessInputWriter,
      stdout: LimitedProcessDrain,
      stderr: LimitedProcessDrain
  ): Unit =
    val gracefulDeadline = System.nanoTime() + gracefulCleanup.toNanos
    val forcedDeadline = gracefulDeadline + forcedCleanup.toNanos
    observeDescendants(process, ownedDescendants)
    ownedDescendants.values.toList.reverse.filter(_.isAlive).foreach(handle => Try(handle.destroy()))
    if process.isAlive then Try(process.destroy())
    awaitTermination(process, ownedDescendants, gracefulDeadline)
    observeDescendants(process, ownedDescendants)
    if process.isAlive || ownedDescendants.values.exists(_.isAlive) then
      ownedDescendants.values.toList.reverse.filter(_.isAlive).foreach(handle => Try(handle.destroyForcibly()))
      if process.isAlive then Try(process.destroyForcibly())
      awaitTermination(process, ownedDescendants, forcedDeadline)
    joinUntil(writer, forcedDeadline)
    joinUntil(stdout, forcedDeadline)
    joinUntil(stderr, forcedDeadline)

  private def awaitTermination(
      process: Process,
      ownedDescendants: mutable.LinkedHashMap[Long, ProcessHandle],
      deadlineNanos: Long
  ): Unit =
    while (process.isAlive || ownedDescendants.values.exists(_.isAlive)) && System.nanoTime() < deadlineNanos do
      observeDescendants(process, ownedDescendants)
      Thread.sleep(math.min(ProcessPollMillis, math.max(1L, (deadlineNanos - System.nanoTime()).nanos.toMillis)))

  private def observeDescendants(
      process: Process,
      ownedDescendants: mutable.LinkedHashMap[Long, ProcessHandle]
  ): Unit =
    Try {
      val stream = process.descendants()
      try stream.iterator().asScala.foreach(handle => ownedDescendants.update(handle.pid(), handle))
      finally stream.close()
    }

  private def joinUntil(thread: Thread, deadlineNanos: Long): Unit =
    val remainingMillis = math.max(0L, (deadlineNanos - System.nanoTime()).nanos.toMillis)
    if remainingMillis > 0 then thread.join(remainingMillis)

private final class LimitedProcessDrain(stream: InputStream, maxBytes: Int) extends Thread:
  setDaemon(true)
  private val output = ByteArrayOutputStream()
  @volatile var overflow = false

  def bytes: Array[Byte] = output.toByteArray

  override def run(): Unit =
    val buffer = Array.ofDim[Byte](8192)
    try
      var read = stream.read(buffer)
      while read >= 0 do
        val remaining = maxBytes - output.size()
        if remaining > 0 then output.write(buffer, 0, math.min(remaining, read))
        if read > remaining then overflow = true
        read = stream.read(buffer)
    catch case _: Exception => ()
    finally Try(stream.close())

private final class ProcessInputWriter(stream: OutputStream, request: Array[Byte]) extends Thread:
  setDaemon(true)
  @volatile private var completedSuccessfully = false
  @volatile private var writeFailed = false

  def succeeded: Boolean = completedSuccessfully
  def failed: Boolean = writeFailed

  override def run(): Unit =
    try
      stream.write(request)
      stream.close()
      completedSuccessfully = true
    catch
      case _: Exception => writeFailed = true
    finally Try(stream.close())

private[reconciliation] final case class OnDemandZincFreshnessWorkerRuntime(
    resolver: WorkerDependencyResolver,
    process: BoundedWorkerProcess
) extends ZincFreshnessWorkerRuntime:
  private val WorkerResource = "/semantic/harness/reconciliation/semantic-scala-zinc-freshness-worker.jar"
  private val WorkerMain = "semantic.harness.reconciliation.worker.ZincFreshnessWorkerMain"

  override def assess(
      inputs: List[ZincFreshnessWorkerInput]
  ): Either[String, Map[String, InternalOutputFreshnessAssessment]] =
    if Runtime.version().feature() != 21 then Left("worker requires the harness JDK 21 runtime")
    else
      for
        request <- ZincFreshnessWorkerProtocol.encodeRequest(inputs)
        dependencies <- resolver.resolve()
        response <- withWorkerJar { (root, workerJar) =>
          val command = javaCommand(workerJar :: dependencies)
          process.run(command, root, request)
        }
        decoded <- ZincFreshnessWorkerProtocol.decodeResponse(response, inputs.map(_.callerId).toSet)
      yield decoded

  private def withWorkerJar[A](body: (Path, Path) => Either[String, A]): Either[String, A] =
    val root = Try(Files.createTempDirectory("semantic-scala-zinc-freshness-worker-"))
      .toEither.left.map(_ => "worker temporary directory is unavailable")
    root.flatMap { directory =>
      try
        Option(getClass.getResourceAsStream(WorkerResource)).toRight("worker JAR resource is unavailable").flatMap { stream =>
          Try {
            val jar = directory.resolve("worker.jar")
            try Files.copy(stream, jar)
            finally stream.close()
            if fileSha256(jar) != ZincFreshnessWorkerIdentity.WorkerJarSha256 then
              throw IllegalStateException("worker JAR identity mismatch")
            jar
          }.toEither.left.map(_ => "worker JAR resource could not be materialized").flatMap(body(directory, _))
        }
      finally deleteTree(directory)
    }

  private def javaCommand(classpath: List[Path]): List[String] =
    val javaExecutable = Path.of(sys.props("java.home"), "bin", "java").toString
    val compatibility = if Runtime.version().feature() >= 23 then List("--sun-misc-unsafe-memory-access=allow") else Nil
    javaExecutable :: (List("-Xms32m", "-Xmx512m", "-XX:+PerfDisableSharedMem") ++ compatibility ++ List(
      "-cp",
      classpath.mkString(java.io.File.pathSeparator),
      WorkerMain
    ))

  private def deleteTree(root: Path): Unit =
    Try {
      if Files.exists(root) then
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach(path => Try(Files.deleteIfExists(path)))
        finally paths.close()
    }

  private def fileSha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.newInputStream(path)
    val buffer = Array.ofDim[Byte](8192)
    try
      var read = stream.read(buffer)
      while read >= 0 do
        if read > 0 then digest.update(buffer, 0, read)
        read = stream.read(buffer)
    finally stream.close()
    digest.digest().map(value => f"${value & 0xff}%02x").mkString
