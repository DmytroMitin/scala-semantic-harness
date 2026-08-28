package semantic.harness.semanticdb_reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class FreshnessAssessorSuite extends munit.FunSuite:
  private val relative = "src/main/scala/example/Main.scala"
  private val sourceText = "package example\nobject Main\n"

  test("valid lowercase or uppercase SemanticDB md5 establishes Fresh"):
    val lower = md5(sourceText)
    List(lower, lower.toUpperCase).foreach { digest =>
      val (source, artifact) = snapshots(TextDocument(uri = relative, md5 = digest))
      val assessment = FreshnessAssessor.assess(source, artifact, relative)

      assessment.freshness match
        case SourceArtifactFreshness.Fresh(evidence) =>
          assertEquals(evidence.basis, FreshnessBasis.SemanticdbMd5Utf8)
          assertEquals(evidence.documentIndex, Some(0))
          assertEquals(evidence.documentUri, Some(relative))
          assertEquals(evidence.semanticdbMd5, Some(lower))
        case other => fail(s"expected Fresh, got $other")
    }

  test("a usable digest mismatch establishes Stale"):
    val (source, artifact) = snapshots(TextDocument(uri = relative, md5 = md5("older source")))

    FreshnessAssessor.assess(source, artifact, relative).freshness match
      case SourceArtifactFreshness.Stale(evidence) =>
        assertEquals(evidence.basis, FreshnessBasis.SemanticdbMd5Utf8)
      case other => fail(s"expected Stale, got $other")

  test("non-empty embedded text is the fallback when md5 is absent"):
    val (source, artifact) = snapshots(TextDocument(uri = relative, text = sourceText))

    FreshnessAssessor.assess(source, artifact, relative).freshness match
      case SourceArtifactFreshness.Fresh(evidence) =>
        assertEquals(evidence.basis, FreshnessBasis.EmbeddedTextExact)
      case other => fail(s"expected Fresh, got $other")

  test("missing identity is Unverifiable rather than inferred from mtime"):
    val (source, artifact) = snapshots(TextDocument(uri = relative))
    Files.setLastModifiedTime(source.path, FileTime.fromMillis(10L))
    Files.setLastModifiedTime(artifact.path, FileTime.fromMillis(20L))
    val earlierSource = FreshnessAssessor.assess(SourceSnapshot.capture(source.path), SemanticdbReader.readSnapshot(artifact.path).fold(fail(_), identity), relative)
    Files.setLastModifiedTime(source.path, FileTime.fromMillis(30L))
    val laterSource = FreshnessAssessor.assess(SourceSnapshot.capture(source.path), SemanticdbReader.readSnapshot(artifact.path).fold(fail(_), identity), relative)

    assertUnverifiable(earlierSource, UnverifiableReason.MissingDocumentIdentity)
    assertUnverifiable(laterSource, UnverifiableReason.MissingDocumentIdentity)

  test("malformed digest is a typed Unverifiable branch"):
    val (source, artifact) = snapshots(TextDocument(uri = relative, md5 = "not-md5"))
    assertUnverifiable(FreshnessAssessor.assess(source, artifact, relative), UnverifiableReason.MalformedDocumentDigest)

  test("md5 and embedded text must be internally consistent"):
    val (source, artifact) = snapshots(TextDocument(uri = relative, md5 = md5(sourceText), text = "different embedded text"))
    assertUnverifiable(FreshnessAssessor.assess(source, artifact, relative), UnverifiableReason.InconsistentDocumentIdentity)

  test("all-document mapping selects the unique matching document"):
    val (source, artifact) = snapshots(Seq(
      TextDocument(uri = "src/main/scala/example/Other.scala", md5 = md5("other")),
      TextDocument(uri = relative, md5 = md5(sourceText))
    ))

    val result = FreshnessAssessor.assess(source, artifact, relative)

    assertEquals(result.document.map(_.index), Some(1))
    result.freshness match
      case SourceArtifactFreshness.Fresh(evidence) => assertEquals(evidence.documentIndex, Some(1))
      case other => fail(s"expected Fresh, got $other")

  test("zero and multiple mapped documents have distinct typed reasons"):
    val (source, none) = snapshots(TextDocument(uri = "src/main/scala/example/Other.scala", md5 = md5(sourceText)))
    assertUnverifiable(FreshnessAssessor.assess(source, none, relative), UnverifiableReason.NoUniqueDocumentForSource)

    val (_, many) = snapshots(Seq(
      TextDocument(uri = relative, md5 = md5(sourceText)),
      TextDocument(uri = relative, md5 = md5(sourceText))
    ))
    assertUnverifiable(FreshnessAssessor.assess(source, many, relative), UnverifiableReason.AmbiguousDocumentIdentity)

  test("invalid source encoding remains typed Unverifiable even with usable document identity"):
    val sourcePath = Files.createTempFile("freshness-invalid-source", ".scala")
    Files.write(sourcePath, Array(0xc3.toByte, 0x28.toByte))
    val artifactPath = Files.createTempFile("freshness-invalid-source", ".semanticdb")
    writeArtifact(artifactPath, Seq(TextDocument(uri = relative, md5 = md5(sourceText))))

    val result = FreshnessAssessor.assess(
      SourceSnapshot.capture(sourcePath),
      SemanticdbReader.readSnapshot(artifactPath).fold(fail(_), identity),
      relative
    )

    assertUnverifiable(result, UnverifiableReason.InvalidSourceEncoding)

  private def assertUnverifiable(result: FreshnessAssessment, reason: UnverifiableReason): Unit =
    result.freshness match
      case SourceArtifactFreshness.Unverifiable(actual, evidence) =>
        assertEquals(actual, reason)
        assertEquals(evidence.basis, FreshnessBasis.None)
      case other => fail(s"expected Unverifiable($reason), got $other")

  private def snapshots(document: TextDocument): (SourceSnapshot, ArtifactSnapshot) = snapshots(Seq(document))

  private def snapshots(documents: Seq[TextDocument]): (SourceSnapshot, ArtifactSnapshot) =
    val sourcePath = Files.createTempFile("freshness-source", ".scala")
    Files.writeString(sourcePath, sourceText, StandardCharsets.UTF_8)
    val artifactPath = Files.createTempFile("freshness-artifact", ".semanticdb")
    writeArtifact(artifactPath, documents)
    SourceSnapshot.capture(sourcePath) -> SemanticdbReader.readSnapshot(artifactPath).fold(fail(_), identity)

  private def writeArtifact(path: Path, documents: Seq[TextDocument]): Unit =
    Files.write(path, TextDocuments(documents = documents).toByteArray)

  private def md5(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
