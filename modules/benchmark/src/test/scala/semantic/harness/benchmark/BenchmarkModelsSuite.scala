package semantic.harness.benchmark

import io.circe.parser.decode
import io.circe.syntax.*

class BenchmarkModelsSuite extends munit.FunSuite:
  test("BenchmarkCase decodes from JSON"):
    val json =
      """{
        |  "id": "compile-error-1",
        |  "title": "Simple compile error repair",
        |  "description": "Repair a missing identifier.",
        |  "mode": "compile-test-only",
        |  "initialProject": "examples/scala3-compile-failure",
        |  "successCommand": "compile --json",
        |  "allowedCommands": ["compile --json", "test --json", "errors --json"],
        |  "expectedSignals": ["compile-failure"]
        |}""".stripMargin

    val decoded = decode[BenchmarkCase](json)
    assertEquals(decoded.map(_.id), Right("compile-error-1"))
    assertEquals(decoded.map(_.expectedIntent), Right(None))
    assertEquals(decoded.map(_.acceptablePatchFamilies), Right(Nil))
    assertEquals(decoded.map(_.intentNotes), Right(Nil))
    assert(decoded.exists(BenchmarkValidation.valid))

  test("BenchmarkCase decodes optional intent metadata"):
    val json =
      """{
        |  "id": "semantic-misleading-1",
        |  "title": "Misleading same-name semantic evidence",
        |  "description": "Repair a tiny Scala project with more than one compile-valid patch.",
        |  "mode": "semantic-harness",
        |  "initialProject": "examples/scala3-semantic-misleading",
        |  "successCommand": "compile --json",
        |  "allowedCommands": ["compile --json", "symbol-at --json"],
        |  "expectedSignals": ["type-mismatch"],
        |  "expectedIntent": "Preserve the declared Int result intent.",
        |  "acceptablePatchFamilies": ["change import A.* to import B.*"],
        |  "intentNotes": ["Changing result type to String is compile-valid but changes intent."]
        |}""".stripMargin

    val decoded = decode[BenchmarkCase](json)
    assertEquals(decoded.map(_.expectedIntent), Right(Some("Preserve the declared Int result intent.")))
    assertEquals(decoded.map(_.acceptablePatchFamilies), Right(List("change import A.* to import B.*")))
    assertEquals(
      decoded.map(_.intentNotes),
      Right(List("Changing result type to String is compile-valid but changes intent."))
    )

  test("BenchmarkRun encodes to JSON"):
    val run = BenchmarkRun(
      caseId = "compile-error-1",
      mode = "compile-test-only",
      success = true,
      iterations = 2,
      commandsUsed = List("compile --json", "test --json"),
      semanticCommandsUsed = List("symbol-at"),
      semanticAssessment = "helpful",
      finalStatus = "success",
      notes = List("manual run"),
      intentAssessment = Some("preserved"),
      intentAssessmentNotes = List("Patch kept the intended result type."),
      commandCompliance = Some("compliant"),
      commandComplianceNotes = List("Required validation command was run."),
      requiredCommandsUsed = List("compile --json"),
      forbiddenCommandsUsed = Nil,
      extraCommandsUsed = List("pwd"),
      environmentDeviations = Nil
    )

    val json = run.asJson.noSpaces
    assertEquals(decode[BenchmarkRun](json), Right(run))

  test("BenchmarkRun decodes legacy JSON without semantic metadata"):
    val json =
      """{
        |  "caseId": "compile-error-1",
        |  "mode": "compile-test-only",
        |  "success": true,
        |  "iterations": 1,
        |  "commandsUsed": ["compile --json"],
        |  "finalStatus": "success",
        |  "notes": ["legacy run"]
        |}""".stripMargin

    val expected = BenchmarkRun(
      caseId = "compile-error-1",
      mode = "compile-test-only",
      success = true,
      iterations = 1,
      commandsUsed = List("compile --json"),
      semanticCommandsUsed = Nil,
      semanticAssessment = "uncertain",
      finalStatus = "success",
      notes = List("legacy run"),
      intentAssessment = None,
      intentAssessmentNotes = Nil,
      commandCompliance = None,
      commandComplianceNotes = Nil,
      requiredCommandsUsed = Nil,
      forbiddenCommandsUsed = Nil,
      extraCommandsUsed = Nil,
      environmentDeviations = Nil
    )

    assertEquals(decode[BenchmarkRun](json), Right(expected))

  test("BenchmarkRun decodes new intent metadata"):
    val json =
      """{
        |  "caseId": "semantic-misleading-1",
        |  "mode": "semantic-harness",
        |  "success": true,
        |  "iterations": 1,
        |  "commandsUsed": ["compile --json"],
        |  "semanticCommandsUsed": ["symbol-at"],
        |  "semanticAssessment": "misleading",
        |  "finalStatus": "success",
        |  "notes": ["semantic evidence described the broken binding"],
        |  "intentAssessment": "changed",
        |  "intentAssessmentNotes": ["Patch changed the declared result type to String."]
        |}""".stripMargin

    val expected = BenchmarkRun(
      caseId = "semantic-misleading-1",
      mode = "semantic-harness",
      success = true,
      iterations = 1,
      commandsUsed = List("compile --json"),
      semanticCommandsUsed = List("symbol-at"),
      semanticAssessment = "misleading",
      finalStatus = "success",
      notes = List("semantic evidence described the broken binding"),
      intentAssessment = Some("changed"),
      intentAssessmentNotes = List("Patch changed the declared result type to String.")
    )

    assertEquals(decode[BenchmarkRun](json), Right(expected))

  test("BenchmarkRun decodes command compliance metadata"):
    val json =
      """{
        |  "caseId": "semantic-required-1",
        |  "mode": "semantic-harness",
        |  "success": true,
        |  "iterations": 1,
        |  "commandsUsed": ["compile --json", "symbol-at --json"],
        |  "semanticCommandsUsed": ["symbol-at"],
        |  "semanticAssessment": "helpful",
        |  "finalStatus": "success",
        |  "notes": ["manual run"],
        |  "intentAssessment": "preserved",
        |  "intentAssessmentNotes": ["Patch used the intended helper."],
        |  "commandCompliance": "compliant",
        |  "commandComplianceNotes": ["Required semantic command and final validation were used."],
        |  "requiredCommandsUsed": ["symbol-at", "compile --json"],
        |  "forbiddenCommandsUsed": [],
        |  "extraCommandsUsed": ["pwd"],
        |  "environmentDeviations": []
        |}""".stripMargin

    val expected = BenchmarkRun(
      caseId = "semantic-required-1",
      mode = "semantic-harness",
      success = true,
      iterations = 1,
      commandsUsed = List("compile --json", "symbol-at --json"),
      semanticCommandsUsed = List("symbol-at"),
      semanticAssessment = "helpful",
      finalStatus = "success",
      notes = List("manual run"),
      intentAssessment = Some("preserved"),
      intentAssessmentNotes = List("Patch used the intended helper."),
      commandCompliance = Some("compliant"),
      commandComplianceNotes = List("Required semantic command and final validation were used."),
      requiredCommandsUsed = List("symbol-at", "compile --json"),
      forbiddenCommandsUsed = Nil,
      extraCommandsUsed = List("pwd"),
      environmentDeviations = Nil
    )

    assertEquals(decode[BenchmarkRun](json), Right(expected))
