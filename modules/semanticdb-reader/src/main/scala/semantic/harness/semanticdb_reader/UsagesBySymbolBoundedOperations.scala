package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.TextDocuments

private[semanticdb_reader] trait UsagesBySymbolBoundedOperations:
  def readAllBytes(path: Path): Array[Byte]
  def parseTextDocuments(bytes: Array[Byte]): TextDocuments
  def size(path: Path): Long
  def realPath(path: Path): Path

private[semanticdb_reader] object UsagesBySymbolBoundedOperations:
  val Default: UsagesBySymbolBoundedOperations = new UsagesBySymbolBoundedOperations:
    def readAllBytes(path: Path): Array[Byte] = Files.readAllBytes(path)
    def parseTextDocuments(bytes: Array[Byte]): TextDocuments = TextDocuments.parseFrom(bytes)
    def size(path: Path): Long = Files.size(path)
    def realPath(path: Path): Path = path.toRealPath()
