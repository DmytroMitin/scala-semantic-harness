package semantic.harness.core

import io.circe.parser.decode
import io.circe.syntax.*

class DiagnosticModelsSuite extends munit.FunSuite:
  test("Diagnostic encodes and decodes as JSON"):
    val diagnostic = Diagnostic(
      severity = "error",
      message = "Not found: missingValue",
      position = Some(
        DiagnosticPosition(
          file = "src/main/scala/Main.scala",
          line = 7,
          column = 13
        )
      )
    )

    val json = diagnostic.asJson.noSpaces

    assertEquals(decode[Diagnostic](json), Right(diagnostic))

  test("CompileReport encodes and decodes as JSON"):
    val report = CompileReport(
      success = false,
      diagnostics = List(
        Diagnostic(
          severity = "error",
          message = "type mismatch",
          position = Some(DiagnosticPosition("Example.scala", 10, 5))
        )
      )
    )

    val json = report.asJson.noSpaces
    assert(json.contains(""""schemaVersion":"semantic-scala.compile-result.v1""""))
    assertEquals(decode[CompileReport](json), Right(report))

  test("CompileReport decodes legacy JSON without schemaVersion"):
    val json =
      """{
        |  "success": true,
        |  "diagnostics": []
        |}""".stripMargin

    val decoded = decode[CompileReport](json)
    assertEquals(decoded.map(_.schemaVersion), Right(CompileReport.SchemaVersion))
    assertEquals(decoded.map(_.success), Right(true))
    assertEquals(decoded.map(_.diagnostics), Right(Nil))

  test("TestReport encodes and decodes as JSON"):
    val report = TestReport(
      success = false,
      total = 2,
      passed = 1,
      failed = 1,
      failures = List(
        Diagnostic(
          severity = "failure",
          message = "expected true but was false",
          position = None
        )
      )
    )

    val json = report.asJson.noSpaces
    assert(json.contains(""""schemaVersion":"semantic-scala.test-result.v1""""))
    assertEquals(decode[TestReport](json), Right(report))

  test("TestReport decodes legacy JSON without schemaVersion"):
    val json =
      """{
        |  "success": true,
        |  "total": 2,
        |  "passed": 2,
        |  "failed": 0,
        |  "failures": []
        |}""".stripMargin

    val decoded = decode[TestReport](json)
    assertEquals(decoded.map(_.schemaVersion), Right(TestReport.SchemaVersion))
    assertEquals(decoded.map(_.success), Right(true))
    assertEquals(decoded.map(_.total), Right(2))
