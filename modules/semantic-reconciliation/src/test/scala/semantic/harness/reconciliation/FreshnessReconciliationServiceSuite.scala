package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SourceArtifactFreshness
import semantic.harness.semanticdb_reader.UnverifiableReason

class FreshnessReconciliationServiceSuite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val sourceText = "package example\nobject Main:\n  val answer = 42\n"
  private val staticSymbol = "example/Main.answer."

  test("Fresh explicit artifact completes only as CompletedFresh"):
    val fixture = createFixture("reconcile-v2-fresh", md5 = Some(md5(sourceText)))

    val report = service(live(fixture.source, Some(staticSymbol))).reconcile(
      fixture.source, line = 3, column = 7, fixture.artifact
    ).fold(fail(_), identity)

    assertEquals(report.schemaVersion, ReconciliationResultV2.SchemaVersion)
    assert(report.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Fresh]))
    report.outcome match
      case ReconciliationOutcomeV2.CompletedFresh(result) =>
        assertEquals(result.status, ReconciliationStatus.ExactMatch)
      case other => fail(s"expected CompletedFresh, got $other")

  test("explicit stale artifact is a successful typed NotAttempted result"):
    val fixture = createFixture("reconcile-v2-stale", md5 = Some(md5("older")))

    val report = service(live(fixture.source, Some(staticSymbol))).reconcile(
      fixture.source, line = 3, column = 7, fixture.artifact
    ).fold(fail(_), identity)

    assert(report.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Stale]))
    assertEquals(report.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.StaleArtifact))

  test("missing producer identity can complete only as structurally qualified Unverifiable"):
    val fixture = createFixture("reconcile-v2-unverifiable", md5 = None)

    val report = service(live(fixture.source, Some(staticSymbol))).reconcile(
      fixture.source, line = 3, column = 7, fixture.artifact
    ).fold(fail(_), identity)

    report.outcome match
      case ReconciliationOutcomeV2.CompletedQualifiedUnverifiable(result, reason) =>
        assertEquals(reason, UnverifiableReason.MissingDocumentIdentity)
        assertEquals(result.status, ReconciliationStatus.ExactMatch)
      case other => fail(s"expected qualified completion, got $other")

  test("ordinary RangeMatchOnly and NoMatch remain nested facts under eligible branches"):
    val rangeFixture = createFixture("reconcile-v2-range", md5 = Some(md5(sourceText)))
    val range = service(live(rangeFixture.source, None)).reconcile(
      rangeFixture.source, line = 3, column = 7, rangeFixture.artifact
    ).fold(fail(_), identity)
    range.outcome match
      case ReconciliationOutcomeV2.CompletedFresh(result) => assertEquals(result.status, ReconciliationStatus.RangeMatchOnly)
      case other => fail(s"expected completed RangeMatchOnly, got $other")

    val noMatch = service(SymbolAtResult(symbol = None, displayName = None, range = None, source = rangeFixture.source.toString)).reconcile(
      rangeFixture.source, line = 3, column = 16, rangeFixture.artifact
    ).fold(fail(_), identity)
    noMatch.outcome match
      case ReconciliationOutcomeV2.CompletedFresh(result) => assertEquals(result.status, ReconciliationStatus.NoMatch)
      case other => fail(s"expected completed NoMatch, got $other")

  test("source mutation after semantic work supersedes and withholds completed reconciliation"):
    val fixture = createFixture("reconcile-v2-source-change", md5 = Some(md5(sourceText)))
    val serviceWithMutation = FreshnessReconciliationService.withPointQueryAndHook(
      (_, _, _, _) => Right(live(fixture.source, Some(staticSymbol))),
      () => Files.writeString(fixture.source, sourceText + "// changed\n")
    )

    val report = serviceWithMutation.reconcile(
      fixture.source, line = 3, column = 7, fixture.artifact
    ).fold(fail(_), identity)

    report.freshness match
      case Some(SourceArtifactFreshness.SourceChangedDuringRequest(before, after, _)) =>
        assertNotEquals(before, after)
      case other => fail(s"expected SourceChangedDuringRequest, got $other")
    assertEquals(report.outcome, ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest))

  private def service(result: SymbolAtResult): FreshnessReconciliationService =
    FreshnessReconciliationService.withPointQuery((_, _, _, _) => Right(result))

  private def createFixture(label: String, md5: Option[String]): Fixture =
    val root = Files.createTempDirectory(label)
    val source = root.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, sourceText, StandardCharsets.UTF_8)
    val artifact = root.resolve("target/Main.scala.semanticdb")
    Files.createDirectories(artifact.getParent)
    val occurrence = SymbolOccurrence(
      range = Some(Range(2, 2, 2, 12)),
      symbol = staticSymbol,
      role = SymbolOccurrence.Role.REFERENCE
    )
    Files.write(artifact, TextDocuments(documents = Seq(TextDocument(
      uri = relative,
      md5 = md5.getOrElse(""),
      occurrences = Seq(occurrence)
    ))).toByteArray)
    Fixture(source, artifact)

  private def live(source: Path, symbol: Option[String]): SymbolAtResult = SymbolAtResult(
    symbol = symbol,
    displayName = symbol.map(_ => "answer"),
    range = Some(SourceRange(2, 2, 2, 12)),
    source = source.toString
  )

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)).map(byte => f"${byte & 0xff}%02x").mkString

  private final case class Fixture(source: Path, artifact: Path)
