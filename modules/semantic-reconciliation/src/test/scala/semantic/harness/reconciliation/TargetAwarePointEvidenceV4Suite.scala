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
import semantic.harness.presentation.PresentationCompilerClasspath
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathEntry
import semantic.harness.sbt_runner.SbtClasspathEntryKind
import semantic.harness.sbt_runner.SbtPointContextAcquirer
import semantic.harness.sbt_runner.SbtPointContextFailure
import semantic.harness.sbt_runner.SbtPointContextReceipt
import semantic.harness.sbt_runner.SbtPointContextRequest
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtScalaVersion

class TargetAwarePointEvidenceV4Suite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val sourceText = "package example\nobject Main:\n  val answer = 42\n"
  private val symbol = "example/Main.answer."

  test("prepared Fresh target uses own existing output once plus external dependencies and stays partial"):
    val fixture = sharedFixture("target-v4-prepared", classDirectoryPresent = true)
    var contexts = List.empty[PresentationCompilerContext]
    val service = serviceFor(fixture, context =>
      contexts = contexts :+ context
      resolved(fixture.source)
    )
    try
      val report = service.inspect(pointRequest(fixture)).fold(fail(_), identity)

      assertEquals(report.schemaVersion, PointEvidenceReportV4.SchemaVersion)
      assertEquals(report.targetSelection.status, PointTargetSelectionStatusV4.SelectedTargetOwnedFresh)
      assertEquals(report.targetContext.acquisitionProfile, PointContextAcquisitionProfileV4.PartialExistingOutputPointContext)
      assertEquals(report.targetContext.acquisitionEffect, PointContextAcquisitionEffectV4.TargetSourceOutputsNotRequested)
      assertEquals(report.targetContext.buildPerformed, TargetBuildPerformedV4.NotRequested)
      assertEquals(report.targetContext.classpathBasis, PointClasspathBasisV4.ExistingSelectedClassDirectoryPlusExternalDependencies)
      assertEquals(report.targetContext.contextCompleteness, PointContextCompletenessV4.PartialExistingOutputs)
      assertEquals(report.targetContext.selectedClassDirectoryStatus, SelectedClassDirectoryStatusV4.PresentIncluded)
      assertEquals(report.targetContext.externalDependencyEntryCount, Some(1))
      assertEquals(report.targetContext.presentationCompilerContextEntryCount, Some(2))
      assertEquals(contexts.size, 1)
      assertEquals(contextEntries(contexts.head).count(_ == fixture.classes), 1)
      assert(!contextEntries(contexts.head).contains(fixture.jsClasses))
      assertEquals(report.livePoint.status, PointLiveStatus.Resolved)
      assert(report.reconciliation.outcome.isInstanceOf[ReconciliationOutcomeV2.CompletedFresh])
    finally deleteRecursively(fixture.root)

  test("missing own output runs external-only without fallback and reports truthful Unresolved partial context"):
    val fixture = sharedFixture("target-v4-missing", classDirectoryPresent = false)
    var contexts = List.empty[PresentationCompilerContext]
    val service = serviceFor(fixture, context =>
      contexts = contexts :+ context
      Right(SymbolAtResult(
        symbol = None,
        displayName = None,
        range = None,
        source = fixture.source.toString
      ))
    )
    try
      val report = service.inspect(pointRequest(fixture)).fold(fail(_), identity)

      assertEquals(report.targetContext.selectedClassDirectoryStatus, SelectedClassDirectoryStatusV4.AbsentNotIncluded)
      assertEquals(report.targetContext.presentationCompilerContextEntryCount, Some(1))
      assertEquals(report.targetContext.contextCompleteness, PointContextCompletenessV4.PartialExistingOutputs)
      assertEquals(contexts.flatMap(contextEntries), List(fixture.dependency))
      assertEquals(report.livePoint.status, PointLiveStatus.Unresolved)
      assert(!Files.exists(fixture.classes))
    finally deleteRecursively(fixture.root)

  test("Fresh qualified Unverifiable stale multiple absent and unsafe ownership stay typed"):
    val unverifiable = sharedFixture("target-v4-unverifiable", classDirectoryPresent = true)
    writeUnverifiableArtifact(unverifiable.jvmArtifact)
    try
      assertEquals(inspect(unverifiable).targetSelection.status, PointTargetSelectionStatusV4.SelectedTargetOwnedQualifiedUnverifiable)
    finally deleteRecursively(unverifiable.root)

    val stale = sharedFixture("target-v4-stale", classDirectoryPresent = true, jvmIdentity = "older")
    try
      assertEquals(inspect(stale).targetSelection.status, PointTargetSelectionStatusV4.StaleTargetOwnedCandidate)
    finally deleteRecursively(stale.root)

    val multiple = sharedFixture("target-v4-multiple", classDirectoryPresent = true)
    writeArtifact(multiple.jvmRoot.resolve("duplicate.semanticdb"), sourceText)
    try
      assertEquals(inspect(multiple).targetSelection.status, PointTargetSelectionStatusV4.MultipleCandidatesOwnedByTarget)
    finally deleteRecursively(multiple.root)

    val absent = sharedFixture("target-v4-absent", classDirectoryPresent = true)
    val otherRoot = Files.createDirectories(absent.root.resolve("other/meta"))
    try
      assertEquals(inspect(absent, absent.receipt.copy(semanticdbTargetRoot = otherRoot)).targetSelection.status, PointTargetSelectionStatusV4.NoCandidateOwnedByTarget)
    finally deleteRecursively(absent.root)

    val unsafe = sharedFixture("target-v4-unsafe", classDirectoryPresent = true)
    val outside = Files.createTempDirectory("target-v4-outside-")
    try
      assertEquals(inspect(unsafe, unsafe.receipt.copy(semanticdbTargetRoot = outside)).targetSelection.status, PointTargetSelectionStatusV4.UnsafeOrUnverifiableOutputRoots)
    finally
      deleteRecursively(unsafe.root)
      deleteRecursively(outside)

  test("acquisition switch axis unknown and process failures are closed and never fall back"):
    val fixture = sharedFixture("target-v4-failure", classDirectoryPresent = true)
    try
      val failures = List(
        SbtPointContextFailure.ScalaSwitch("switch") -> PointTargetSelectionStatusV4.ScalaSwitchFailed,
        SbtPointContextFailure.ScalaVersionMismatch("axis") -> PointTargetSelectionStatusV4.ScalaAxisMismatch,
        SbtPointContextFailure.UnknownProject("unknown") -> PointTargetSelectionStatusV4.UnknownProject,
        SbtPointContextFailure.Process("process") -> PointTargetSelectionStatusV4.TargetContextAcquisitionFailed
      )
      failures.foreach { case (failure, expected) =>
        val report = PointEvidenceServiceV4.withDependencies(
          FixedAcquirer(Left(failure)),
          (_, _, _, _, _) => fail("live query must not run without a safe acquired context"),
          () => ()
        ).inspect(pointRequest(fixture)).fold(fail(_), identity)
        assertEquals(report.targetSelection.status, expected)
      }
    finally deleteRecursively(fixture.root)

  test("source artifact class input and root mutations withhold completed reconciliation"):
    val sourceChanged = sharedFixture("target-v4-source-change", classDirectoryPresent = true)
    try
      val report = serviceFor(sourceChanged, _ => resolved(sourceChanged.source), () =>
        Files.writeString(sourceChanged.source, sourceText + "// changed\n")
      ).inspect(pointRequest(sourceChanged)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, PointTargetSelectionStatusV4.SourceChangedDuringRequest)
      assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest))
    finally deleteRecursively(sourceChanged.root)

    val artifactChanged = sharedFixture("target-v4-artifact-change", classDirectoryPresent = true)
    try
      val report = serviceFor(artifactChanged, _ => resolved(artifactChanged.source), () =>
        Files.writeString(artifactChanged.jvmArtifact, "changed")
      ).inspect(pointRequest(artifactChanged)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, PointTargetSelectionStatusV4.SelectedArtifactChangedOrRemapped)
      assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SelectedArtifactChangedOrRemapped))
    finally deleteRecursively(artifactChanged.root)

    val inputChanged = sharedFixture("target-v4-input-change", classDirectoryPresent = true)
    try
      val report = serviceFor(inputChanged, _ => resolved(inputChanged.source), () =>
        Files.writeString(inputChanged.classes.resolve("new.class"), "changed")
      ).inspect(pointRequest(inputChanged)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, PointTargetSelectionStatusV4.PointContextInputsChangedDuringRequest)
      assertEquals(report.reconciliation.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.ArtifactEvidenceUnavailable))
    finally deleteRecursively(inputChanged.root)

    val rootChanged = sharedFixture("target-v4-root-change", classDirectoryPresent = true)
    try
      val report = serviceFor(rootChanged, _ => resolved(rootChanged.source), () =>
        Files.move(rootChanged.jvmRoot, rootChanged.root.resolve("old-jvm-meta"))
        Files.createDirectory(rootChanged.jvmRoot)
      ).inspect(pointRequest(rootChanged)).fold(fail(_), identity)
      assertEquals(report.targetSelection.status, PointTargetSelectionStatusV4.TargetRootsOrAxisChangedDuringRequest)
    finally deleteRecursively(rootChanged.root)

  test("receipt/request axis mismatch is rejected even for a synthetic acquirer"):
    val fixture = sharedFixture("target-v4-axis-mismatch", classDirectoryPresent = true)
    try
      val mismatched = fixture.receipt.copy(effectiveScalaVersion = scalaVersion("3.3.8"))
      assertEquals(inspect(fixture, mismatched).targetSelection.status, PointTargetSelectionStatusV4.ScalaAxisMismatch)
    finally deleteRecursively(fixture.root)

  private def inspect(fixture: Fixture, receipt: SbtPointContextReceipt = null): PointEvidenceReportV4 =
    val selectedReceipt = Option(receipt).getOrElse(fixture.receipt)
    serviceFor(fixture, _ => resolved(fixture.source), receipt = selectedReceipt)
      .inspect(pointRequest(fixture)).fold(fail(_), identity)

  private def serviceFor(
      fixture: Fixture,
      query: PresentationCompilerContext => Either[String, SymbolAtResult],
      hook: () => Unit = () => (),
      receipt: SbtPointContextReceipt = null
  ): PointEvidenceServiceV4 =
    val selectedReceipt = Option(receipt).getOrElse(fixture.receipt)
    PointEvidenceServiceV4.withDependencies(
      FixedAcquirer(Right(selectedReceipt)),
      (_, _, _, _, context) => query(context),
      hook
    )

  private def resolved(path: Path): Either[String, SymbolAtResult] = Right(SymbolAtResult(
    symbol = Some(symbol),
    displayName = Some("answer"),
    range = Some(SourceRange(2, 2, 2, 12)),
    source = path.toString
  ))

  private def contextEntries(context: PresentationCompilerContext): List[Path] =
    context.classpath match
      case PresentationCompilerClasspath.Explicit(entries) => entries
      case PresentationCompilerClasspath.NarrowRuntime => Nil

  private def pointRequest(fixture: Fixture): SemanticPointEvidenceTargetRequestV4 =
    SemanticPointEvidenceTargetRequestV4(
      fixture.root,
      fixture.source,
      3,
      7,
      project,
      Some(scalaVersion("3.3.7"))
    )

  private def sharedFixture(
      label: String,
      classDirectoryPresent: Boolean,
      jvmIdentity: String = sourceText
  ): Fixture =
    val root = Files.createTempDirectory(label)
    val source = root.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, sourceText)
    val jvmRoot = Files.createDirectories(root.resolve("kernel/jvm/meta"))
    val jsRoot = Files.createDirectories(root.resolve("kernel/js/meta"))
    val classes = root.resolve("kernel/jvm/classes")
    if classDirectoryPresent then Files.createDirectories(classes)
    val jsClasses = Files.createDirectories(root.resolve("kernel/js/classes"))
    val dependency = Files.createFile(root.resolve("dependency.jar"))
    val jvmArtifact = jvmRoot.resolve(relative + ".semanticdb")
    writeArtifact(jvmArtifact, jvmIdentity)
    writeArtifact(jsRoot.resolve(relative + ".semanticdb"), sourceText)
    val axis = scalaVersion("3.3.7")
    val receipt = SbtPointContextReceipt(
      project,
      SbtClasspathConfiguration.Compile,
      Some(axis),
      axis,
      classes,
      jvmRoot,
      classDirectoryPresent,
      List(SbtClasspathEntry(dependency, SbtClasspathEntryKind.Jar)),
      Some("a" * 64)
    )
    Fixture(root, source, jvmRoot, jvmArtifact, classes, jsClasses, dependency, receipt)

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

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString

  private def project: SbtProjectId = SbtProjectId.parse("kernelJVM").fold(fail(_), identity)

  private def scalaVersion(value: String): SbtScalaVersion =
    SbtScalaVersion.parse(value).fold(fail(_), identity)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

  private final case class FixedAcquirer(
      result: Either[SbtPointContextFailure, SbtPointContextReceipt]
  ) extends SbtPointContextAcquirer:
    override def acquire(request: SbtPointContextRequest) = result

  private final case class Fixture(
      root: Path,
      source: Path,
      jvmRoot: Path,
      jvmArtifact: Path,
      classes: Path,
      jsClasses: Path,
      dependency: Path,
      receipt: SbtPointContextReceipt
  )
