package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import semantic.harness.presentation.PresentationCompilerContext
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathEntry
import semantic.harness.sbt_runner.SbtClasspathEntryKind
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtTargetContextAcquirer
import semantic.harness.sbt_runner.SbtTargetContextFailure
import semantic.harness.sbt_runner.SbtTargetContextReceipt
import semantic.harness.sbt_runner.SbtTargetContextRequest

class TargetAwarePointEvidenceV3Suite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val sourceText = "package example\nobject Main:\n  val answer = 42\n"
  private val symbol = "example/Main.answer."

  test("v3 source mapping preserves workspace ambiguity and selects the unique target-owned Fresh candidate"):
    val fixture = sharedFixture("target-v3-owned")
    try
      val report = sourceService(fixture).inspect(sourceRequest(fixture)).fold(fail(_), identity)

      assertEquals(report.schemaVersion, SemanticdbForSourceReportV3.SchemaVersion)
      assertEquals(report.discovery.status, semantic.harness.semanticdb_reader.SemanticdbForSource.StatusAmbiguous)
      assertEquals(report.targetContext.status, TargetContextAcquisitionStatusV3.Acquired)
      assertEquals(report.targetContext.pathProvenanceStatus, TargetPathProvenanceStatusV3.Represented)
      assertEquals(report.targetContext.semanticdbTargetRoot, Some("kernel/jvm/meta"))
      assertEquals(report.targetContext.classpathEntryCount, Some(1))
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.SelectedTargetOwnedFresh)
      assertEquals(report.targetSelection.selectedArtifact.map(_.semanticdb), Some(relativeArtifact("kernel/jvm/meta")))
    finally deleteRecursively(fixture.root)

  test("target ownership fails closed for multiple stale absent and unsafe roots"):
    val multiple = sharedFixture("target-v3-multiple")
    writeArtifact(multiple.jvmRoot.resolve("duplicate.semanticdb"), sourceText)
    try
      val report = sourceService(multiple).inspect(sourceRequest(multiple)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.MultipleCandidatesOwnedByTarget)
    finally deleteRecursively(multiple.root)

    val stale = sharedFixture("target-v3-stale", jvmIdentity = "older")
    try
      val report = sourceService(stale).inspect(sourceRequest(stale)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.StaleTargetOwnedCandidate)
    finally deleteRecursively(stale.root)

    val unverifiable = sharedFixture("target-v3-unverifiable")
    writeUnverifiableArtifact(unverifiable.jvmArtifact)
    try
      val report = sourceService(unverifiable).inspect(sourceRequest(unverifiable)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.SelectedTargetOwnedQualifiedUnverifiable)
    finally deleteRecursively(unverifiable.root)

    val absent = sharedFixture("target-v3-absent")
    try
      val receipt = absent.receipt.copy(semanticdbTargetRoot = absent.root.resolve("other/meta"))
      Files.createDirectories(receipt.semanticdbTargetRoot)
      val report = SemanticdbForSourceServiceV3(FixedAcquirer(Right(receipt)))
        .inspect(sourceRequest(absent)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.NoCandidateOwnedByTarget)
    finally deleteRecursively(absent.root)

    val unsafe = sharedFixture("target-v3-unsafe")
    val link = unsafe.root.resolve("linked-meta")
    Files.createSymbolicLink(link, unsafe.jvmRoot)
    try
      val receipt = unsafe.receipt.copy(semanticdbTargetRoot = link)
      val report = SemanticdbForSourceServiceV3(FixedAcquirer(Right(receipt)))
        .inspect(sourceRequest(unsafe)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.UnsafeOrUnverifiableOutputRootOwnership)
    finally deleteRecursively(unsafe.root)

  test("target acquisition failure and unknown project remain typed and never fall back"):
    val fixture = sharedFixture("target-v3-acquisition")
    try
      val failed = SemanticdbForSourceServiceV3(FixedAcquirer(Left(SbtTargetContextFailure.Process("failed"))))
        .inspect(sourceRequest(fixture)).fold(fail(_), identity)
      val unknown = SemanticdbForSourceServiceV3(FixedAcquirer(Left(SbtTargetContextFailure.UnknownProject("unknown"))))
        .inspect(sourceRequest(fixture)).fold(fail(_), identity)

      assertEquals(failed.targetContext.status, TargetContextAcquisitionStatusV3.AcquisitionFailed)
      assertEquals(failed.targetSelection.status, TargetArtifactSelectionStatusV3.TargetContextAcquisitionFailed)
      assertEquals(unknown.targetContext.status, TargetContextAcquisitionStatusV3.UnknownProject)
      assertEquals(unknown.targetSelection.status, TargetArtifactSelectionStatusV3.UnknownProject)
    finally deleteRecursively(fixture.root)

  test("v3 point evidence uses the same receipt classpath for live evidence and completes Fresh"):
    val fixture = sharedFixture("target-v3-point")
    var contexts = List.empty[PresentationCompilerContext]
    val service = PointEvidenceServiceV3.withDependencies(
      FixedAcquirer(Right(fixture.receipt)),
      (path, _, _, _, context) =>
        contexts = contexts :+ context
        Right(SymbolAtResult(
          symbol = Some(symbol),
          displayName = Some("answer"),
          range = Some(SourceRange(2, 2, 2, 12)),
          source = path.toString
        )),
      () => ()
    )
    try
      val report = service.inspect(pointRequest(fixture)).fold(fail(_), identity)

      assertEquals(report.schemaVersion, PointEvidenceReportV3.SchemaVersion)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.SelectedTargetOwnedFresh)
      assertEquals(contexts.size, 1)
      assertEquals(contexts.head.classpath, PresentationCompilerContext.explicit(fixture.receipt.classpath.map(_.path)).classpath)
      assert(report.reconciliation.outcome.isInstanceOf[ReconciliationOutcomeV2.CompletedFresh])
    finally deleteRecursively(fixture.root)

  test("final source and artifact mutation checks withhold completed target reconciliation"):
    val sourceChanged = sharedFixture("target-v3-final-source")
    try
      val report = pointService(sourceChanged, () => Files.writeString(sourceChanged.source, sourceText + "// changed\n"))
        .inspect(pointRequest(sourceChanged)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, TargetArtifactSelectionStatusV3.SourceChangedDuringRequest)
      assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest))
    finally deleteRecursively(sourceChanged.root)

    val artifactChanged = sharedFixture("target-v3-final-artifact")
    try
      val report = pointService(artifactChanged, () => Files.writeString(artifactChanged.jvmArtifact, "changed"))
        .inspect(pointRequest(artifactChanged)).fold(fail(_), identity)
      assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SelectedArtifactChangedOrRemapped))
    finally deleteRecursively(artifactChanged.root)

  private def pointService(fixture: Fixture, hook: () => Unit): PointEvidenceServiceV3 =
    PointEvidenceServiceV3.withDependencies(
      FixedAcquirer(Right(fixture.receipt)),
      (path, _, _, _, _) => Right(SymbolAtResult(
        symbol = Some(symbol),
        displayName = Some("answer"),
        range = Some(SourceRange(2, 2, 2, 12)),
        source = path.toString
      )),
      hook
    )

  private def sourceService(fixture: Fixture): SemanticdbForSourceServiceV3 =
    SemanticdbForSourceServiceV3(FixedAcquirer(Right(fixture.receipt)))

  private def sourceRequest(fixture: Fixture): SemanticdbForSourceTargetRequest =
    SemanticdbForSourceTargetRequest(fixture.root, fixture.source, project)

  private def pointRequest(fixture: Fixture): SemanticPointEvidenceTargetRequest =
    SemanticPointEvidenceTargetRequest(fixture.root, fixture.source, 3, 7, project)

  private def sharedFixture(label: String, jvmIdentity: String = sourceText): Fixture =
    val root = Files.createTempDirectory(label)
    val source = root.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, sourceText)
    val jvmRoot = Files.createDirectories(root.resolve("kernel/jvm/meta"))
    val jsRoot = Files.createDirectories(root.resolve("kernel/js/meta"))
    val classes = Files.createDirectories(root.resolve("kernel/jvm/classes"))
    val jvmArtifact = jvmRoot.resolve(relative + ".semanticdb")
    val jsArtifact = jsRoot.resolve(relative + ".semanticdb")
    writeArtifact(jvmArtifact, jvmIdentity)
    writeArtifact(jsArtifact, sourceText)
    val receipt = SbtTargetContextReceipt(
      project,
      SbtClasspathConfiguration.Compile,
      classes,
      jvmRoot,
      List(SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory)),
      "3.3.7",
      Some("a" * 64)
    )
    Fixture(root, source, jvmRoot, jvmArtifact, receipt)

  private def writeArtifact(path: Path, identity: String): Unit =
    Files.createDirectories(path.getParent)
    val occurrence = SymbolOccurrence(Some(Range(2, 2, 2, 12)), symbol, SymbolOccurrence.Role.REFERENCE)
    val document = TextDocument(uri = relative, md5 = md5(identity), occurrences = Seq(occurrence))
    Files.write(path, TextDocuments(documents = Seq(document)).toByteArray)

  private def writeUnverifiableArtifact(path: Path): Unit =
    val occurrence = SymbolOccurrence(Some(Range(2, 2, 2, 12)), symbol, SymbolOccurrence.Role.REFERENCE)
    Files.write(path, TextDocuments(documents = Seq(
      TextDocument(uri = relative, occurrences = Seq(occurrence))
    )).toByteArray)

  private def relativeArtifact(root: String): String = s"$root/$relative.semanticdb"

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString

  private def project: SbtProjectId = SbtProjectId.parse("kernelJVM").fold(fail(_), identity)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

  private final case class FixedAcquirer(
      result: Either[SbtTargetContextFailure, SbtTargetContextReceipt]
  ) extends SbtTargetContextAcquirer:
    override def acquire(request: SbtTargetContextRequest) = result

  private final case class Fixture(
      root: Path,
      source: Path,
      jvmRoot: Path,
      jvmArtifact: Path,
      receipt: SbtTargetContextReceipt
  )
