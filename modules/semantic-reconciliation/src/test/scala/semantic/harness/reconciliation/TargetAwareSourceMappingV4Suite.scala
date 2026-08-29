package semantic.harness.reconciliation

import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtScalaVersion
import semantic.harness.sbt_runner.SbtSourceMappingRootAcquirer
import semantic.harness.sbt_runner.SbtSourceMappingRootFailure
import semantic.harness.sbt_runner.SbtSourceMappingRootReceipt
import semantic.harness.sbt_runner.SbtSourceMappingRootRequest

class TargetAwareSourceMappingV4Suite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val sourceText = "package example\nobject Main:\n  val answer = 42\n"

  test("v4 selects one Fresh target-owned candidate and reports root-only non-build provenance"):
    val fixture = sharedFixture("target-v4-owned")
    try
      val report = service(fixture).inspect(request(fixture, Some(axis("3.3.7")))).fold(fail(_), identity)

      assertEquals(report.schemaVersion, SemanticdbForSourceReportV4.SchemaVersion)
      assertEquals(report.discovery.status, semantic.harness.semanticdb_reader.SemanticdbForSource.StatusAmbiguous)
      assertEquals(report.targetContext.status, TargetRootAcquisitionStatusV4.Acquired)
      assertEquals(report.targetContext.requestedScalaVersion, Some("3.3.7"))
      assertEquals(report.targetContext.effectiveScalaVersion, Some("3.3.7"))
      assertEquals(report.targetContext.scalaAxisStatus, ScalaAxisStatusV4.RequestedMatched)
      assertEquals(report.targetContext.acquisitionProfile, TargetAcquisitionProfileV4.RootOnlySourceMapping)
      assertEquals(report.targetContext.acquisitionEffect, TargetAcquisitionEffectV4.TargetSourceOutputsNotRequested)
      assertEquals(report.targetContext.buildPerformed, TargetBuildPerformedV4.NotRequested)
      assertEquals(report.targetContext.classDirectory, Some("kernel/jvm/classes"))
      assertEquals(report.targetContext.semanticdbTargetRoot, Some("kernel/jvm/meta"))
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV4.SelectedTargetOwnedFresh)
      assertEquals(report.targetSelection.selectedArtifact.map(_.semanticdb), Some(relativeArtifact("kernel/jvm/meta")))
      assertEquals(decode[SemanticdbForSourceReportV4](report.asJson.noSpaces), Right(report))
    finally deleteRecursively(fixture.root)

  test("omitted axis records the fresh lifecycle build default"):
    val fixture = sharedFixture("target-v4-default", effective = "2.13.18")
    try
      val report = service(fixture).inspect(request(fixture, None)).fold(fail(_), identity)
      assertEquals(report.targetContext.requestedScalaVersion, None)
      assertEquals(report.targetContext.effectiveScalaVersion, Some("2.13.18"))
      assertEquals(report.targetContext.scalaAxisStatus, ScalaAxisStatusV4.BuildDefault)
    finally deleteRecursively(fixture.root)

  test("selection fails closed for multiple stale absent and unsafe target roots"):
    val multiple = sharedFixture("target-v4-multiple")
    writeArtifact(multiple.jvmRoot.resolve("duplicate.semanticdb"), sourceText)
    try
      assertEquals(
        service(multiple).inspect(request(multiple, Some(axis("3.3.7")))).toOption.get.targetSelection.status,
        TargetArtifactSelectionStatusV4.MultipleCandidatesOwnedByTarget
      )
    finally deleteRecursively(multiple.root)

    val stale = sharedFixture("target-v4-stale", jvmIdentity = "older")
    try
      assertEquals(
        service(stale).inspect(request(stale, Some(axis("3.3.7")))).toOption.get.targetSelection.status,
        TargetArtifactSelectionStatusV4.StaleTargetOwnedCandidate
      )
    finally deleteRecursively(stale.root)

    val absent = sharedFixture("target-v4-absent")
    try
      val other = absent.root.resolve("other/meta")
      val changed = absent.receipt.copy(semanticdbTargetRoot = other)
      val report = SemanticdbForSourceServiceV4.withDependencies(FixedAcquirer(Right(changed)), () => ())
        .inspect(request(absent, Some(axis("3.3.7")))).toOption.get
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV4.NoCandidateOwnedByTarget)
    finally deleteRecursively(absent.root)

    val unsafe = sharedFixture("target-v4-unsafe")
    val link = unsafe.root.resolve("linked-meta")
    Files.createSymbolicLink(link, unsafe.jvmRoot)
    try
      val changed = unsafe.receipt.copy(semanticdbTargetRoot = link)
      val report = SemanticdbForSourceServiceV4.withDependencies(FixedAcquirer(Right(changed)), () => ())
        .inspect(request(unsafe, Some(axis("3.3.7")))).toOption.get
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots)
    finally deleteRecursively(unsafe.root)

  test("typed acquisition failures never fall back to workspace selection"):
    val fixture = sharedFixture("target-v4-acquisition")
    val cases = List(
      SbtSourceMappingRootFailure.ScalaSwitch("switch") -> TargetArtifactSelectionStatusV4.ScalaSwitchFailed,
      SbtSourceMappingRootFailure.ScalaVersionMismatch("mismatch") -> TargetArtifactSelectionStatusV4.ScalaAxisMismatch,
      SbtSourceMappingRootFailure.UnknownProject("unknown") -> TargetArtifactSelectionStatusV4.UnknownProject,
      SbtSourceMappingRootFailure.Process("failed") -> TargetArtifactSelectionStatusV4.TargetContextAcquisitionFailed
    )
    try
      cases.foreach { case (failure, expected) =>
        val report = SemanticdbForSourceServiceV4.withDependencies(FixedAcquirer(Left(failure)), () => ())
          .inspect(request(fixture, Some(axis("3.3.7")))).toOption.get
        assertEquals(report.targetSelection.status, expected)
        assertEquals(report.targetSelection.selectedArtifact, None)
      }
    finally deleteRecursively(fixture.root)

  test("final source artifact and root mutations withhold selection"):
    val sourceChanged = sharedFixture("target-v4-final-source")
    try
      val report = service(sourceChanged, () => Files.writeString(sourceChanged.source, sourceText + "// changed\n"))
        .inspect(request(sourceChanged, Some(axis("3.3.7")))).toOption.get
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV4.SourceChangedDuringRequest)
      assertEquals(report.targetSelection.selectedArtifact, None)
    finally deleteRecursively(sourceChanged.root)

    val artifactChanged = sharedFixture("target-v4-final-artifact")
    try
      val report = service(artifactChanged, () => Files.writeString(artifactChanged.jvmArtifact, "changed"))
        .inspect(request(artifactChanged, Some(axis("3.3.7")))).toOption.get
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV4.SelectedArtifactChangedOrRemapped)
      assertEquals(report.targetSelection.selectedArtifact, None)
    finally deleteRecursively(artifactChanged.root)

    val rootChanged = sharedFixture("target-v4-final-root")
    try
      val report = service(rootChanged, () => Files.move(rootChanged.jvmRoot, rootChanged.root.resolve("moved-meta")))
        .inspect(request(rootChanged, Some(axis("3.3.7")))).toOption.get
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots)
      assertEquals(report.targetSelection.selectedArtifact, None)
    finally deleteRecursively(rootChanged.root)

  private def service(fixture: Fixture, hook: () => Unit = () => ()) =
    SemanticdbForSourceServiceV4.withDependencies(FixedAcquirer(Right(fixture.receipt)), hook)

  private def request(fixture: Fixture, requested: Option[SbtScalaVersion]) =
    SemanticdbForSourceTargetRequestV4(fixture.root, fixture.source, project, requested)

  private def sharedFixture(
      label: String,
      jvmIdentity: String = sourceText,
      effective: String = "3.3.7"
  ): Fixture =
    val root = Files.createTempDirectory(label)
    val source = root.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, sourceText)
    val jvmRoot = Files.createDirectories(root.resolve("kernel/jvm/meta"))
    val jsRoot = Files.createDirectories(root.resolve("kernel/js/meta"))
    val classes = Files.createDirectories(root.resolve("kernel/jvm/classes"))
    val jvmArtifact = jvmRoot.resolve(relative + ".semanticdb")
    writeArtifact(jvmArtifact, jvmIdentity)
    writeArtifact(jsRoot.resolve(relative + ".semanticdb"), sourceText)
    val effectiveAxis = axis(effective)
    val receipt = SbtSourceMappingRootReceipt(
      project,
      SbtClasspathConfiguration.Compile,
      Option.when(effective == "3.3.7")(effectiveAxis),
      effectiveAxis,
      classes,
      jvmRoot,
      None
    )
    Fixture(root, source, jvmRoot, jvmArtifact, receipt)

  private def writeArtifact(path: Path, identity: String): Unit =
    Files.createDirectories(path.getParent)
    val occurrence = SymbolOccurrence(Some(Range(2, 2, 2, 12)), "example/Main.answer.", SymbolOccurrence.Role.REFERENCE)
    val document = TextDocument(uri = relative, md5 = md5(identity), occurrences = Seq(occurrence))
    Files.write(path, TextDocuments(documents = Seq(document)).toByteArray)

  private def relativeArtifact(root: String): String = s"$root/$relative.semanticdb"

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString

  private def project: SbtProjectId = SbtProjectId.parse("kernelJVM").fold(fail(_), identity)
  private def axis(value: String): SbtScalaVersion = SbtScalaVersion.parse(value).fold(fail(_), identity)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

  private final case class FixedAcquirer(
      result: Either[SbtSourceMappingRootFailure, SbtSourceMappingRootReceipt]
  ) extends SbtSourceMappingRootAcquirer:
    override def acquire(request: SbtSourceMappingRootRequest) = result

  private final case class Fixture(
      root: Path,
      source: Path,
      jvmRoot: Path,
      jvmArtifact: Path,
      receipt: SbtSourceMappingRootReceipt
  )
