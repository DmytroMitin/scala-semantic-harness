package semantic.harness.reconciliation

import java.nio.file.Path
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticFileSummary
import semantic.harness.semanticdb_reader.SemanticOccurrence
import semantic.harness.semanticdb_reader.SemanticRange
import semantic.harness.semanticdb_reader.SemanticdbReader

object SemanticReconciler:
  def reconcile(
    file: Path,
    line: Int,
    column: Int,
    semanticdb: Path
  ): Either[String, ReconciliationResult] =
    for
      compilerResult <- PresentationCompilerService().symbolAt(file, line, column)
      summary <- SemanticdbReader.read(semanticdb)
    yield reconcile(file.toString, line, column, compilerResult, summary)

  def reconcile(
    file: String,
    line: Int,
    column: Int,
    compilerResult: SymbolAtResult,
    summary: SemanticFileSummary
  ): ReconciliationResult =
    val queryPosition = pointRange(line, column)
    val selected = selectOccurrence(summary, queryPosition.startLine, queryPosition.startCharacter)
    val semanticdbSymbol = selected.map(_.symbol)
    val range = reconciledRange(
      selected.flatMap(_.range).map(sourceRange),
      compilerResult.range
    )

    ReconciliationResult(
      file = file,
      queryPosition = queryPosition,
      result = ReconciledSymbol(
        semanticdbSymbol = semanticdbSymbol,
        compilerSymbol = compilerResult.symbol,
        displayName = compilerResult.displayName,
        range = range,
        status = status(semanticdbSymbol, compilerResult.symbol, selected, compilerResult)
      )
    )

  private def selectOccurrence(
    summary: SemanticFileSummary,
    line: Int,
    character: Int
  ): Option[SemanticOccurrence] =
    summary.occurrences
      .zipWithIndex
      .flatMap { case (occurrence, index) =>
        occurrence.range.filter(contains(_, line, character)).map(range => (occurrence, rangeSize(range), index))
      }
      .sortBy { case (_, size, index) => (size, index) }
      .headOption
      .map(_._1)

  private def status(
    semanticdbSymbol: Option[String],
    compilerSymbol: Option[String],
    selected: Option[SemanticOccurrence],
    compilerResult: SymbolAtResult
  ): ReconciliationStatus =
    (semanticdbSymbol, compilerSymbol) match
      case (Some(left), Some(right)) if left == right =>
        ReconciliationStatus.ExactMatch
      case (Some(_), Some(_)) =>
        ReconciliationStatus.SymbolMismatch
      case _ if selected.nonEmpty || compilerResult.symbol.nonEmpty =>
        ReconciliationStatus.RangeMatchOnly
      case _ =>
        ReconciliationStatus.NoMatch

  private def pointRange(line: Int, column: Int): SourceRange =
    val zeroBasedLine = line - 1
    val zeroBasedColumn = column - 1
    SourceRange(
      startLine = zeroBasedLine,
      startCharacter = zeroBasedColumn,
      endLine = zeroBasedLine,
      endCharacter = zeroBasedColumn
    )

  private def contains(range: SemanticRange, line: Int, character: Int): Boolean =
    compare(line, character, range.startLine, range.startCharacter) >= 0 &&
      compare(line, character, range.endLine, range.endCharacter) < 0

  private def compare(leftLine: Int, leftCharacter: Int, rightLine: Int, rightCharacter: Int): Int =
    val lineCompare = leftLine.compare(rightLine)
    if lineCompare != 0 then lineCompare
    else leftCharacter.compare(rightCharacter)

  private def rangeSize(range: SemanticRange): (Int, Int) =
    (range.endLine - range.startLine, range.endCharacter - range.startCharacter)

  private def sourceRange(range: SemanticRange): SourceRange =
    SourceRange(
      startLine = range.startLine,
      startCharacter = range.startCharacter,
      endLine = range.endLine,
      endCharacter = range.endCharacter
    )

  private def reconciledRange(
    semanticdbRange: Option[SourceRange],
    compilerRange: Option[SourceRange]
  ): Option[SourceRange] =
    (semanticdbRange, compilerRange) match
      case (Some(left), Some(right)) if overlaps(left, right) => Some(left)
      case (_, Some(right))                                  => Some(right)
      case (Some(left), _)                                   => Some(left)
      case _                                                 => None

  private def overlaps(left: SourceRange, right: SourceRange): Boolean =
    compare(left.startLine, left.startCharacter, right.endLine, right.endCharacter) < 0 &&
      compare(right.startLine, right.startCharacter, left.endLine, left.endCharacter) < 0
