package semantic.harness.sbt_runner

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class ValidatedSbtJavaHome private[sbt_runner] (
    canonicalHome: Path,
    binDirectory: Path,
    launcher: Path,
    sbtJavaHomeDigest: String,
    sbtJavaRuntimeFingerprint: String
)

enum SbtJavaHomeFailure:
  case Validation(message: String)
  case Probe(message: String)

object SbtJavaHomeFailure:
  def message(failure: SbtJavaHomeFailure): String =
    failure match
      case SbtJavaHomeFailure.Validation(message) => message
      case SbtJavaHomeFailure.Probe(message)      => message

trait SbtJavaHomeValidator:
  def validate(input: Path): Either[SbtJavaHomeFailure, ValidatedSbtJavaHome]

object SbtJavaHomeValidator:
  val DefaultProbeTimeout: FiniteDuration = 10.seconds
  val MaxLauncherBytes: Long = 64L * 1024L * 1024L
  val MaxReleaseBytes: Long = 1L * 1024L * 1024L
  val MaxProbeStreamBytes: Int = 64 * 1024

  val default: SbtJavaHomeValidator = bounded(DefaultProbeTimeout)

  def bounded(probeTimeout: FiniteDuration): SbtJavaHomeValidator =
    DefaultSbtJavaHomeValidator(probeTimeout)

private final case class DefaultSbtJavaHomeValidator(
    probeTimeout: FiniteDuration
) extends SbtJavaHomeValidator:
  import SbtJavaHomeValidator.*

  override def validate(
      input: Path
  ): Either[SbtJavaHomeFailure, ValidatedSbtJavaHome] =
    if !input.isAbsolute then validation("--sbt-java-home must be an absolute directory")
    else
      try
        val canonicalHome = input.normalize().toRealPath()
        if !Files.isDirectory(canonicalHome, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isReadable(canonicalHome)
        then validation("--sbt-java-home must resolve to a readable directory")
        else
          val binDirectory = canonicalHome.resolve("bin")
          val launcherName =
            if System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
            then "java.exe"
            else "java"
          validateLauncher(canonicalHome, binDirectory.resolve(launcherName)).flatMap {
            launcher =>
              for
                launcherDigest <- hashBoundedFile(
                  launcher,
                  MaxLauncherBytes,
                  "selected Java launcher"
                )
                releaseDigest <- releaseEvidence(canonicalHome.resolve("release"))
                probe <- runProbe(launcher)
              yield
                val homeDigest = digest(
                  List(
                    "semantic-scala.sbt-java-home.v1",
                    canonicalHome.toString,
                    launcher.toString
                  )
                )
                val runtimeFingerprint = digest(
                  List(
                    "semantic-scala.sbt-java-runtime.v1",
                    canonicalHome.toString,
                    launcher.toString,
                    launcherDigest,
                    releaseDigest,
                    probe.stdout,
                    probe.stderr
                  )
                )
                ValidatedSbtJavaHome(
                  canonicalHome = canonicalHome,
                  binDirectory = binDirectory,
                  launcher = launcher,
                  sbtJavaHomeDigest = homeDigest,
                  sbtJavaRuntimeFingerprint = runtimeFingerprint
                )
          }
      catch
        case _: Exception =>
          validation("--sbt-java-home could not be resolved safely")

  private def validateLauncher(
      canonicalHome: Path,
      candidate: Path
  ): Either[SbtJavaHomeFailure, Path] =
    try
      val launcher = candidate.toRealPath()
      if !launcher.startsWith(canonicalHome) then
        validation("--sbt-java-home launcher must remain contained by the selected home")
      else if !Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS) ||
          !Files.isReadable(launcher) || !Files.isExecutable(launcher)
      then
        validation("--sbt-java-home must contain a readable executable Java launcher")
      else Right(launcher)
    catch
      case _: Exception =>
        validation("--sbt-java-home must contain a readable executable Java launcher")

  private def releaseEvidence(path: Path): Either[SbtJavaHomeFailure, String] =
    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Right("release:absent")
    else if Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)
    then validation("--sbt-java-home release metadata is not a safe regular file")
    else
      hashBoundedFile(path, MaxReleaseBytes, "selected Java release metadata")
        .map(value => s"release:sha256:$value")

  private def hashBoundedFile(
      path: Path,
      maximum: Long,
      label: String
  ): Either[SbtJavaHomeFailure, String] =
    var stream: InputStream = null
    try
      val expectedSize = Files.size(path)
      if expectedSize > maximum then
        validation(s"$label exceeds the permitted evidence bound")
      else
        stream = Files.newInputStream(path)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = Array.ofDim[Byte](64 * 1024)
        var total = 0L
        var read = stream.read(buffer)
        while read >= 0 do
          if read > 0 then
            total = Math.addExact(total, read.toLong)
            if total > maximum then
              return validation(s"$label exceeds the permitted evidence bound")
            digest.update(buffer, 0, read)
          read = stream.read(buffer)
        if total != expectedSize || Files.size(path) != expectedSize then
          validation(s"$label changed while evidence was collected")
        else Right(SbtClasspathDigest.hex(digest.digest()))
    catch
      case _: Exception => validation(s"$label could not be read safely")
    finally if stream != null then Try(stream.close())

  private def runProbe(
      launcher: Path
  ): Either[SbtJavaHomeFailure, ProbeOutput] =
    var process: Process = null
    try
      process = ProcessBuilder(launcher.toString, "-version")
        .redirectErrorStream(false)
        .start()
      val stdout = BoundedProbeCollector(process.getInputStream, MaxProbeStreamBytes)
      val stderr = BoundedProbeCollector(process.getErrorStream, MaxProbeStreamBytes)
      stdout.start()
      stderr.start()
      val completed = process.waitFor(probeTimeout.toMillis, TimeUnit.MILLISECONDS)
      if !completed then
        destroyProcessTree(process)
        process.waitFor(1L, TimeUnit.SECONDS)
      stdout.join()
      stderr.join()
      if !completed then probe("Selected Java version probe timed out")
      else if stdout.overflowed || stderr.overflowed then
        probe("Selected Java version probe exceeded the output bound")
      else if process.exitValue() != 0 then
        probe("Selected Java version probe exited unsuccessfully")
      else
        Right(
          ProbeOutput(
            String(stdout.bytes, StandardCharsets.UTF_8),
            String(stderr.bytes, StandardCharsets.UTF_8)
          )
        )
    catch
      case _: Exception => probe("Selected Java version probe could not be started")
    finally
      if process != null && process.isAlive then destroyProcessTree(process)

  private def destroyProcessTree(process: Process): Unit =
    val descendants = process.descendants()
    try descendants.forEach(handle => handle.destroyForcibly())
    finally descendants.close()
    process.destroyForcibly()

  private def digest(values: List[String]): String =
    SbtClasspathDigest.hex(
      MessageDigest
        .getInstance("SHA-256")
        .digest(SbtClasspathDigest.lengthDelimited(values))
    )

  private def validation[A](message: String): Left[SbtJavaHomeFailure, A] =
    Left(SbtJavaHomeFailure.Validation(message))

  private def probe[A](message: String): Left[SbtJavaHomeFailure, A] =
    Left(SbtJavaHomeFailure.Probe(message))

private final case class ProbeOutput(stdout: String, stderr: String)

private final class BoundedProbeCollector(
    stream: InputStream,
    maximum: Int
) extends Thread:
  private val output = ByteArrayOutputStream(math.min(maximum, 8192))
  @volatile private var exceeded = false

  override def run(): Unit =
    val buffer = Array.ofDim[Byte](8192)
    try
      var read = stream.read(buffer)
      while read >= 0 do
        if read > 0 then
          val remaining = maximum + 1 - output.size()
          if remaining > 0 then output.write(buffer, 0, math.min(read, remaining))
          if output.size() > maximum then exceeded = true
        read = stream.read(buffer)
    finally Try(stream.close())

  def bytes: Array[Byte] = output.toByteArray.take(maximum)
  def overflowed: Boolean = exceeded

private[sbt_runner] object SbtJavaEnvironment:
  def configure(
      environment: java.util.Map[String, String],
      selected: ValidatedSbtJavaHome
  ): Unit =
    environment.put("JAVA_HOME", selected.canonicalHome.toString)
    val previousPath = Option(environment.get("PATH")).filter(_.nonEmpty)
    environment.put(
      "PATH",
      previousPath.fold(selected.binDirectory.toString)(value =>
        s"${selected.binDirectory}${File.pathSeparator}$value"
      )
    )

private[sbt_runner] object SbtJavaPrivacy:
  def sanitize(value: String, selected: ValidatedSbtJavaHome): String =
    List(
      selected.launcher.toString,
      selected.binDirectory.toString,
      selected.canonicalHome.toString
    ).distinct
      .sortBy(value => -value.length)
      .foldLeft(value)((current, path) => current.replace(path, "<sbt-java-home>"))

private[sbt_runner] object SbtJavaContext:
  def token(selected: ValidatedSbtJavaHome): String =
    SbtClasspathDigest.hex(
      MessageDigest
        .getInstance("SHA-256")
        .digest(
          SbtClasspathDigest.lengthDelimited(
            List(
              "semantic-scala.sbt-java-context.v1",
              selected.sbtJavaHomeDigest,
              selected.sbtJavaRuntimeFingerprint
            )
          )
        )
    )
