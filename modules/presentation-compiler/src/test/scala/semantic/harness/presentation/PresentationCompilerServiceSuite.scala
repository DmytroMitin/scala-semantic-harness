package semantic.harness.presentation

import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.file.Files
import java.nio.file.Path

class PresentationCompilerServiceSuite extends munit.FunSuite:
  test("symbolAt returns symbol information for fixture source"):
    val result = PresentationCompilerService().symbolAt(fixture, line = 6, column = 16)

    val symbol = result.fold(message => fail(message), identity)
    assert(symbol.symbol.exists(_.nonEmpty))
    assert(symbol.displayName.exists(_.nonEmpty))
    assertEquals(symbol.source, fixture.toString)
    assertEquals(symbol.schemaVersion, SymbolAtResult.SchemaVersion)

  test("symbolAt returns empty optional fields for valid no-symbol position"):
    val result = PresentationCompilerService().symbolAt(fixture, line = 6, column = 1)

    val symbol = result.fold(message => fail(message), identity)
    assertEquals(symbol.symbol, None)
    assertEquals(symbol.displayName, None)

  test("symbolAt returns clear error for invalid source file"):
    val result = PresentationCompilerService().symbolAt(
      Path.of("modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Missing.scala"),
      line = 1,
      column = 1
    )

    assert(result.left.exists(_.contains("does not exist")))

  test("symbolAt validates one-based line and column"):
    assert(PresentationCompilerService().symbolAt(fixture, line = 0, column = 1).left.exists(_.contains("Line must be positive")))
    assert(PresentationCompilerService().symbolAt(fixture, line = 1, column = 0).left.exists(_.contains("Column must be positive")))
    assert(PresentationCompilerService().symbolAt(fixture, line = 100, column = 1).left.exists(_.contains("outside the source file")))
    assert(PresentationCompilerService().symbolAt(fixture, line = 1, column = 100).left.exists(_.contains("outside line")))

  test("symbolAt preserves UTF-16 columns after a surrogate pair"):
    val unicodeFixture =
      Path.of("modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala")
    val line = Files.readAllLines(unicodeFixture).get(14)
    val column = line.indexOf("unicodeOptional") + 1
    val result = PresentationCompilerService().symbolAt(unicodeFixture, line = 15, column = column)

    val symbol = result.fold(message => fail(message), identity)
    assert(symbol.displayName.exists(_.contains("unicodeOptional")))

  test("SymbolAtResult encodes schemaVersion and decodes legacy JSON"):
    val result = SymbolAtResult(
      symbol = Some("example/Main.add()."),
      displayName = Some("add"),
      range = Some(SourceRange(1, 2, 1, 6)),
      source = "Main.scala"
    )

    val json = result.asJson.noSpaces
    assert(json.contains(""""schemaVersion":"semantic-scala.symbol-at-result.v1""""))
    assertEquals(decode[SymbolAtResult](json), Right(result))

    val legacy =
      """{
        |  "symbol": "example/Main.add().",
        |  "displayName": "add",
        |  "range": null,
        |  "source": "Main.scala"
        |}""".stripMargin

    assertEquals(decode[SymbolAtResult](legacy).map(_.schemaVersion), Right(SymbolAtResult.SchemaVersion))

  private def fixture: Path =
    Path.of("modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala")
