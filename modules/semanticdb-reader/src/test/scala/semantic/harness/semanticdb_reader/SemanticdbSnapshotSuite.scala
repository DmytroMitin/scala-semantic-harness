package semantic.harness.semanticdb_reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class SemanticdbSnapshotSuite extends munit.FunSuite:
  test("artifact snapshot retains every SemanticDB document and its identity metadata"):
    val path = Files.createTempFile("semanticdb-snapshot-all-docs", ".semanticdb")
    write(path, Seq(
      TextDocument(uri = "src/main/scala/example/First.scala", md5 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", text = "first"),
      TextDocument(uri = "src/main/scala/example/Second.scala", md5 = "", text = "second")
    ))

    val snapshot = SemanticdbReader.readSnapshot(path).fold(fail(_), identity)

    assertEquals(snapshot.documents.map(_.index), List(0, 1))
    assertEquals(snapshot.documents.map(_.uri), List(
      "src/main/scala/example/First.scala",
      "src/main/scala/example/Second.scala"
    ))
    assertEquals(snapshot.documents.map(_.semanticdbMd5), List(Some("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), None))
    assertEquals(snapshot.documents.map(_.hasEmbeddedText), List(true, true))
    assert(snapshot.sha256.matches("[0-9a-f]{64}"))

  test("artifact snapshot preserves missing md5 and text as missing identity"):
    val path = Files.createTempFile("semanticdb-snapshot-empty-identity", ".semanticdb")
    write(path, Seq(TextDocument(uri = "src/main/scala/example/Main.scala")))

    val document = SemanticdbReader.readSnapshot(path).fold(fail(_), identity).documents.head

    assertEquals(document.semanticdbMd5, None)
    assertEquals(document.hasEmbeddedText, false)

  test("source snapshot uses strict UTF-8, preserves CRLF and stable SHA-256"):
    val path = Files.createTempFile("source-snapshot-utf8", ".scala")
    val bytes = "object Café:\r\n  val empty = \"\"\r\n".getBytes(StandardCharsets.UTF_8)
    Files.write(path, bytes)

    val first = SourceSnapshot.capture(path)
    val second = SourceSnapshot.capture(path)

    assertEquals(first.unverifiableReason, None)
    assertEquals(first.content, Some("object Café:\r\n  val empty = \"\"\r\n"))
    assertEquals(first.sha256, second.sha256)
    assertEquals(first.md5, Some("c1777fb3cb5ab893d73ea74a0f4e95fe"))

  test("source snapshot accepts empty UTF-8 source"):
    val path = Files.createTempFile("source-snapshot-empty", ".scala")
    Files.write(path, Array.emptyByteArray)

    val snapshot = SourceSnapshot.capture(path)

    assertEquals(snapshot.content, Some(""))
    assertEquals(snapshot.sha256, Some("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    assertEquals(snapshot.md5, Some("d41d8cd98f00b204e9800998ecf8427e"))

  test("source snapshot classifies malformed UTF-8 without lossy decoding"):
    val path = Files.createTempFile("source-snapshot-invalid", ".scala")
    Files.write(path, Array(0xc3.toByte, 0x28.toByte))

    val snapshot = SourceSnapshot.capture(path)

    assertEquals(snapshot.content, None)
    assertEquals(snapshot.md5, None)
    assertEquals(snapshot.unverifiableReason, Some(UnverifiableReason.InvalidSourceEncoding))

  test("source snapshot conservatively classifies a UTF-8 BOM"):
    val path = Files.createTempFile("source-snapshot-bom", ".scala")
    Files.write(path, Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte) ++ "object Main".getBytes(StandardCharsets.UTF_8))

    val snapshot = SourceSnapshot.capture(path)

    assertEquals(snapshot.content, None)
    assertEquals(snapshot.unverifiableReason, Some(UnverifiableReason.UnsupportedSourceEncodingOrBom))

  test("source snapshot returns the typed SourceUnreadable branch"):
    val snapshot = SourceSnapshot.capture(Path.of("target", "missing-source-snapshot.scala"))

    assertEquals(snapshot.content, None)
    assertEquals(snapshot.unverifiableReason, Some(UnverifiableReason.SourceUnreadable))

  private def write(path: Path, documents: Seq[TextDocument]): Unit =
    Files.write(path, TextDocuments(documents = documents).toByteArray)
