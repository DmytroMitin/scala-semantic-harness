package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.Range as SemanticdbRange
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

object SemanticdbReader:
  def read(path: Path): Either[String, SemanticFileSummary] =
    if !Files.exists(path) then Left(s"SemanticDB file does not exist: $path")
    else if !Files.isRegularFile(path) then Left(s"SemanticDB path is not a file: $path")
    else if path.getFileName == null || !path.getFileName.toString.endsWith(".semanticdb") then
      Left(s"SemanticDB path must point to a .semanticdb file: $path")
    else
      try
        val documents = TextDocuments.parseFrom(Files.readAllBytes(path))
        documents.documents.headOption match
          case Some(document) => Right(summary(document))
          case None           => Left(s"SemanticDB file contains no documents: $path")
      catch
        case exception: Exception =>
          Left(s"Unable to read SemanticDB file $path: ${exception.getMessage}")

  def readSnapshot(path: Path): Either[String, ArtifactSnapshot] =
    if !Files.exists(path) then Left(s"SemanticDB file does not exist: $path")
    else if !Files.isRegularFile(path) then Left(s"SemanticDB path is not a file: $path")
    else if path.getFileName == null || !path.getFileName.toString.endsWith(".semanticdb") then
      Left(s"SemanticDB path must point to a .semanticdb file: $path")
    else
      try
        val normalized = path.toAbsolutePath.normalize()
        val bytes = Files.readAllBytes(normalized)
        val documents = TextDocuments.parseFrom(bytes).documents.toList.zipWithIndex.map { case (document, index) =>
          SemanticdbDocumentSnapshot(
            index = index,
            uri = document.uri,
            semanticdbMd5 = Option(document.md5).filter(_.nonEmpty),
            hasEmbeddedText = document.text.nonEmpty,
            summary = summary(document),
            embeddedText = Option(document.text).filter(_.nonEmpty)
          )
        }
        if documents.isEmpty then Left(s"SemanticDB file contains no documents: $path")
        else Right(ArtifactSnapshot(
          path = normalized,
          sha256 = Digests.sha256(bytes),
          mtimeMillis = Files.getLastModifiedTime(normalized).toMillis,
          documents = documents
        ))
      catch
        case exception: Exception =>
          Left(s"Unable to read SemanticDB file $path: ${exception.getMessage}")

  private[semanticdb_reader] def summary(document: TextDocument): SemanticFileSummary =
    SemanticFileSummary(
      uri = document.uri,
      symbols = document.symbols.map(symbol).toList,
      occurrences = document.occurrences.map(occurrence).toList
    )

  private def symbol(info: SymbolInformation): SemanticSymbol =
    SemanticSymbol(
      symbol = info.symbol,
      displayName = info.displayName,
      kind = enumName(info.kind),
      language = enumName(info.language)
    )

  private def occurrence(info: SymbolOccurrence): SemanticOccurrence =
    SemanticOccurrence(
      symbol = info.symbol,
      role = enumName(info.role).getOrElse("UNKNOWN_ROLE"),
      range = info.range.map(range)
    )

  private def range(value: SemanticdbRange): SemanticRange =
    SemanticRange(
      startLine = value.startLine,
      startCharacter = value.startCharacter,
      endLine = value.endLine,
      endCharacter = value.endCharacter
    )

  private def enumName(value: scalapb.GeneratedEnum): Option[String] =
    val name = value.toString
    Option.when(!name.startsWith("Unrecognized(") && !name.startsWith("UNKNOWN_"))(name)
