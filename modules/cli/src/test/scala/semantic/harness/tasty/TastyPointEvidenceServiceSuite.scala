package semantic.harness.tasty

import java.nio.file.{Files, Path}
import semantic.harness.sbt_runner.*

class TastyPointEvidenceServiceSuite extends munit.FunSuite:
  test("compile failure is a JSON-domain result and never inspects old artifacts"):
    val fixture = Fixture.create()
    val inspector = RecordingInspector(Right(fixture.inspection(Nil)))
    try
      val service = TastyPointEvidenceService(FixedAcquirer(fixture.receipt(SbtTastyCompileStatus.Failed)), inspector)
      val result = service.inspect(fixture.input)
      assertEquals(result.map(_.status), Right(TastyPointEvidenceStatus.CompileFailed))
      assertEquals(result.map(_.compile.status), Right(TastyCompileStatus.Failed))
      assertEquals(inspector.calls, 0)
    finally fixture.close()

  test("resolved evidence deterministically selects the smallest typed tree and exact artifact digest"):
    val fixture = Fixture.create()
    try
      val larger = fixture.tree(0, 5, 30, "Ident")
      val smaller = fixture.tree(1, 9, 14, "Select")
      val inspector = RecordingInspector(Right(fixture.inspection(List(larger, smaller))))
      val service = TastyPointEvidenceService(FixedAcquirer(fixture.receipt()), inspector)
      val report = service.inspect(fixture.input).toOption.get
      assertEquals(report.status, TastyPointEvidenceStatus.Resolved)
      assertEquals(report.selectedTree.map(_.kind), Some("Select"))
      assertEquals(report.artifactEvidence.selectedArtifact.map(_.sha256.length), Some(64))
      assertEquals(report.freshness.disposition, TastyFreshnessDisposition.SameRequestSourceStable)
      assertEquals(inspector.lastDependencyClasspath, fixture.receipt().dependencyClasspath)
    finally fixture.close()

  test("source changed by the authoritative build fails closed before inspection"):
    val fixture = Fixture.create()
    val mutating = new SbtTastyCompileReceiptAcquirer:
      def acquire(request: SbtTastyCompileRequest) =
        Files.writeString(fixture.source, "object Changed\n")
        Right(fixture.receipt())
    val inspector = RecordingInspector(Right(fixture.inspection(Nil)))
    try
      val report = TastyPointEvidenceService(mutating, inspector).inspect(fixture.input).toOption.get
      assertEquals(report.status, TastyPointEvidenceStatus.SourceChangedDuringRequest)
      assertEquals(inspector.calls, 0)
    finally fixture.close()

  private final case class FixedAcquirer(receiptValue: SbtTastyCompileReceipt)
      extends SbtTastyCompileReceiptAcquirer:
    def acquire(request: SbtTastyCompileRequest) = Right(receiptValue)

  private final case class RecordingInspector(result: Either[ExactTastyInspectorFailure, ExactTastyInspection])
      extends ExactTastyInspector:
    var calls = 0
    var lastDependencyClasspath = List.empty[Path]
    def inspect(
        targetScalaVersion: String,
        workspace: Path,
        source: Path,
        line: Int,
        column: Int,
        candidates: List[TastyArtifactCandidate],
        dependencyClasspath: List[Path]
    ) =
      calls += 1
      lastDependencyClasspath = dependencyClasspath
      result

  private final case class Fixture(
      workspace: Path,
      source: Path,
      classes: Path,
      candidates: List[TastyArtifactCandidate],
      project: SbtProjectId
  ):
    val input = TastyPointEvidenceInput(workspace, "Example.scala", 1, 8, project, None)

    def receipt(status: SbtTastyCompileStatus = SbtTastyCompileStatus.Succeeded) =
      SbtTastyCompileReceipt(
        project,
        SbtClasspathConfiguration.Compile,
        status,
        Some("3.3.3"),
        Some(classes),
        sourceIncluded = true,
        targetJavaContext = None,
        dependencyClasspath = List(classes)
      )

    def tree(index: Int, start: Int, end: Int, kind: String) =
      ExactTastyTreeEvidence(
        candidates(index),
        TastySelectedTree(kind, TastySourceRange(1, start + 1, 1, end + 1), Some("example.value"), Some("value"), None, Some("String")),
        start,
        end
      )

    def inspection(trees: List[ExactTastyTreeEvidence]) =
      ExactTastyInspection(
        "3.3.3",
        candidates.size,
        trees,
        TastyInspectorProvenance(
          "semantic-scala.internal-tasty-worker-output.v1",
          "ExactScalaTastyInspectorChild",
          "3.3.3",
          "a" * 64,
          "b" * 64,
          false,
          false
        )
      )

    def close(): Unit =
      val paths = Files.walk(workspace)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()

  private object Fixture:
    def create(): Fixture =
      val workspace = Files.createTempDirectory("tasty-service-")
      val source = workspace.resolve("Example.scala")
      val classes = Files.createDirectories(workspace.resolve("target/classes"))
      Files.writeString(source, "object Example\n")
      Files.write(classes.resolve("A.tasty"), Array[Byte](1))
      Files.write(classes.resolve("B.tasty"), Array[Byte](2))
      val project = SbtProjectId.parse("app").toOption.get
      val candidates = TastyArtifactInventory.inspect(workspace, classes).toOption.get
      Fixture(workspace, source, classes, candidates, project)
