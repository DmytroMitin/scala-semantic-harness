package semantic.harness.reconciliation

import io.circe.parser.decode
import io.circe.syntax.*
import semantic.harness.presentation.SourceRange

class ReconciliationModelsSuite extends munit.FunSuite:
  test("ReconciliationStatus encodes as a string"):
    assertEquals(ReconciliationStatus.ExactMatch.asJson.noSpaces, "\"ExactMatch\"")
    assertEquals(ReconciliationStatus.RangeMatchOnly.asJson.noSpaces, "\"RangeMatchOnly\"")
    assertEquals(ReconciliationStatus.SymbolMismatch.asJson.noSpaces, "\"SymbolMismatch\"")
    assertEquals(ReconciliationStatus.NoMatch.asJson.noSpaces, "\"NoMatch\"")

  test("ReconciliationResult JSON decodes"):
    val result = ReconciliationResult(
      file = "Main.scala",
      queryPosition = SourceRange(5, 15, 5, 15),
      result = ReconciledSymbol(
        semanticdbSymbol = Some("example/Main.add()."),
        compilerSymbol = Some("example/Main.add()."),
        displayName = Some("add"),
        range = Some(SourceRange(4, 6, 4, 9)),
        status = ReconciliationStatus.ExactMatch
      )
    )

    val json = result.asJson.noSpaces
    assert(json.contains(""""schemaVersion":"semantic-scala.reconcile-symbol-result.v1""""))
    assertEquals(decode[ReconciliationResult](json), Right(result))

  test("ReconciliationResult decodes legacy JSON without schemaVersion"):
    val json =
      """{
        |  "file": "Main.scala",
        |  "queryPosition": {
        |    "startLine": 5,
        |    "startCharacter": 15,
        |    "endLine": 5,
        |    "endCharacter": 15
        |  },
        |  "result": {
        |    "semanticdbSymbol": null,
        |    "compilerSymbol": null,
        |    "displayName": null,
        |    "range": null,
        |    "status": "NoMatch"
        |  }
        |}""".stripMargin

    val decoded = decode[ReconciliationResult](json)
    assertEquals(decoded.map(_.schemaVersion), Right(ReconciliationResult.SchemaVersion))
    assertEquals(decoded.map(_.file), Right("Main.scala"))
    assertEquals(decoded.map(_.result.status), Right(ReconciliationStatus.NoMatch))
