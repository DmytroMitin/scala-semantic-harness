package semantic.harness.reconciliation.worker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class ZincFreshnessWorkerEngineSuite extends munit.FunSuite {
  test("matching complete inventories are Fresh and a source change is Stale") {
    val fixture = WorkerFixture.create("worker-engine-fresh")
    try {
      val engine = WorkerEngine.withReader(_ => Right(fixture.snapshot))
      val fresh = engine.assess(fixture.input)
      assertEquals(fresh.status, "Fresh")
      assertEquals(fresh.reason, "SourceAndProductContentMatch")
      assertEquals(fresh.sourceCount, Some(1))
      assertEquals(fresh.productCount, Some(1))

      Files.writeString(fixture.source, "package producer\nclass Changed\n", StandardCharsets.UTF_8)
      val stale = engine.assess(fixture.input)
      assertEquals(stale.status, "Stale")
      assertEquals(stale.reason, "SourceContentMismatch")
    } finally WorkerFixture.delete(fixture.workspace)
  }

  test("reader failure wrong axis and partial inventories never become Fresh") {
    val fixture = WorkerFixture.create("worker-engine-closed")
    try {
      val unsupported = WorkerEngine.withReader(_ => Left("UnsupportedAnalysisFormatOrVersion"))
        .assess(fixture.input)
      assertEquals(unsupported.status -> unsupported.reason,
        "Unverifiable" -> "UnsupportedAnalysisFormatOrVersion")

      val wrongAxis = WorkerEngine.withReader(_ => Right(fixture.snapshot.copy(compilerVersion = "2.12.21")))
        .assess(fixture.input)
      assertEquals(wrongAxis.status -> wrongAxis.reason, "Unverifiable" -> "ScalaAxisMismatch")

      Files.delete(fixture.product)
      val missing = WorkerEngine.withReader(_ => Right(fixture.snapshot)).assess(fixture.input)
      assertEquals(missing.status -> missing.reason, "Unverifiable" -> "MissingExpectedProduct")
    } finally WorkerFixture.delete(fixture.workspace)
  }

  private final case class WorkerFixture(
      workspace: Path,
      source: Path,
      product: Path,
      input: WorkerInput,
      snapshot: AnalysisSnapshot
  )

  private object WorkerFixture {
    def create(prefix: String): WorkerFixture = {
      val workspace = Files.createTempDirectory(prefix)
      val source = Files.createDirectories(workspace.resolve("producer/src/main/scala/producer"))
        .resolve("Producer.scala")
      Files.writeString(source, "package producer\nclass Producer\n", StandardCharsets.UTF_8)
      val classes = Files.createDirectories(workspace.resolve("producer/target/scala-2.13/classes/producer"))
        .getParent
      val product = classes.resolve("producer/Producer.class")
      Files.write(product, Array[Byte](1, 2, 3, 4))
      val analysis = Files.createDirectories(workspace.resolve("producer/target/scala-2.13/zinc"))
        .resolve("inc_compile.zip")
      Files.write(analysis, Array[Byte](5, 6, 7, 8))
      val sourceId = "${BASE}/" + relative(workspace, source)
      val productId = "${BASE}/" + relative(workspace, product)
      val input = WorkerInput(
        "producer",
        workspace,
        "2.13.18",
        classes,
        analysis,
        0,
        Set(
          workspace.resolve("producer/src/main/scala"),
          workspace.resolve("producer/target/src_managed/main")
        ),
        Set(workspace.resolve("producer/src/main/scala")),
        Set(workspace.resolve("producer/target/src_managed/main"))
      )
      WorkerFixture(workspace, source, product, input, AnalysisSnapshot(
        "2.13.18",
        Map(sourceId -> ContentStamp.current(source)),
        Map(productId -> ContentStamp.current(product)),
        Map(sourceId -> Set(productId))
      ))
    }

    def delete(root: Path): Unit = if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }

    private def relative(workspace: Path, path: Path): String =
      workspace.relativize(path).toString.replace(java.io.File.separatorChar, '/')
  }
}
