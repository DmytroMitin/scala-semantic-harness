package semantic.harness.semanticdb_reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class SemanticdbForSourceV2Suite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val sourceText = "package example\nobject Main\n"

  test("v2 unique candidate exposes all-document mapping and Fresh provenance"):
    val fixture = workspace("semanticdb-for-source-v2-fresh")
    writeArtifact(fixture.artifact, Seq(
      TextDocument(uri = "src/main/scala/example/Other.scala", md5 = md5("other")),
      TextDocument(uri = relative, md5 = md5(sourceText))
    ))

    val report = SemanticdbForSource.inspectV2(fixture.root, fixture.source).fold(fail(_), identity)
    val candidate = report.matches.head

    assertEquals(report.schemaVersion, SemanticdbForSourceReportV2.SchemaVersion)
    assertEquals(report.status, SemanticdbForSource.StatusUniqueMatch)
    assertEquals(candidate.documentCount, Some(2))
    assertEquals(candidate.documentUri, Some(relative))
    assertEquals(candidate.documentIndex, Some(1))
    assert(candidate.artifactSnapshotSha256.exists(_.matches("[0-9a-f]{64}")))
    assert(candidate.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Fresh]))

  test("v2 keeps a unique stale candidate visible and annotated"):
    val fixture = workspace("semanticdb-for-source-v2-stale")
    writeArtifact(fixture.artifact, Seq(TextDocument(uri = relative, md5 = md5("older"))))

    val report = SemanticdbForSource.inspectV2(fixture.root, fixture.source).fold(fail(_), identity)

    assertEquals(report.status, SemanticdbForSource.StatusUniqueMatch)
    assert(report.matches.head.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Stale]))

  test("v2 exposes missing identity as a unique Unverifiable candidate"):
    val fixture = workspace("semanticdb-for-source-v2-unverifiable")
    writeArtifact(fixture.artifact, Seq(TextDocument(uri = relative)))

    val candidate = SemanticdbForSource.inspectV2(fixture.root, fixture.source).fold(fail(_), identity).matches.head

    candidate.freshness match
      case Some(SourceArtifactFreshness.Unverifiable(reason, _)) =>
        assertEquals(reason, UnverifiableReason.MissingDocumentIdentity)
      case other => fail(s"expected Unverifiable candidate, got $other")

  test("mixed-freshness artifact candidates remain ambiguous before eligibility filtering"):
    val fixture = workspace("semanticdb-for-source-v2-ambiguous")
    writeArtifact(fixture.artifact, Seq(TextDocument(uri = relative, md5 = md5(sourceText))))
    writeArtifact(fixture.root.resolve("z/Main.scala.semanticdb"), Seq(TextDocument(uri = relative, md5 = md5("older"))))

    val report = SemanticdbForSource.inspectV2(fixture.root, fixture.source).fold(fail(_), identity)

    assertEquals(report.status, SemanticdbForSource.StatusAmbiguous)
    assertEquals(report.matches.size, 2)
    assert(report.matches.exists(_.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Fresh])))
    assert(report.matches.exists(_.freshness.exists(_.isInstanceOf[SourceArtifactFreshness.Stale])))

  test("v2 preserves no-match and unparseable path evidence"):
    val noMatch = workspace("semanticdb-for-source-v2-no-match")
    writeArtifact(noMatch.artifact, Seq(TextDocument(uri = "src/main/scala/example/Other.scala", md5 = md5("other"))))
    val noMatchReport = SemanticdbForSource.inspectV2(noMatch.root, noMatch.source).fold(fail(_), identity)
    assertEquals(noMatchReport.status, SemanticdbForSource.StatusNoMatch)

    val partial = workspace("semanticdb-for-source-v2-partial", artifactRelative = s"target/META-INF/semanticdb/$relative.semanticdb")
    Files.createDirectories(partial.artifact.getParent)
    Files.writeString(partial.artifact, "not semanticdb")
    val partialReport = SemanticdbForSource.inspectV2(partial.root, partial.source).fold(fail(_), identity)
    assertEquals(partialReport.status, SemanticdbForSource.StatusPartial)
    assertEquals(partialReport.matches.head.parseStatus, "Unparseable")
    assertEquals(partialReport.matches.head.freshness, None)

  private def workspace(label: String, artifactRelative: String = "target/Main.scala.semanticdb"): Fixture =
    val root = Files.createTempDirectory(label)
    val source = root.resolve(relative)
    Files.createDirectories(source.getParent)
    Files.writeString(source, sourceText, StandardCharsets.UTF_8)
    Fixture(root, source, root.resolve(artifactRelative))

  private def writeArtifact(path: Path, documents: Seq[TextDocument]): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, TextDocuments(documents = documents).toByteArray)

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)).map(byte => f"${byte & 0xff}%02x").mkString

  private final case class Fixture(root: Path, source: Path, artifact: Path)
