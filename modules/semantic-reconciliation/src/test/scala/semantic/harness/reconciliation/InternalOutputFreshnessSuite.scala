package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import semantic.harness.sbt_runner.SbtInternalSourceLayoutReceipt

class InternalOutputFreshnessSuite extends munit.FunSuite:
  test("matching complete source and product content is Fresh and reading changes no bytes"):
    val fixture = freshnessFixture("internal-fresh-clean")
    try
      val before = fixture.tracked.map(path => path -> sha256(path)).toMap
      val result = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      val after = fixture.tracked.map(path => path -> sha256(path)).toMap

      assertEquals(result.status, InternalOutputFreshnessStatusV6.Fresh)
      assertEquals(result.reason, InternalOutputFreshnessReasonV6.SourceAndProductContentMatch)
      assertEquals(result.recordedSourceCount, Some(1))
      assertEquals(result.recordedProductCount, Some(1))
      assertEquals(after, before)
    finally deleteRecursively(fixture.workspace)

  test("source and product content mismatches are Stale with distinct reasons"):
    val sourceFixture = freshnessFixture("internal-fresh-source-stale")
    try
      Files.writeString(sourceFixture.source, "package producer\nclass Changed\n", StandardCharsets.UTF_8)
      val result = assessor(sourceFixture.snapshot).assess(
        sourceFixture.workspace,
        "2.13.18",
        sourceFixture.classes,
        Some(sourceFixture.analysis),
        Some(sourceFixture.layout)
      )
      assertEquals(result.status, InternalOutputFreshnessStatusV6.Stale)
      assertEquals(result.reason, InternalOutputFreshnessReasonV6.SourceContentMismatch)
    finally deleteRecursively(sourceFixture.workspace)

    val productFixture = freshnessFixture("internal-fresh-product-stale")
    try
      Files.write(productFixture.product, Array[Byte](9, 8, 7))
      val result = assessor(productFixture.snapshot).assess(
        productFixture.workspace,
        "2.13.18",
        productFixture.classes,
        Some(productFixture.analysis),
        Some(productFixture.layout)
      )
      assertEquals(result.status, InternalOutputFreshnessStatusV6.Stale)
      assertEquals(result.reason, InternalOutputFreshnessReasonV6.ProductContentMismatch)
    finally deleteRecursively(productFixture.workspace)

  test("missing analysis, reader failures, wrong axis, and missing products never become Fresh"):
    val fixture = freshnessFixture("internal-fresh-unverifiable")
    try
      val missing = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.workspace.resolve("missing-analysis.zip")),
        Some(fixture.layout)
      )
      assertEquals(
        missing.status -> missing.reason,
        InternalOutputFreshnessStatusV6.Unverifiable -> InternalOutputFreshnessReasonV6.AnalysisFileMissing
      )

      val unavailable = InternalOutputFreshnessAssessor.withReader(
        fixedReader(Left(ZincAnalysisReadFailure.AnalysisPathUnavailable))
      ).assess(fixture.workspace, "2.13.18", fixture.classes, Some(fixture.analysis), Some(fixture.layout))
      assertEquals(unavailable.reason, InternalOutputFreshnessReasonV6.AnalysisPathUnavailable)

      val corrupt = InternalOutputFreshnessAssessor.withReader(
        fixedReader(Left(ZincAnalysisReadFailure.CorruptOrUnreadable))
      ).assess(fixture.workspace, "2.13.18", fixture.classes, Some(fixture.analysis), Some(fixture.layout))
      assertEquals(corrupt.reason, InternalOutputFreshnessReasonV6.CorruptOrUnreadableAnalysis)

      val unsupported = InternalOutputFreshnessAssessor.withReader(
        fixedReader(Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion))
      ).assess(fixture.workspace, "2.13.18", fixture.classes, Some(fixture.analysis), Some(fixture.layout))
      assertEquals(unsupported.reason, InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion)

      val wrongAxis = assessor(fixture.snapshot.copy(compilerVersion = "2.12.21")).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      assertEquals(wrongAxis.reason, InternalOutputFreshnessReasonV6.ScalaAxisMismatch)

      val nonContentStamp = assessor(fixture.snapshot.copy(
        sourceStamps = fixture.snapshot.sourceStamps.view.mapValues(_ => "lastModified(1)").toMap
      )).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      assertEquals(
        nonContentStamp.reason,
        InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion
      )

      val backslashAnalysis = fixture.workspace.resolve("producer/target/zinc\\private.zip")
      Files.copy(fixture.analysis, backslashAnalysis)
      val unrepresentableAnalysis = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(backslashAnalysis),
        Some(fixture.layout)
      )
      assertEquals(
        unrepresentableAnalysis.reason,
        InternalOutputFreshnessReasonV6.AnalysisPathUnavailable
      )

      Files.delete(fixture.product)
      val missingProduct = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      assertEquals(missingProduct.reason, InternalOutputFreshnessReasonV6.MissingExpectedProduct)
    finally deleteRecursively(fixture.workspace)

  test("unsafe identities and incomplete current inventories are Unverifiable"):
    val unsafeFixture = freshnessFixture("internal-fresh-unsafe")
    try
      val unsafe = unsafeFixture.snapshot.copy(
        sourceStamps = Map("/outside/private/Producer.scala" -> unsafeFixture.snapshot.sourceStamps.values.head)
      )
      val unsafeResult = assessor(unsafe).assess(
        unsafeFixture.workspace,
        "2.13.18",
        unsafeFixture.classes,
        Some(unsafeFixture.analysis),
        Some(unsafeFixture.layout)
      )
      assertEquals(unsafeResult.reason, InternalOutputFreshnessReasonV6.UnsafeSourceOrProductPath)
    finally deleteRecursively(unsafeFixture.workspace)

    val inventoryFixture = freshnessFixture("internal-fresh-inventory")
    try
      Files.writeString(
        inventoryFixture.source.getParent.resolve("Added.scala"),
        "package producer\nclass Added\n",
        StandardCharsets.UTF_8
      )
      val result = assessor(inventoryFixture.snapshot).assess(
        inventoryFixture.workspace,
        "2.13.18",
        inventoryFixture.classes,
        Some(inventoryFixture.analysis),
        Some(inventoryFixture.layout)
      )
      assertEquals(result.status, InternalOutputFreshnessStatusV6.Unverifiable)
      assertEquals(result.reason, InternalOutputFreshnessReasonV6.SourceInventoryIncompleteOrUnbounded)
    finally deleteRecursively(inventoryFixture.workspace)

    val siblingRootFixture = freshnessFixture("internal-fresh-sibling-root")
    try
      val addedJava = siblingRootFixture.workspace.resolve("producer/src/main/java/producer/Added.java")
      Files.createDirectories(addedJava.getParent)
      Files.writeString(addedJava, "package producer; class Added {}\n", StandardCharsets.UTF_8)
      val result = assessor(siblingRootFixture.snapshot).assess(
        siblingRootFixture.workspace,
        "2.13.18",
        siblingRootFixture.classes,
        Some(siblingRootFixture.analysis),
        Some(siblingRootFixture.layout.copy(
          sourceDirectories = siblingRootFixture.layout.sourceDirectories :+
            siblingRootFixture.workspace.resolve("producer/src/main/java"),
          unmanagedSourceDirectories = siblingRootFixture.layout.unmanagedSourceDirectories :+
            siblingRootFixture.workspace.resolve("producer/src/main/java")
        ))
      )
      assertEquals(result.status, InternalOutputFreshnessStatusV6.Unverifiable)
      assertEquals(result.reason, InternalOutputFreshnessReasonV6.SourceInventoryIncompleteOrUnbounded)
    finally deleteRecursively(siblingRootFixture.workspace)

  test("official Zinc reader classifies malformed containers separately from unsupported layouts"):
    val workspace = Files.createTempDirectory("internal-fresh-reader-format")
    try
      val corrupt = workspace.resolve("corrupt.zip")
      Files.write(corrupt, Array[Byte](1, 2, 3, 4))
      assertEquals(
        ZincAnalysisReader.default.read(corrupt),
        Left(ZincAnalysisReadFailure.CorruptOrUnreadable)
      )

      val unsupported = workspace.resolve("unsupported.zip")
      val output = ZipOutputStream(Files.newOutputStream(unsupported))
      try
        output.putNextEntry(ZipEntry("not-zinc.bin"))
        output.write(Array[Byte](5, 6, 7))
        output.closeEntry()
      finally output.close()
      assertEquals(
        ZincAnalysisReader.default.read(unsupported),
        Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion)
      )

      val tooManyEntries = workspace.resolve("too-many-entries.zip")
      val boundedOutput = ZipOutputStream(Files.newOutputStream(tooManyEntries))
      try
        ("inc_compile.bin" :: "api_companions.bin" ::
          (1 to InternalOutputFreshnessLimits.MaxAnalysisEntries).map(index => s"extra-$index").toList)
          .foreach { name =>
            boundedOutput.putNextEntry(ZipEntry(name))
            boundedOutput.write(1)
            boundedOutput.closeEntry()
          }
      finally boundedOutput.close()
      assertEquals(
        ZincAnalysisReader.default.read(tooManyEntries),
        Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion)
      )
    finally deleteRecursively(workspace)

  test("source layout generators managed residue and oversized content fail closed"):
    val fixture = freshnessFixture("internal-fresh-layout-bounds")
    try
      val generated = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout.copy(sourceGeneratorCount = 1))
      )
      assertEquals(generated.reason, InternalOutputFreshnessReasonV6.GeneratedOrManagedSourceUnbounded)

      val managedSource = fixture.layout.managedSourceDirectories.head.resolve("Generated.scala")
      Files.createDirectories(managedSource.getParent)
      Files.writeString(managedSource, "class Generated\n")
      val managed = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      assertEquals(managed.reason, InternalOutputFreshnessReasonV6.GeneratedOrManagedSourceUnbounded)
      Files.delete(managedSource)

      val oversized = java.io.RandomAccessFile(fixture.source.toFile, "rw")
      try oversized.setLength(InternalOutputFreshnessLimits.MaxContentFileBytes.toLong + 1L)
      finally oversized.close()
      val bounded = assessor(fixture.snapshot).assess(
        fixture.workspace,
        "2.13.18",
        fixture.classes,
        Some(fixture.analysis),
        Some(fixture.layout)
      )
      assertEquals(bounded.reason, InternalOutputFreshnessReasonV6.SourceInventoryIncompleteOrUnbounded)
    finally deleteRecursively(fixture.workspace)

  private final case class Fixture(
      workspace: Path,
      source: Path,
      classes: Path,
      product: Path,
      analysis: Path,
      snapshot: ZincAnalysisSnapshot,
      layout: SbtInternalSourceLayoutReceipt
  ):
    val tracked = List(source, product, analysis)

  private def freshnessFixture(prefix: String): Fixture =
    val workspace = Files.createTempDirectory(prefix)
    val source = Files.createDirectories(workspace.resolve("producer/src/main/scala/producer"))
      .resolve("Producer.scala")
    Files.writeString(source, "package producer\nclass Producer\n", StandardCharsets.UTF_8)
    val classes = Files.createDirectories(workspace.resolve("producer/target/scala-2.13/classes/producer"))
      .getParent
    val product = Files.createDirectories(classes.resolve("producer")).resolve("Producer.class")
    Files.write(product, Array[Byte](1, 2, 3, 4))
    val analysis = Files.createDirectories(workspace.resolve("producer/target/scala-2.13/zinc"))
      .resolve("inc_compile_2.13.zip")
    Files.write(analysis, Array[Byte](5, 6, 7, 8))
    val sourceId = "${BASE}/" + relative(workspace, source)
    val productId = "${BASE}/" + relative(workspace, product)
    Fixture(
      workspace,
      source,
      classes,
      product,
      analysis,
      ZincAnalysisSnapshot(
        compilerVersion = "2.13.18",
        sourceStamps = Map(sourceId -> ZincContentStamp.current(source)),
        productStamps = Map(productId -> ZincContentStamp.current(product)),
        sourceProducts = Map(sourceId -> Set(productId))
      ),
      SbtInternalSourceLayoutReceipt(
        sourceDirectories = List(workspace.resolve("producer/src/main/scala"), workspace.resolve("producer/target/scala-2.13/src_managed/main")),
        unmanagedSourceDirectories = List(workspace.resolve("producer/src/main/scala")),
        managedSourceDirectories = List(workspace.resolve("producer/target/scala-2.13/src_managed/main")),
        sourceGeneratorCount = 0
      )
    )

  private def assessor(snapshot: ZincAnalysisSnapshot): InternalOutputFreshnessAssessor =
    InternalOutputFreshnessAssessor.withReader(fixedReader(Right(snapshot)))

  private def fixedReader(
      result: Either[ZincAnalysisReadFailure, ZincAnalysisSnapshot]
  ): ZincAnalysisReader = new ZincAnalysisReader:
    override def read(path: Path): Either[ZincAnalysisReadFailure, ZincAnalysisSnapshot] = result

  private def relative(workspace: Path, path: Path): String =
    workspace.relativize(path).toString.replace(java.io.File.separatorChar, '/')

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
