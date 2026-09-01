package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.concurrent.duration.*
import semantic.harness.sbt_runner.SbtInternalSourceLayoutReceipt

class ZincFreshnessWorkerProcessSuite extends munit.FunSuite:
  test("on-demand runtime resolves once and launches one bounded child for a batch"):
    val fixture = inputFixture("worker-runtime-batch")
    try
      val resolver = RecordingResolver(Right(List(Path.of("dependency.jar"))))
      val process = RecordingProcess()
      val runtime = OnDemandZincFreshnessWorkerRuntime(resolver, process)
      val result = runtime.assess(List(fixture.input.copy(callerId = "a"), fixture.input.copy(callerId = "b")))

      assert(result.isRight)
      assertEquals(resolver.calls, 1)
      assertEquals(process.calls, 1)
      assert(process.command.contains("-Xmx512m"))
      assert(process.command.contains("semantic.harness.reconciliation.worker.ZincFreshnessWorkerMain"))
      assertEquals(process.request.linesIterator.count(_.startsWith("item\t")), 2)
    finally deleteRecursively(fixture.root)

  test("bounded process has a minimal environment and classifies nonzero and oversized streams"):
    val process = JvmBoundedWorkerProcess(deadline = 2.seconds, gracefulCleanup = 100.millis, forcedCleanup = 200.millis)
    val root = Files.createTempDirectory("worker-process-bounds")
    try
      val environment = process.run(command("environment"), root, Array.emptyByteArray).toOption
        .map(bytes => String(bytes, StandardCharsets.UTF_8)).get
      assertEquals(environment.linesIterator.toSet, Set("LANG"))
      assert(process.run(command("nonzero"), root, Array.emptyByteArray).isLeft)
      assert(process.run(command("stdout-overflow"), root, Array.emptyByteArray).isLeft)
      assert(process.run(command("stderr-overflow"), root, Array.emptyByteArray).isLeft)
    finally deleteRecursively(root)

  test("timeout performs bounded forced cleanup"):
    val process = JvmBoundedWorkerProcess(deadline = 100.millis, gracefulCleanup = 100.millis, forcedCleanup = 300.millis)
    val root = Files.createTempDirectory("worker-process-timeout")
    try
      val started = System.nanoTime()
      assert(process.run(command("timeout"), root, Array.emptyByteArray).isLeft)
      val elapsed = (System.nanoTime() - started).nanos
      assert(elapsed < 2.seconds, clue(elapsed))
    finally deleteRecursively(root)

  test("stdin backpressure is bounded by the worker deadline"):
    val process = JvmBoundedWorkerProcess(deadline = 100.millis, gracefulCleanup = 100.millis, forcedCleanup = 300.millis)
    val root = Files.createTempDirectory("worker-process-stdin")
    try
      val started = System.nanoTime()
      val request = Array.fill[Byte](ZincFreshnessWorkerProtocol.MaxProtocolBytes)('x'.toByte)
      assert(process.run(command("stdin-block"), root, request).isLeft)
      val elapsed = (System.nanoTime() - started).nanos
      assert(elapsed < 2.seconds, clue(elapsed))
    finally deleteRecursively(root)

  test("nonzero worker exit reaps an owned descendant"):
    val process = JvmBoundedWorkerProcess(deadline = 2.seconds, gracefulCleanup = 100.millis, forcedCleanup = 500.millis)
    val root = Files.createTempDirectory("worker-process-descendant")
    try
      val pidFile = root.resolve("descendant.pid")
      assert(process.run(command("nonzero-descendant", pidFile.toString), root, Array.emptyByteArray).isLeft)
      val pid = Files.readString(pidFile).trim.toLong
      assert(!ProcessHandle.of(pid).map(_.isAlive).orElse(false), clue(pid))
    finally deleteRecursively(root)

  test("real supported-API worker boundary classifies corrupt and unsupported analyses closed"):
    val fixture = inputFixture("worker-real-reader")
    try
      Files.write(fixture.input.analysisFile, Array[Byte](1, 2, 3, 4))
      val corrupt = InternalOutputFreshnessAssessor.default.assess(
        fixture.root,
        fixture.input.effectiveScalaVersion,
        fixture.input.classDirectory,
        Some(fixture.input.analysisFile),
        Some(fixture.input.sourceLayout)
      )
      assertEquals(
        corrupt.status -> corrupt.reason,
        InternalOutputFreshnessStatusV6.Unverifiable -> InternalOutputFreshnessReasonV6.CorruptOrUnreadableAnalysis
      )

      val output = ZipOutputStream(Files.newOutputStream(fixture.input.analysisFile))
      try
        output.putNextEntry(ZipEntry("not-zinc.bin"))
        output.write(1)
        output.closeEntry()
      finally output.close()
      val unsupported = InternalOutputFreshnessAssessor.default.assess(
        fixture.root,
        fixture.input.effectiveScalaVersion,
        fixture.input.classDirectory,
        Some(fixture.input.analysisFile),
        Some(fixture.input.sourceLayout)
      )
      assertEquals(
        unsupported.status -> unsupported.reason,
        InternalOutputFreshnessStatusV6.Unverifiable -> InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion
      )
    finally deleteRecursively(fixture.root)

  test("warm exact cache never acquires while same-size tampering and cold failure acquire once"):
    val tamperedRoot = Files.createTempDirectory("worker-tampered-cache")
    val coldRoot = Files.createTempDirectory("worker-cold-cache")
    try
      val stream = getClass.getResourceAsStream(
        "/semantic/harness/reconciliation/semantic-scala-zinc-freshness-worker-dependencies.tsv"
      )
      val inventory = try String(stream.readAllBytes(), StandardCharsets.UTF_8).linesIterator.toList
      finally stream.close()
      inventory.foreach { line =>
        val Array(relative, bytes, _) = line.split("\t", -1): @unchecked
        val path = tamperedRoot.resolve("https/repo1.maven.org/maven2").resolve(relative)
        Files.createDirectories(path.getParent)
        val file = java.io.RandomAccessFile(path.toFile, "rw")
        try file.setLength(bytes.toLong)
        finally file.close()
      }
      var warmAcquisitions = 0
      val warm = ExactCoursierWorkerDependencyResolver(coursierapi.Cache.create().getLocation.toPath, () =>
        { warmAcquisitions += 1; throw IllegalStateException("network must not be used") }
      ).resolve()
      assertEquals(warm.map(_.size), Right(42))
      assertEquals(warmAcquisitions, 0)

      var tamperedAcquisitions = 0
      val tampered = ExactCoursierWorkerDependencyResolver(tamperedRoot, () =>
        { tamperedAcquisitions += 1; throw IllegalStateException("tampered cache must fail closed") }
      ).resolve()
      assert(tampered.isLeft)
      assertEquals(tamperedAcquisitions, 1)

      var coldAcquisitions = 0
      val cold = ExactCoursierWorkerDependencyResolver(coldRoot, () =>
        { coldAcquisitions += 1; throw IllegalStateException("offline") }
      ).resolve()
      assert(cold.isLeft)
      assertEquals(coldAcquisitions, 1)
    finally
      deleteRecursively(tamperedRoot)
      deleteRecursively(coldRoot)

  private final case class InputFixture(root: Path, input: ZincFreshnessWorkerInput)

  private final case class RecordingResolver(result: Either[String, List[Path]]) extends WorkerDependencyResolver:
    var calls = 0
    override def resolve(): Either[String, List[Path]] =
      calls += 1
      result

  private final case class RecordingProcess() extends BoundedWorkerProcess:
    var calls = 0
    var command = List.empty[String]
    var request = ""
    override def run(
        value: List[String],
        directory: Path,
        bytes: Array[Byte]
    ): Either[String, Array[Byte]] =
      calls += 1
      command = value
      request = String(bytes, StandardCharsets.UTF_8)
      val ids = request.linesIterator.filter(_.startsWith("item\t")).map(_.split("\t")(1)).toList
      Right((ZincFreshnessWorkerProtocol.ResponseMarker + "\n" + ids.map(id =>
        s"result\t$id\tFresh\tSourceAndProductContentMatch\t1\t1"
      ).mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))

  private def inputFixture(prefix: String): InputFixture =
    val root = Files.createTempDirectory(prefix)
    val classes = Files.createDirectories(root.resolve("producer/target/classes"))
    val analysis = Files.createDirectories(root.resolve("producer/target/zinc")).resolve("inc_compile.zip")
    Files.write(analysis, Array[Byte](1))
    val unmanaged = Files.createDirectories(root.resolve("producer/src/main/scala"))
    val managed = root.resolve("producer/target/src_managed/main")
    InputFixture(root, ZincFreshnessWorkerInput(
      "producer",
      root,
      "2.13.18",
      classes,
      analysis,
      SbtInternalSourceLayoutReceipt(List(unmanaged, managed), List(unmanaged), List(managed), 0)
    ))

  private def command(mode: String, arguments: String*): List[String] =
    val javaExecutable = Path.of(sys.props("java.home"), "bin", "java").toString
    val classes = ZincFreshnessWorkerProcessFixture.getClass.getProtectionDomain.getCodeSource.getLocation.toURI
    val scalaLibrary = classOf[scala.Product].getProtectionDomain.getCodeSource.getLocation.toURI
    val scala3Library = classOf[scala.deriving.Mirror].getProtectionDomain.getCodeSource.getLocation.toURI
    val classpath = List(classes, scalaLibrary, scala3Library).map(uri => Path.of(uri).toString)
      .mkString(java.io.File.pathSeparator)
    List(javaExecutable, "-cp", classpath, ZincFreshnessWorkerProcessFixture.getClass.getName.stripSuffix("$"), mode) ++ arguments

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

object ZincFreshnessWorkerProcessFixture:
  def main(args: Array[String]): Unit = args.headOption match
    case Some("environment") => System.out.print(System.getenv().keySet().toArray.mkString("\n"))
    case Some("nonzero") => System.exit(7)
    case Some("stdout-overflow") => System.out.write(Array.fill[Byte](ZincFreshnessWorkerProtocol.MaxProtocolBytes + 1)('x'.toByte))
    case Some("stderr-overflow") => System.err.write(Array.fill[Byte](64 * 1024 + 1)('x'.toByte))
    case Some("timeout") =>
      Runtime.getRuntime.addShutdownHook(Thread(() => Thread.sleep(10000)))
      Thread.sleep(10000)
    case Some("nonzero-descendant") =>
      val javaExecutable = Path.of(sys.props("java.home"), "bin", "java").toString
      val classpath = sys.props("java.class.path")
      ProcessBuilder(
        javaExecutable,
        "-cp",
        classpath,
        ZincFreshnessWorkerProcessFixture.getClass.getName.stripSuffix("$"),
        "descendant",
        args(1)
      ).inheritIO().start()
      val pidFile = Path.of(args(1))
      while !Files.isRegularFile(pidFile) do Thread.sleep(5)
      Thread.sleep(50)
      System.exit(7)
    case Some("descendant") =>
      Files.writeString(Path.of(args(1)), ProcessHandle.current().pid().toString)
      Thread.sleep(10000)
    case Some("stdin-block") => Thread.sleep(10000)
    case _ => System.exit(8)
