package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import semantic.harness.presentation.InferTypeContextKind
import semantic.harness.presentation.InferTypeRequest
import semantic.harness.presentation.InferTypeStatus
import semantic.harness.presentation.PresentationCompilerContext
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtPointContextAcquirer
import semantic.harness.sbt_runner.SbtPointContextFailure
import semantic.harness.sbt_runner.SbtPointContextReceipt
import semantic.harness.sbt_runner.SbtPointContextRequest
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtScalaVersion
import semantic.harness.semanticdb_reader.SourceArtifactFreshness

class Scala39LiveSemanticsSuite extends munit.FunSuite:
  test("linked Presentation Compiler resolves a declaration after the stable into modifier"):
    withFixture { fixture =>
      val result = PresentationCompilerService().symbolAt(fixture.source, line = 8, column = 7)

      assertEquals(result.flatMap(_.symbol.toRight("no symbol")), Right(symbol))
    }

  test("target-classpath infer-type renders String after the stable into modifier"):
    withFixture { fixture =>
      val result = PresentationCompilerService().inferType(InferTypeRequest(
        fixture.source,
        line = 8,
        column = 7,
        PresentationCompilerContext.explicit(List(fixture.classes), Some(fixture.root))
      )).fold(fail(_), identity)

      assertEquals(result.status, InferTypeStatus.Resolved)
      assertEquals(result.rendering, Some("val rendered: String"))
      assertEquals(result.contextKind, InferTypeContextKind.ExplicitClasspath)
    }

  test("target-aware point evidence keeps Scala 3.9 live and static authority distinct"):
    withFixture { fixture =>
      val compiler = PresentationCompilerService()
      val report = PointEvidenceServiceV4.withDependencies(
        FixedAcquirer(Right(fixture.receipt)),
        (path, source, line, column, context) =>
          compiler.symbolAtSnapshot(path, source, line, column, context),
        () => ()
      ).inspect(SemanticPointEvidenceTargetRequestV4(
        fixture.root,
        fixture.source,
        line = 8,
        column = 7,
        fixture.project,
        requestedScalaVersion = Some(scala39)
      )).fold(fail(_), identity)

      assertEquals(report.targetContext.requestedScalaVersion, Some("3.9.0"))
      assertEquals(report.targetContext.effectiveScalaVersion, Some("3.9.0"))
      assertEquals(report.targetContext.buildPerformed, TargetBuildPerformedV4.NotRequested)
      assertEquals(report.livePoint.status, PointLiveStatus.Resolved)
      assertEquals(report.livePoint.result.flatMap(_.symbol), Some(symbol))
      assertEquals(report.targetSelection.status, PointTargetSelectionStatusV4.SelectedTargetOwnedFresh)
      assert(report.targetSelection.selectedArtifact.flatMap(_.freshness).exists {
        case SourceArtifactFreshness.Fresh(_) => true
        case _ => false
      })
      assert(report.reconciliation.outcome.isInstanceOf[ReconciliationOutcomeV2.CompletedFresh])
    }

  private def withFixture(test: Fixture => Unit): Unit =
    val fixture = Fixture.create()
    try test(fixture)
    finally fixture.close()

  private def scala39: SbtScalaVersion =
    SbtScalaVersion.parse("3.9.0").fold(fail(_), identity)

  private val relative = "src/main/scala/scala39fixture/Scala39Specific.scala"
  private val symbol = "scala39fixture/Scala39Specific.rendered."
  private val sourceText =
    """package scala39fixture
      |
      |into final case class Slug(value: String)
      |
      |object Scala39Specific:
      |  given Conversion[String, Slug] = Slug(_)
      |  def render(slug: Slug): String = slug.value
      |  val rendered: String = render("stable-into")
      |""".stripMargin

  private final case class FixedAcquirer(
      result: Either[SbtPointContextFailure, SbtPointContextReceipt]
  ) extends SbtPointContextAcquirer:
    override def acquire(request: SbtPointContextRequest) = result

  private final case class Fixture(
      root: Path,
      source: Path,
      classes: Path,
      project: SbtProjectId,
      receipt: SbtPointContextReceipt
  ):
    def close(): Unit =
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()

  private object Fixture:
    def create(): Fixture =
      val root = Files.createTempDirectory("scala39-live-semantics-")
      val source = root.resolve(relative)
      Files.createDirectories(source.getParent)
      Files.writeString(source, sourceText)

      val classes = Files.createDirectories(root.resolve("target/scala-3.9.0/classes"))
      val semanticdbRoot = Files.createDirectories(root.resolve("target/scala-3.9.0/meta"))
      val artifact = semanticdbRoot.resolve(relative + ".semanticdb")
      Files.createDirectories(artifact.getParent)
      val occurrence = SymbolOccurrence(
        Some(Range(7, 6, 7, 14)),
        symbol,
        SymbolOccurrence.Role.DEFINITION
      )
      val document = TextDocument(
        uri = relative,
        md5 = md5(sourceText),
        occurrences = Seq(occurrence)
      )
      Files.write(artifact, TextDocuments(documents = Seq(document)).toByteArray)

      val project = SbtProjectId.parse("app").fold(message => throw new IllegalArgumentException(message), identity)
      val receipt = SbtPointContextReceipt(
        project,
        SbtClasspathConfiguration.Compile,
        requestedScalaVersion = Some(scala39),
        effectiveScalaVersion = scala39,
        classDirectory = classes,
        semanticdbTargetRoot = semanticdbRoot,
        classDirectoryPresent = true,
        externalDependencyClasspath = Nil,
        targetJavaContext = Some("JDK_21")
      )
      Fixture(root, source, classes, project, receipt)

    private def md5(value: String): String =
      MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))
        .map(byte => f"${byte & 0xff}%02x").mkString
