package semantic.harness.reconciliation

import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.semanticdb_reader.SemanticFileSummary
import semantic.harness.semanticdb_reader.SemanticOccurrence
import semantic.harness.semanticdb_reader.SemanticRange

class SemanticReconcilerSuite extends munit.FunSuite:
  test("returns ExactMatch when SemanticDB and compiler symbols are identical"):
    val result = SemanticReconciler.reconcile(
      file = "Main.scala",
      line = 2,
      column = 4,
      compilerResult = compiler(symbol = Some("example/Main.add().")),
      summary = summary(occurrence("example/Main.add().", SemanticRange(1, 2, 1, 8)))
    )

    assertEquals(result.result.status, ReconciliationStatus.ExactMatch)
    assertEquals(result.result.semanticdbSymbol, Some("example/Main.add()."))
    assertEquals(result.result.compilerSymbol, Some("example/Main.add()."))

  test("returns SymbolMismatch without normalizing different symbol strings"):
    val result = SemanticReconciler.reconcile(
      file = "Main.scala",
      line = 2,
      column = 4,
      compilerResult = compiler(symbol = Some("example/Main.add().")),
      summary = summary(occurrence("example/Main.add().semanticdb", SemanticRange(1, 2, 1, 8)))
    )

    assertEquals(result.result.status, ReconciliationStatus.SymbolMismatch)
    assertEquals(result.result.semanticdbSymbol, Some("example/Main.add().semanticdb"))
    assertEquals(result.result.compilerSymbol, Some("example/Main.add()."))

  test("returns RangeMatchOnly when range evidence exists but identity is incomplete"):
    val result = SemanticReconciler.reconcile(
      file = "Main.scala",
      line = 2,
      column = 4,
      compilerResult = compiler(symbol = None),
      summary = summary(occurrence("example/Main.add().", SemanticRange(1, 2, 1, 8)))
    )

    assertEquals(result.result.status, ReconciliationStatus.RangeMatchOnly)
    assertEquals(result.result.semanticdbSymbol, Some("example/Main.add()."))
    assertEquals(result.result.compilerSymbol, None)

  test("returns NoMatch when neither source has usable evidence"):
    val result = SemanticReconciler.reconcile(
      file = "Main.scala",
      line = 9,
      column = 1,
      compilerResult = SymbolAtResult(
        symbol = None,
        displayName = None,
        range = None,
        source = "Main.scala"
      ),
      summary = summary(occurrence("example/Main.add().", SemanticRange(1, 2, 1, 8)))
    )

    assertEquals(result.result.status, ReconciliationStatus.NoMatch)
    assertEquals(result.result.semanticdbSymbol, None)
    assertEquals(result.result.compilerSymbol, None)

  test("selects the smallest containing range and then SemanticDB payload order"):
    val broad = occurrence("broad", SemanticRange(1, 0, 1, 20))
    val firstSmall = occurrence("firstSmall", SemanticRange(1, 2, 1, 6))
    val secondSmall = occurrence("secondSmall", SemanticRange(1, 2, 1, 6))

    val result = SemanticReconciler.reconcile(
      file = "Main.scala",
      line = 2,
      column = 4,
      compilerResult = compiler(symbol = Some("compiler")),
      summary = summary(broad, firstSmall, secondSmall)
    )

    assertEquals(result.result.semanticdbSymbol, Some("firstSmall"))
    assertEquals(result.result.status, ReconciliationStatus.SymbolMismatch)

  private def compiler(symbol: Option[String]): SymbolAtResult =
    SymbolAtResult(
      symbol = symbol,
      displayName = Some("add"),
      range = Some(SourceRange(1, 2, 1, 6)),
      source = "Main.scala"
    )

  private def occurrence(symbol: String, range: SemanticRange): SemanticOccurrence =
    SemanticOccurrence(
      symbol = symbol,
      role = "REFERENCE",
      range = Some(range)
    )

  private def summary(occurrences: SemanticOccurrence*): SemanticFileSummary =
    SemanticFileSummary(
      uri = "Main.scala",
      symbols = Nil,
      occurrences = occurrences.toList
    )
