package semantic.harness.tasty

import coursierapi.{Dependency, Fetch, MavenRepository}
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

enum ExactTastyInspectorFailure:
  case UnsupportedTargetScala
  case InspectorUnavailable
  case InspectorFailed

final case class ExactTastyTreeEvidence(
    candidate: TastyArtifactCandidate,
    tree: TastySelectedTree,
    startOffset: Int,
    endOffset: Int
)

final case class ExactTastyInspection(
    scalaVersion: String,
    inspectedCount: Int,
    trees: List[ExactTastyTreeEvidence],
    provenance: TastyInspectorProvenance
)

trait ExactTastyInspector:
  def inspect(
      targetScalaVersion: String,
      workspace: Path,
      source: Path,
      line: Int,
      column: Int,
      candidates: List[TastyArtifactCandidate],
      dependencyClasspath: List[Path] = Nil
  ): Either[ExactTastyInspectorFailure, ExactTastyInspection]

object ExactTastyInspector:
  val default: ExactTastyInspector = ProcessExactTastyInspector()

private final case class ProcessExactTastyInspector() extends ExactTastyInspector:
  private val StableScala3 = raw"3\.\d+\.\d+".r
  private val InputMarker = "semantic-scala.internal-tasty-worker-input.v1"
  private val OutputMarker = "semantic-scala.internal-tasty-worker-output.v1"
  private val Resource = "/semantic/harness/tasty/ExactScalaTastyInspectorChild.scala"
  private val MaxProtocolBytes = 256 * 1024
  private val MaxToolchainFiles = 64
  private val MaxToolchainBytes = 256L * 1024L * 1024L

  override def inspect(
      targetScalaVersion: String,
      workspace: Path,
      source: Path,
      line: Int,
      column: Int,
      candidates: List[TastyArtifactCandidate],
      dependencyClasspath: List[Path]
  ): Either[ExactTastyInspectorFailure, ExactTastyInspection] =
    if !StableScala3.matches(targetScalaVersion) then Left(ExactTastyInspectorFailure.UnsupportedTargetScala)
    else
      val temporaryRoot = Files.createTempDirectory("semantic-scala-exact-tasty-")
      try inspectIn(temporaryRoot, targetScalaVersion, workspace, source, line, column, candidates, dependencyClasspath)
      catch case _: Exception => Left(ExactTastyInspectorFailure.InspectorFailed)
      finally deleteTree(temporaryRoot)

  private def inspectIn(
      root: Path,
      version: String,
      workspace: Path,
      source: Path,
      line: Int,
      column: Int,
      candidates: List[TastyArtifactCandidate],
      dependencyClasspath: List[Path]
  ): Either[ExactTastyInspectorFailure, ExactTastyInspection] =
    val workerBytes = readWorkerSource().toRight(ExactTastyInspectorFailure.InspectorUnavailable)
    val toolchain = resolveToolchain(version).toRight(ExactTastyInspectorFailure.InspectorUnavailable)
    for
      bytes <- workerBytes
      jars <- toolchain
      classes <- compileWorker(root, bytes, jars)
      output <- runWorker(root, classes, jars, workspace, source, line, column, candidates, dependencyClasspath)
      parsed <- parseOutput(output, version, candidates)
    yield parsed.copy(
      provenance = TastyInspectorProvenance(
        protocolVersion = OutputMarker,
        implementation = "ExactScalaTastyInspectorChild",
        scalaVersion = version,
        workerSourceSha256 = TastyDigest.sha256(bytes),
        toolchainSha256 = toolchainSha256(jars),
        targetCompilerOptionsReplayed = false,
        targetPluginsReplayed = false
      )
    )

  private def readWorkerSource(): Option[Array[Byte]] =
    Option(getClass.getResourceAsStream(Resource)).flatMap { stream =>
      Try(stream.readAllBytes()).toOption.map { bytes => stream.close(); bytes }
    }

  private def resolveToolchain(version: String): Option[List[Path]] =
    Try {
      val paths = Fetch.create()
        .withRepositories(MavenRepository.of("https://repo1.maven.org/maven2"))
        .addDependencies(Dependency.of("org.scala-lang", "scala3-tasty-inspector_3", version))
        .fetch()
        .asScala
        .toList
        .map(_.toPath.toRealPath())
        .sortBy(_.getFileName.toString)
      if paths.size > MaxToolchainFiles || paths.map(Files.size).sum > MaxToolchainBytes then
        throw IllegalStateException("official Scala toolchain exceeds fixed bounds")
      paths
    }.toOption

  private def compileWorker(
      root: Path,
      sourceBytes: Array[Byte],
      jars: List[Path]
  ): Either[ExactTastyInspectorFailure, Path] =
    val source = root.resolve("ExactScalaTastyInspectorChild.scala")
    val classes = Files.createDirectory(root.resolve("classes"))
    Files.write(source, sourceBytes)
    val classpath = jars.mkString(java.io.File.pathSeparator)
    val command = javaCommandPrefix ++ List(
      "-cp",
      classpath,
      "dotty.tools.dotc.Main",
      "-classpath",
      classpath,
      "-d",
      classes.toString,
      source.toString
    )
    ProcessCapture.run(command, root, 120.seconds, MaxProtocolBytes) match
      case ProcessCaptureResult.Completed(0, _) => Right(classes)
      case _                                    => Left(ExactTastyInspectorFailure.InspectorFailed)

  private def runWorker(
      root: Path,
      classes: Path,
      jars: List[Path],
      workspace: Path,
      source: Path,
      line: Int,
      column: Int,
      candidates: List[TastyArtifactCandidate],
      dependencyClasspath: List[Path]
  ): Either[ExactTastyInspectorFailure, String] =
    val input = root.resolve("worker.input")
    val protocol =
      (List(
        InputMarker,
        s"source=${encode(source.toRealPath().toString)}",
        s"workspace=${encode(workspace.toRealPath().toString)}",
        s"line=$line",
        s"column=$column"
      ) ++ candidates.map(candidate => s"candidate=${encode(candidate.path.toRealPath().toString)}") ++
        dependencyClasspath.distinct.map(path => s"dependency=${encode(path.toRealPath().toString)}"))
        .mkString("\n") + "\n"
    if protocol.getBytes(StandardCharsets.UTF_8).length > MaxProtocolBytes then
      Left(ExactTastyInspectorFailure.InspectorFailed)
    else
      Files.writeString(input, protocol, StandardCharsets.UTF_8)
      val classpath = (classes :: jars).mkString(java.io.File.pathSeparator)
      ProcessCapture.run(
        javaCommandPrefix ++ List(
          "-cp",
          classpath,
          "semantic.harness.tasty.child.ExactScalaTastyInspectorChild",
          input.toString
        ),
        root,
        60.seconds,
        MaxProtocolBytes
      ) match
        case ProcessCaptureResult.Completed(0, output) => Right(output)
        case _                                         => Left(ExactTastyInspectorFailure.InspectorFailed)

  private def parseOutput(
      output: String,
      expectedVersion: String,
      candidates: List[TastyArtifactCandidate]
  ): Either[ExactTastyInspectorFailure, ExactTastyInspection] =
    val lines = output.linesIterator.toList
    val version = unique(lines, "scalaVersion=").flatMap(decode)
    val inspected = unique(lines, "inspected=").flatMap(_.toIntOption)
    if lines.headOption != Some(OutputMarker) || version != Some(expectedVersion) || inspected.isEmpty then
      Left(ExactTastyInspectorFailure.InspectorFailed)
    else
      val trees = lines.filter(_.startsWith("tree\t")).map(parseTree(_, candidates))
      if trees.exists(_.isEmpty) then Left(ExactTastyInspectorFailure.InspectorFailed)
      else
        Right(
          ExactTastyInspection(
            expectedVersion,
            inspected.get,
            trees.flatten,
            TastyInspectorProvenance(OutputMarker, "", expectedVersion, "", "", false, false)
          )
        )

  private def parseTree(line: String, candidates: List[TastyArtifactCandidate]): Option[ExactTastyTreeEvidence] =
    val fields = line.split("\t", -1).toList
    fields match
      case "tree" :: indexRaw :: startRaw :: endRaw :: startLineRaw :: startColumnRaw :: endLineRaw :: endColumnRaw :: encoded
          if encoded.size == 5 =>
        for
          index <- indexRaw.toIntOption.filter(value => value >= 0 && value < candidates.size)
          start <- startRaw.toIntOption
          end <- endRaw.toIntOption
          startLine <- startLineRaw.toIntOption
          startColumn <- startColumnRaw.toIntOption
          endLine <- endLineRaw.toIntOption
          endColumn <- endColumnRaw.toIntOption
          values <- sequence(encoded.map(decode))
        yield ExactTastyTreeEvidence(
          candidates(index),
          TastySelectedTree(
            values(0),
            TastySourceRange(startLine, startColumn, endLine, endColumn),
            optional(values(1)),
            optional(values(2)),
            optional(values(3)),
            optional(values(4))
          ),
          start,
          end
        )
      case _ => None

  private def unique(lines: List[String], prefix: String): Option[String] =
    lines.filter(_.startsWith(prefix)) match
      case value :: Nil => Some(value.stripPrefix(prefix))
      case _            => None

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): Option[String] =
    Try(new String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toOption

  private def sequence[A](values: List[Option[A]]): Option[List[A]] =
    values.foldRight(Option(List.empty[A]))((value, result) => for x <- value; xs <- result yield x :: xs)

  private def optional(value: String): Option[String] = Option(value).filter(_.nonEmpty)

  private def javaExecutable: String =
    Path.of(sys.props("java.home"), "bin", "java").toString

  private def javaCommandPrefix: List[String] =
    val compatibility =
      if Runtime.version().feature() >= 23 then List("--sun-misc-unsafe-memory-access=allow")
      else Nil
    javaExecutable :: (List("-Xms32m", "-Xmx512m", "-XX:+PerfDisableSharedMem") ++ compatibility)

  private def toolchainSha256(jars: List[Path]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    jars.foreach { path =>
      digest.update(path.getFileName.toString.getBytes(StandardCharsets.UTF_8))
      val stream = Files.newInputStream(path)
      try
        val buffer = Array.ofDim[Byte](8192)
        var read = stream.read(buffer)
        while read >= 0 do
          if read > 0 then digest.update(buffer, 0, read)
          read = stream.read(buffer)
      finally stream.close()
    }
    digest.digest().map("%02x".format(_)).mkString

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()

private enum ProcessCaptureResult:
  case Completed(exitCode: Int, output: String)
  case TimedOut
  case Failed

private object ProcessCapture:
  def run(command: List[String], directory: Path, timeout: FiniteDuration, maxBytes: Int): ProcessCaptureResult =
    val builder = ProcessBuilder(command*).directory(directory.toFile).redirectErrorStream(true)
    val environment = builder.environment()
    environment.clear()
    environment.put("LANG", "C.UTF-8")
    Try(builder.start()).toOption match
      case None => ProcessCaptureResult.Failed
      case Some(process) =>
        val drain = LimitedDrain(process.getInputStream, maxBytes)
        drain.start()
        val completed = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
        if !completed then
          process.destroy()
          if !process.waitFor(2, TimeUnit.SECONDS) then process.destroyForcibly()
        drain.join(5000)
        if completed && !drain.overflow then
          ProcessCaptureResult.Completed(process.exitValue(), drain.output)
        else if completed then ProcessCaptureResult.Failed
        else ProcessCaptureResult.TimedOut

private final class LimitedDrain(stream: InputStream, maxBytes: Int) extends Thread:
  private val buffer = ByteArrayOutputStream()
  @volatile var overflow = false

  def output: String = buffer.toString(StandardCharsets.UTF_8)

  override def run(): Unit =
    val chunk = Array.ofDim[Byte](8192)
    try
      var read = stream.read(chunk)
      while read >= 0 do
        val remaining = maxBytes - buffer.size()
        if remaining > 0 then buffer.write(chunk, 0, math.min(remaining, read))
        if read > remaining then overflow = true
        read = stream.read(chunk)
    catch case _: Exception => ()
    finally Try(stream.close())
