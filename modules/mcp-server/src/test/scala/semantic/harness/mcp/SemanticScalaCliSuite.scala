package semantic.harness.mcp

import java.nio.file.Files
import java.nio.file.Path

import io.circe.Json
import io.circe.syntax.*
import semantic.harness.reconciliation.ReconciliationResult
import semantic.harness.reconciliation.PointEvidenceReport
import semantic.harness.semanticdb_reader.SemanticFileSummary
import scala.jdk.CollectionConverters.*

class SemanticScalaCliSuite extends munit.FunSuite:
  test("semantic_compile constructs argv and runs in workspace"):
    withTempWorkspace { workspace =>
      val runner = RecordingRunner(successPayload(success = true))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticCompile(workspace)

      assert(result.ok)
      assertEquals(runner.calls, List((List("/tmp/semantic-scala", "compile", "--json"), workspace.toAbsolutePath.normalize())))
      assertEquals(result.command, List("semantic-scala", "compile", "--json"))
    }

  test("semantic_errors constructs argv and runs in workspace"):
    withTempWorkspace { workspace =>
      val runner = RecordingRunner(successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticErrors(workspace)

      assert(result.ok)
      assertEquals(runner.calls, List((List("/tmp/semantic-scala", "errors", "--json"), workspace.toAbsolutePath.normalize())))
      assertEquals(result.command, List("semantic-scala", "errors", "--json"))
    }

  test("semantic_test constructs argv and runs in workspace"):
    withTempWorkspace { workspace =>
      val runner = RecordingRunner(testPayload(success = true))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticTest(workspace)

      assert(result.ok)
      assertEquals(runner.calls, List((List("/tmp/semantic-scala", "test", "--json"), workspace.toAbsolutePath.normalize())))
      assertEquals(result.command, List("semantic-scala", "test", "--json"))
    }

  test("build-oracle tools append one validated sbt project before json"):
    withTempWorkspace { workspace =>
      val compileRunner = RecordingRunner(successPayload(success = true))
      val errorsRunner = RecordingRunner(
        successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion)
      )
      val testRunner = RecordingRunner(testPayload(success = true))

      val compile = SemanticScalaCli(Path.of("/tmp/semantic-scala"), compileRunner)
        .semanticCompile(workspace, Some("core2_13"))
      val errors = SemanticScalaCli(Path.of("/tmp/semantic-scala"), errorsRunner)
        .semanticErrors(workspace, Some("core2_13"))
      val test = SemanticScalaCli(Path.of("/tmp/semantic-scala"), testRunner)
        .semanticTest(workspace, Some("tests_2"))

      assert(compile.ok)
      assert(errors.ok)
      assert(test.ok)
      assertEquals(
        compileRunner.calls.map(_._1),
        List(List("/tmp/semantic-scala", "compile", "--sbt-project", "core2_13", "--json"))
      )
      assertEquals(
        errorsRunner.calls.map(_._1),
        List(List("/tmp/semantic-scala", "errors", "--sbt-project", "core2_13", "--json"))
      )
      assertEquals(
        testRunner.calls.map(_._1),
        List(List("/tmp/semantic-scala", "test", "--sbt-project", "tests_2", "--json"))
      )
    }

  test("build-oracle tools reject invalid sbt projects before process launch"):
    withTempWorkspace { workspace =>
      val runner = RecordingRunner(successPayload(success = true))
      val result = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)
        .semanticCompile(workspace, Some("core2_13;test"))

      assert(!result.ok)
      assertEquals(runner.calls, Nil)
      assert(result.error.exists(_.contains("sbt project ID must start with a letter")))
      assert(!result.error.exists(_.contains(workspace.toString)))
    }

  test("semantic_effect_summary constructs argv and runs in workspace"):
    withScalaWorkspace { (workspace, file) =>
      val runner = RecordingRunner(effectSummaryPayload(methods = List("example.UserRepo.find")))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticEffectSummary(workspace, file)

      assert(result.ok)
      assertEquals(
        runner.calls,
        List((List("/tmp/semantic-scala", "effect-summary", "--file", file, "--json"), workspace.toAbsolutePath.normalize()))
      )
      assertEquals(result.command, List("semantic-scala", "effect-summary", "--file", file, "--json"))
    }

  test("semantic_symbol_at constructs argv and runs in workspace"):
    withScalaWorkspace { (workspace, file) =>
      val runner = RecordingRunner(symbolAtPayload(symbol = Some("example/Main.add()."), displayName = Some("add")))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(result.ok)
      assertEquals(
        runner.calls,
        List((List("/tmp/semantic-scala", "symbol-at", "--file", file, "--line", "6", "--col", "16", "--json"), workspace.toAbsolutePath.normalize()))
      )
      assertEquals(result.command, List("semantic-scala", "symbol-at", "--file", file, "--line", "6", "--col", "16", "--json"))
    }

  test("semantic_symbols constructs argv and runs in workspace"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val runner = RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = List("example/Main#")))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(result.ok)
      assertEquals(
        runner.calls,
        List((List("/tmp/semantic-scala", "symbols", "--semanticdb", semanticdb, "--json"), workspace.toAbsolutePath.normalize()))
      )
      assertEquals(result.command, List("semantic-scala", "symbols", "--semanticdb", semanticdb, "--json"))
    }

  test("semantic_reconcile_symbol constructs argv and runs in workspace"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val runner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(result.ok)
      assertEquals(
        runner.calls,
        List(
          (
            List(
              "/tmp/semantic-scala",
              "reconcile-symbol",
              "--file",
              file,
              "--line",
              "6",
              "--col",
              "16",
              "--semanticdb",
              semanticdb,
              "--json"
            ),
            workspace.toAbsolutePath.normalize()
          )
        )
      )
      assertEquals(
        result.command,
        List("semantic-scala", "reconcile-symbol", "--file", file, "--line", "6", "--col", "16", "--semanticdb", semanticdb, "--json")
      )
    }

  test("semantic_point_evidence constructs CLI-source-of-truth argv and runs in workspace"):
    withScalaWorkspace { (workspace, file) =>
      val runner = RecordingRunner(pointEvidencePayload)
      val cli = SemanticScalaCli(Path.of("/tmp/semantic-scala"), runner)

      val result = cli.semanticPointEvidence(workspace, file, line = 2, col = 7)

      assert(result.ok)
      val expected = List(
        "/tmp/semantic-scala",
        "point-evidence",
        "--workspace",
        ".",
        "--file",
        file,
        "--line",
        "2",
        "--col",
        "7",
        "--json"
      )
      assertEquals(runner.calls, List((expected, workspace.toAbsolutePath.normalize())))
      assertEquals(result.command, "semantic-scala" :: expected.drop(1))
      assertEquals(result.schemaVersion, Some(PointEvidenceReport.SchemaVersion))
    }

  test("semantic_point_evidence validates relative contained Scala input and positive position"):
    withScalaWorkspace { (workspace, file) =>
      val invalid = List(
        ("../Outside.scala", 1, 1, "escapes workspace"),
        (file, 0, 1, "Invalid line"),
        (file, 1, 0, "Invalid col")
      )

      invalid.foreach { case (candidate, line, col, message) =>
        val runner = RecordingRunner(pointEvidencePayload)
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner)
          .semanticPointEvidence(workspace, candidate, line, col)
        assert(!result.ok)
        assertEquals(runner.calls, Nil)
        assert(result.error.exists(_.contains(message)), clue(result.error))
      }
    }

  test("semantic_point_evidence rejects a CLI payload with the wrong schema"):
    withScalaWorkspace { (workspace, file) =>
      val runner = RecordingRunner(successPayload(success = true))
      val result = SemanticScalaCli(Path.of("semantic-scala"), runner)
        .semanticPointEvidence(workspace, file, line = 2, col = 7)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains(s"expected ${PointEvidenceReport.SchemaVersion}")))
    }

  test("semantic_compile returns ok true for successful compile payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(Path.of("semantic-scala"), RecordingRunner(successPayload(success = true)))

      val result = cli.semanticCompile(workspace)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.CompileSchemaVersion))
      assertEquals(payloadBoolean(result, "success"), Some(true))
    }

  test("semantic_compile returns ok true for compile failure payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(Path.of("semantic-scala"), RecordingRunner(successPayload(success = false)))

      val result = cli.semanticCompile(workspace)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.CompileSchemaVersion))
      assertEquals(payloadBoolean(result, "success"), Some(false))
    }

  test("semantic_errors returns ok true for successful errors payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))
      )

      val result = cli.semanticErrors(workspace)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.ErrorsSchemaVersion))
      assertEquals(payloadBoolean(result, "success"), Some(true))
    }

  test("semantic_errors returns ok true for compile failure payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(successPayload(success = false, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))
      )

      val result = cli.semanticErrors(workspace)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.ErrorsSchemaVersion))
      assertEquals(payloadBoolean(result, "success"), Some(false))
    }

  test("semantic_test returns ok true for successful test payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(Path.of("semantic-scala"), RecordingRunner(testPayload(success = true)))

      val result = cli.semanticTest(workspace)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.TestSchemaVersion))
      assertEquals(payloadBoolean(result, "success"), Some(true))
      assertEquals(payloadInt(result, "failed"), Some(0))
    }

  test("semantic_test returns ok true for test failure payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(Path.of("semantic-scala"), RecordingRunner(testPayload(success = false, failed = 1)))

      val result = cli.semanticTest(workspace)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.TestSchemaVersion))
      assertEquals(payloadBoolean(result, "success"), Some(false))
      assertEquals(payloadInt(result, "failed"), Some(1))
    }

  test("semantic_effect_summary returns ok true and preserves methods payload"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(effectSummaryPayload(methods = List("example.UserRepo.find", "example.Main.getName")))
      )

      val result = cli.semanticEffectSummary(workspace, file)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.EffectSummarySchemaVersion))
      assertEquals(payloadString(result, "schemaVersion"), Some(SemanticScalaCli.EffectSummarySchemaVersion))
      assertEquals(payloadMethods(result), List("example.UserRepo.find", "example.Main.getName"))
    }

  test("semantic_symbol_at returns ok true and preserves point-query payload"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(symbolAtPayload(symbol = Some("example/Main.add()."), displayName = Some("add")))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.SymbolAtSchemaVersion))
      assertEquals(payloadString(result, "schemaVersion"), Some(SemanticScalaCli.SymbolAtSchemaVersion))
      assertEquals(payloadString(result, "symbol"), Some("example/Main.add()."))
      assertEquals(payloadString(result, "displayName"), Some("add"))
    }

  test("semantic_symbol_at returns ok true for valid no-symbol payload"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 1)

      assert(result.ok)
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.SymbolAtSchemaVersion))
      assertEquals(payloadString(result, "symbol"), None)
      assertEquals(payloadString(result, "displayName"), None)
    }

  test("semantic_symbols returns ok true and preserves symbols payload"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = List("example/Main#", "example/Main.add().")))
      )

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.SymbolsSchemaVersion))
      assertEquals(payloadString(result, "schemaVersion"), Some(SemanticFileSummary.SchemaVersion))
      assertEquals(payloadString(result, "uri"), Some("simple/Main.scala"))
      assertEquals(payloadSymbols(result), List("example/Main#", "example/Main.add()."))
    }

  test("semantic_reconcile_symbol returns ok true and preserves exact-match payload"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(reconcilePayload(status = "ExactMatch"))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(result.ok)
      assertEquals(result.exitCode, Some(0))
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.ReconcileSymbolSchemaVersion))
      assertEquals(payloadString(result, "schemaVersion"), Some(ReconciliationResult.SchemaVersion))
      assertEquals(payloadString(result, "file"), Some("src/main/scala/example/Main.scala"))
      assertEquals(payloadStatus(result), Some("ExactMatch"))
      assertEquals(payloadNestedString(result, List("result", "semanticdbSymbol")), Some("example/Main.add()."))
      assertEquals(payloadNestedString(result, List("result", "compilerSymbol")), Some("example/Main.add()."))
    }

  test("semantic_reconcile_symbol returns ok true for non-exact reconciliation status"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(reconcilePayload(status = "SymbolMismatch", semanticdbSymbol = "example/Main.old().", compilerSymbol = "example/Main.add()."))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(result.ok)
      assertEquals(result.schemaVersion, Some(SemanticScalaCli.ReconcileSymbolSchemaVersion))
      assertEquals(payloadStatus(result), Some("SymbolMismatch"))
    }

  test("semantic_compile returns ok false for non-zero CLI failure"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "semantic-scala not found"))
      )

      val result = cli.semanticCompile(workspace)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_errors returns ok false for non-zero CLI failure"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "semantic-scala not found"))
      )

      val result = cli.semanticErrors(workspace)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_test returns ok false for non-zero CLI failure"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "semantic-scala not found"))
      )

      val result = cli.semanticTest(workspace)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_effect_summary returns ok false for non-zero CLI failure"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "missing file"))
      )

      val result = cli.semanticEffectSummary(workspace, file)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_symbol_at returns ok false for non-zero CLI failure"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "bad position"))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_symbols returns ok false for non-zero CLI failure"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "bad semanticdb"))
      )

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_reconcile_symbol returns ok false for non-zero CLI failure"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 1, stdout = "", stderr = "bad reconcile"))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(!result.ok)
      assertEquals(result.exitCode, Some(1))
      assertEquals(result.payload, None)
      assertEquals(result.stderr, "")
      assertEquals(result.error, Some("semantic-scala command failed with exit code 1"))
    }

  test("semantic_compile returns ok false for malformed JSON"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticCompile(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_errors returns ok false for malformed JSON"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticErrors(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_test returns ok false for malformed JSON"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticTest(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_effect_summary returns ok false for malformed JSON"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticEffectSummary(workspace, file)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_symbol_at returns ok false for malformed JSON"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_symbols returns ok false for malformed JSON"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_reconcile_symbol returns ok false for malformed JSON"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = "not json", stderr = ""))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Invalid JSON from semantic-scala")))
    }

  test("semantic_compile returns ok false for missing schemaVersion"):
    withTempWorkspace { workspace =>
      val stdout = """{"success":false,"diagnostics":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticCompile(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_errors returns ok false for missing schemaVersion"):
    withTempWorkspace { workspace =>
      val stdout = """{"success":false,"diagnostics":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticErrors(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_test returns ok false for missing schemaVersion"):
    withTempWorkspace { workspace =>
      val stdout = """{"success":false,"total":1,"passed":0,"failed":1,"failures":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticTest(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_effect_summary returns ok false for missing schemaVersion"):
    withScalaWorkspace { (workspace, file) =>
      val stdout = """{"source":"src/main/scala/example/Main.scala","methods":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticEffectSummary(workspace, file)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_symbol_at returns ok false for missing schemaVersion"):
    withScalaWorkspace { (workspace, file) =>
      val stdout = """{"symbol":"example/Main.add().","displayName":"add","range":null,"source":"src/main/scala/example/Main.scala"}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_symbols returns ok false for missing schemaVersion"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val stdout = """{"uri":"simple/Main.scala","symbols":[],"occurrences":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_reconcile_symbol returns ok false for missing schemaVersion"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val stdout =
        """{"file":"src/main/scala/example/Main.scala","queryPosition":{"startLine":5,"startCharacter":15,"endLine":5,"endCharacter":15},"result":{"semanticdbSymbol":null,"compilerSymbol":null,"displayName":null,"range":null,"status":"NoMatch"}}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("Missing schemaVersion")))
    }

  test("semantic_compile returns ok false for wrong schemaVersion"):
    withTempWorkspace { workspace =>
      val stdout = """{"schemaVersion":"wrong","success":false,"diagnostics":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticCompile(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_errors returns ok false for wrong schemaVersion"):
    withTempWorkspace { workspace =>
      val stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","success":false,"diagnostics":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticErrors(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_test returns ok false for wrong schemaVersion"):
    withTempWorkspace { workspace =>
      val stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","success":false,"total":1,"passed":0,"failed":1,"failures":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticTest(workspace)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_effect_summary returns ok false for wrong schemaVersion"):
    withScalaWorkspace { (workspace, file) =>
      val stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","source":"src/main/scala/example/Main.scala","methods":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticEffectSummary(workspace, file)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_symbol_at returns ok false for wrong schemaVersion"):
    withScalaWorkspace { (workspace, file) =>
      val stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","symbol":"example/Main.add().","displayName":"add","range":null,"source":"src/main/scala/example/Main.scala"}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_symbols returns ok false for wrong schemaVersion"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","uri":"simple/Main.scala","symbols":[],"occurrences":[]}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_reconcile_symbol returns ok false for wrong schemaVersion"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val stdout =
        s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","file":"src/main/scala/example/Main.scala","queryPosition":{"startLine":5,"startCharacter":15,"endLine":5,"endCharacter":15},"result":{"semanticdbSymbol":null,"compilerSymbol":null,"displayName":null,"range":null,"status":"NoMatch"}}"""
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(!result.ok)
      assertEquals(result.payload, None)
      assert(result.error.exists(_.contains("schemaVersion mismatch")))
    }

  test("semantic_compile preserves stderr outside payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(successPayload(success = true).copy(stderr = "WARNING..."))
      )

      val result = cli.semanticCompile(workspace)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_errors preserves stderr outside payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(successPayload(success = false, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion).copy(stderr = "WARNING..."))
      )

      val result = cli.semanticErrors(workspace)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_test preserves stderr outside payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(testPayload(success = true).copy(stderr = "WARNING..."))
      )

      val result = cli.semanticTest(workspace)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_effect_summary preserves stderr outside payload"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(effectSummaryPayload(methods = List("example.UserRepo.find")).copy(stderr = "WARNING..."))
      )

      val result = cli.semanticEffectSummary(workspace, file)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_symbol_at preserves stderr outside payload"):
    withScalaWorkspace { (workspace, file) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(symbolAtPayload(symbol = Some("example/Main.add()."), displayName = Some("add")).copy(stderr = "WARNING..."))
      )

      val result = cli.semanticSymbolAt(workspace, file, line = 6, col = 16)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_symbols preserves stderr outside payload"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = List("example/Main#")).copy(stderr = "WARNING..."))
      )

      val result = cli.semanticSymbols(workspace, semanticdb)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_reconcile_symbol preserves stderr outside payload"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(reconcilePayload(status = "ExactMatch").copy(stderr = "WARNING..."))
      )

      val result = cli.semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)

      assert(result.ok)
      assertEquals(result.stderr, "WARNING...")
      assertEquals(payloadString(result, "stderr"), None)
    }

  test("semantic_compile wrapper encodes metadata outside payload"):
    withTempWorkspace { workspace =>
      val cli = SemanticScalaCli(
        Path.of("semantic-scala"),
        RecordingRunner(successPayload(success = false).copy(stderr = "WARNING..."))
      )

      val json = cli.semanticCompile(workspace).asJson

      assertEquals(json.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(json.hcursor.downField("exitCode").as[Int].toOption, Some(0))
      assertEquals(json.hcursor.downField("stderr").as[String].toOption, Some("WARNING..."))
      assertEquals(json.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(false))
      assertEquals(json.hcursor.downField("payload").downField("stderr").as[String].toOption, None)
    }

  test("semantic_compile validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(successPayload(success = true))
    val missingResult = SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticCompile(missing)

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(successPayload(success = true))
      val fileResult = SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticCompile(file)

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_errors validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-errors-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))
    val missingResult = SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticErrors(missing)

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))
      val fileResult = SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticErrors(file)

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_test validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-test-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(testPayload(success = true))
    val missingResult = SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticTest(missing)

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(testPayload(success = true))
      val fileResult = SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticTest(file)

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_effect_summary validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-effect-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(effectSummaryPayload(methods = Nil))
    val missingResult = SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticEffectSummary(missing, "src/Main.scala")

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(effectSummaryPayload(methods = Nil))
      val fileResult = SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticEffectSummary(file, "src/Main.scala")

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_symbol_at validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-symbol-at-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
    val missingResult = SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticSymbolAt(missing, "src/Main.scala", line = 1, col = 1)

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
      val fileResult = SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticSymbolAt(file, "src/Main.scala", line = 1, col = 1)

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_symbols validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-symbols-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = Nil))
    val missingResult = SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticSymbols(missing, "simple/Main.scala.semanticdb")

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = Nil))
      val fileResult = SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticSymbols(file, "simple/Main.scala.semanticdb")

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_reconcile_symbol validates workspace before invoking process"):
    val missing = Path.of("target", "definitely-missing-reconcile-workspace").toAbsolutePath.normalize()
    val missingRunner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
    val missingResult =
      SemanticScalaCli(Path.of("semantic-scala"), missingRunner).semanticReconcileSymbol(missing, "src/Main.scala", line = 1, col = 1, semanticdb = "simple/Main.scala.semanticdb")

    assert(!missingResult.ok)
    assertEquals(missingRunner.calls, Nil)
    assert(missingResult.error.exists(_.contains("Workspace does not exist")))

    val file = Files.createTempFile("semantic-scala-mcp", ".txt")
    try
      val fileRunner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
      val fileResult =
        SemanticScalaCli(Path.of("semantic-scala"), fileRunner).semanticReconcileSymbol(file, "src/Main.scala", line = 1, col = 1, semanticdb = "simple/Main.scala.semanticdb")

      assert(!fileResult.ok)
      assertEquals(fileRunner.calls, Nil)
      assert(fileResult.error.exists(_.contains("Workspace is not a directory")))
    finally Files.deleteIfExists(file)

  test("semantic_effect_summary validates file before invoking process"):
    withScalaWorkspace { (workspace, file) =>
      val validRunner = RecordingRunner(effectSummaryPayload(methods = Nil))
      val validResult = SemanticScalaCli(Path.of("semantic-scala"), validRunner).semanticEffectSummary(workspace, file)
      assert(validResult.ok)

      val invalidCases = List(
        "" -> "Invalid file: expected non-empty relative .scala path",
        "../outside.scala" -> "File escapes workspace",
        "src/main/scala/example" -> "File is not a regular file",
        "src/main/scala/example/Missing.scala" -> "File does not exist",
        "src/main/scala/example/notes.txt" -> "Invalid file: expected .scala source file"
      )

      Files.writeString(workspace.resolve("src/main/scala/example/notes.txt"), "not scala")

      invalidCases.foreach { case (candidate, expectedMessage) =>
        val runner = RecordingRunner(effectSummaryPayload(methods = Nil))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticEffectSummary(workspace, candidate)

        assert(!result.ok, clue(candidate))
        assertEquals(runner.calls, Nil, clue(candidate))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }

      val absoluteRunner = RecordingRunner(effectSummaryPayload(methods = Nil))
      val absoluteResult =
        SemanticScalaCli(Path.of("semantic-scala"), absoluteRunner).semanticEffectSummary(workspace, workspace.resolve(file).toString)

      assert(!absoluteResult.ok)
      assertEquals(absoluteRunner.calls, Nil)
      assert(absoluteResult.error.exists(_.contains("Invalid file: expected relative path")))
    }

  test("semantic_symbol_at validates file and position before invoking process"):
    withScalaWorkspace { (workspace, file) =>
      val validRunner = RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
      val validResult = SemanticScalaCli(Path.of("semantic-scala"), validRunner).semanticSymbolAt(workspace, file, line = 6, col = 16)
      assert(validResult.ok)

      val invalidFiles = List(
        "" -> "Invalid file: expected non-empty relative .scala path",
        "../outside.scala" -> "File escapes workspace",
        "src/main/scala/example" -> "File is not a regular file",
        "src/main/scala/example/Missing.scala" -> "File does not exist",
        "src/main/scala/example/notes.txt" -> "Invalid file: expected .scala source file"
      )

      Files.writeString(workspace.resolve("src/main/scala/example/notes.txt"), "not scala")

      invalidFiles.foreach { case (candidate, expectedMessage) =>
        val runner = RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticSymbolAt(workspace, candidate, line = 6, col = 16)

        assert(!result.ok, clue(candidate))
        assertEquals(runner.calls, Nil, clue(candidate))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }

      val absoluteRunner = RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
      val absoluteResult =
        SemanticScalaCli(Path.of("semantic-scala"), absoluteRunner).semanticSymbolAt(workspace, workspace.resolve(file).toString, line = 6, col = 16)

      assert(!absoluteResult.ok)
      assertEquals(absoluteRunner.calls, Nil)
      assert(absoluteResult.error.exists(_.contains("Invalid file: expected relative path")))

      val invalidPositions = List(
        (0, 1, "Invalid line: expected positive integer"),
        (1, 0, "Invalid col: expected positive integer")
      )

      invalidPositions.foreach { case (line, col, expectedMessage) =>
        val runner = RecordingRunner(symbolAtPayload(symbol = None, displayName = None))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticSymbolAt(workspace, file, line = line, col = col)

        assert(!result.ok, clue((line, col)))
        assertEquals(runner.calls, Nil, clue((line, col)))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }
    }

  test("semantic_symbols validates semanticdb file before invoking process"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val validRunner = RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = Nil))
      val validResult = SemanticScalaCli(Path.of("semantic-scala"), validRunner).semanticSymbols(workspace, semanticdb)
      assert(validResult.ok)

      val invalidCases = List(
        "" -> "Invalid semanticdb: expected non-empty relative .semanticdb path",
        "../outside.semanticdb" -> "SemanticDB file escapes workspace",
        "semanticdb-fixtures/simple" -> "SemanticDB file is not a regular file",
        "semanticdb-fixtures/simple/Missing.scala.semanticdb" -> "SemanticDB file does not exist",
        "semanticdb-fixtures/simple/Main.scala" -> "Invalid semanticdb: expected .semanticdb file"
      )

      invalidCases.foreach { case (candidate, expectedMessage) =>
        val runner = RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = Nil))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticSymbols(workspace, candidate)

        assert(!result.ok, clue(candidate))
        assertEquals(runner.calls, Nil, clue(candidate))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }

      val absoluteRunner = RecordingRunner(symbolsPayload(uri = "simple/Main.scala", symbols = Nil))
      val absoluteResult =
        SemanticScalaCli(Path.of("semantic-scala"), absoluteRunner).semanticSymbols(workspace, workspace.resolve(semanticdb).toString)

      assert(!absoluteResult.ok)
      assertEquals(absoluteRunner.calls, Nil)
      assert(absoluteResult.error.exists(_.contains("Invalid semanticdb: expected relative path")))
    }

  test("semantic_reconcile_symbol validates source file, semanticdb file, and position before invoking process"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val validRunner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
      val validResult = SemanticScalaCli(Path.of("semantic-scala"), validRunner).semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = semanticdb)
      assert(validResult.ok)

      Files.writeString(workspace.resolve("src/main/scala/example/notes.txt"), "not scala")

      val invalidFiles = List(
        "" -> "Invalid file: expected non-empty relative .scala path",
        "../outside.scala" -> "File escapes workspace",
        "src/main/scala/example" -> "File is not a regular file",
        "src/main/scala/example/Missing.scala" -> "File does not exist",
        "src/main/scala/example/notes.txt" -> "Invalid file: expected .scala source file"
      )

      invalidFiles.foreach { case (candidate, expectedMessage) =>
        val runner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticReconcileSymbol(workspace, candidate, line = 6, col = 16, semanticdb = semanticdb)

        assert(!result.ok, clue(candidate))
        assertEquals(runner.calls, Nil, clue(candidate))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }

      val absoluteFileRunner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
      val absoluteFileResult =
        SemanticScalaCli(Path.of("semantic-scala"), absoluteFileRunner)
          .semanticReconcileSymbol(workspace, workspace.resolve(file).toString, line = 6, col = 16, semanticdb = semanticdb)

      assert(!absoluteFileResult.ok)
      assertEquals(absoluteFileRunner.calls, Nil)
      assert(absoluteFileResult.error.exists(_.contains("Invalid file: expected relative path")))

      val invalidSemanticdbs = List(
        "" -> "Invalid semanticdb: expected non-empty relative .semanticdb path",
        "../outside.semanticdb" -> "SemanticDB file escapes workspace",
        "semanticdb-fixtures/simple" -> "SemanticDB file is not a regular file",
        "semanticdb-fixtures/simple/Missing.scala.semanticdb" -> "SemanticDB file does not exist",
        "semanticdb-fixtures/simple/Main.scala" -> "Invalid semanticdb: expected .semanticdb file"
      )

      invalidSemanticdbs.foreach { case (candidate, expectedMessage) =>
        val runner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = candidate)

        assert(!result.ok, clue(candidate))
        assertEquals(runner.calls, Nil, clue(candidate))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }

      val absoluteSemanticdbRunner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
      val absoluteSemanticdbResult =
        SemanticScalaCli(Path.of("semantic-scala"), absoluteSemanticdbRunner)
          .semanticReconcileSymbol(workspace, file, line = 6, col = 16, semanticdb = workspace.resolve(semanticdb).toString)

      assert(!absoluteSemanticdbResult.ok)
      assertEquals(absoluteSemanticdbRunner.calls, Nil)
      assert(absoluteSemanticdbResult.error.exists(_.contains("Invalid semanticdb: expected relative path")))

      val invalidPositions = List(
        (0, 1, "Invalid line: expected positive integer"),
        (1, 0, "Invalid col: expected positive integer")
      )

      invalidPositions.foreach { case (line, col, expectedMessage) =>
        val runner = RecordingRunner(reconcilePayload(status = "ExactMatch"))
        val result = SemanticScalaCli(Path.of("semantic-scala"), runner).semanticReconcileSymbol(workspace, file, line = line, col = col, semanticdb = semanticdb)

        assert(!result.ok, clue((line, col)))
        assertEquals(runner.calls, Nil, clue((line, col)))
        assert(result.error.exists(_.contains(expectedMessage)), clue(result.error))
      }
    }

  private def successPayload(
    success: Boolean,
    schemaVersion: String = SemanticScalaCli.CompileSchemaVersion
  ): ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"$schemaVersion","success":$success,"diagnostics":[]}""",
      stderr = ""
    )

  private def testPayload(success: Boolean, failed: Int = 0): ProcessResult =
    val passed = if success then 1 else 0
    val failures =
      if failed == 0 then "[]"
      else """[{"severity":"error","message":"test failed","position":null}]"""
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"${SemanticScalaCli.TestSchemaVersion}","success":$success,"total":1,"passed":$passed,"failed":$failed,"failures":$failures}""",
      stderr = ""
    )

  private def effectSummaryPayload(methods: List[String]): ProcessResult =
    val methodsJson = methods
      .map { packageQualifiedName =>
        s"""{"name":"${packageQualifiedName.split('.').last}","range":null,"declaredReturnType":null,"inferredReturnType":null,"effectCategory":"unknown","confidence":"unknown","notes":[],"packageQualifiedName":"$packageQualifiedName"}"""
      }
      .mkString("[", ",", "]")
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"${SemanticScalaCli.EffectSummarySchemaVersion}","source":"src/main/scala/example/Main.scala","methods":$methodsJson}""",
      stderr = ""
    )

  private def symbolAtPayload(symbol: Option[String], displayName: Option[String]): ProcessResult =
    val symbolJson = symbol.fold("null")(value => s""""$value"""")
    val displayNameJson = displayName.fold("null")(value => s""""$value"""")
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"${SemanticScalaCli.SymbolAtSchemaVersion}","symbol":$symbolJson,"displayName":$displayNameJson,"range":null,"source":"src/main/scala/example/Main.scala"}""",
      stderr = ""
    )

  private def symbolsPayload(uri: String, symbols: List[String]): ProcessResult =
    val symbolsJson = symbols
      .map { symbol =>
        s"""{"symbol":"$symbol","displayName":"${symbol.split('/').lastOption.getOrElse(symbol)}","kind":"CLASS","language":"SCALA"}"""
      }
      .mkString("[", ",", "]")
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"${SemanticFileSummary.SchemaVersion}","uri":"$uri","symbols":$symbolsJson,"occurrences":[]}""",
      stderr = ""
    )

  private def reconcilePayload(
    status: String,
    semanticdbSymbol: String = "example/Main.add().",
    compilerSymbol: String = "example/Main.add()."
  ): ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout =
        s"""{"schemaVersion":"${ReconciliationResult.SchemaVersion}","file":"src/main/scala/example/Main.scala","queryPosition":{"startLine":5,"startCharacter":15,"endLine":5,"endCharacter":15},"result":{"semanticdbSymbol":"$semanticdbSymbol","compilerSymbol":"$compilerSymbol","displayName":"add","range":{"startLine":5,"startCharacter":6,"endLine":5,"endCharacter":9},"status":"$status"}}""",
      stderr = ""
    )

  private def pointEvidencePayload: ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"${PointEvidenceReport.SchemaVersion}","workspace":"/workspace","sourceFile":"/workspace/src/main/scala/example/Main.scala","position":{"line":2,"column":7,"encoding":"UTF-16"},"discovery":{"schemaVersion":"semantic-scala.semanticdb-for-source.v1","workspace":"/workspace","sourceFile":"/workspace/src/main/scala/example/Main.scala","sourceRelativePath":"src/main/scala/example/Main.scala","status":"Unavailable","semanticdbFiles":0,"parseableFiles":0,"unparseableFiles":0,"matches":[],"candidatesConsidered":0,"warnings":[],"errors":[]},"selection":{"status":"NotSelectedUnavailable","artifact":null,"reason":"SemanticDB artifact evidence was unavailable"},"livePoint":{"status":"Unresolved","result":{"schemaVersion":"semantic-scala.symbol-at-result.v1","symbol":null,"displayName":null,"range":null,"source":"/workspace/src/main/scala/example/Main.scala"},"reason":null},"reconciliation":{"status":"NotAttempted","result":null,"notAttemptedReason":"ArtifactEvidenceUnavailable","detail":"SemanticDB discovery status was Unavailable"}}""",
      stderr = ""
    )

  private def payloadBoolean(result: McpToolResult, field: String): Option[Boolean] =
    result.payload.flatMap(_.hcursor.downField(field).as[Boolean].toOption)

  private def payloadInt(result: McpToolResult, field: String): Option[Int] =
    result.payload.flatMap(_.hcursor.downField(field).as[Int].toOption)

  private def payloadString(result: McpToolResult, field: String): Option[String] =
    result.payload.flatMap(_.hcursor.downField(field).as[String].toOption)

  private def payloadNestedString(result: McpToolResult, path: List[String]): Option[String] =
    result.payload.flatMap { json =>
      path.foldLeft(json.hcursor: io.circe.ACursor)((cursor, field) => cursor.downField(field)).as[String].toOption
    }

  private def payloadStatus(result: McpToolResult): Option[String] =
    payloadNestedString(result, List("result", "status"))

  private def payloadMethods(result: McpToolResult): List[String] =
    result.payload
      .flatMap(_.hcursor.downField("methods").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("packageQualifiedName").as[String].toOption)

  private def payloadSymbols(result: McpToolResult): List[String] =
    result.payload
      .flatMap(_.hcursor.downField("symbols").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("symbol").as[String].toOption)

  private def withTempWorkspace(test: Path => Unit): Unit =
    val workspace = Files.createTempDirectory("semantic-scala-mcp")
    try test(workspace)
    finally deleteRecursively(workspace)

  private def withScalaWorkspace(test: (Path, String) => Unit): Unit =
    withTempWorkspace { workspace =>
      val file = "src/main/scala/example/Main.scala"
      val path = workspace.resolve(file)
      Files.createDirectories(path.getParent)
      Files.writeString(path, "package example\nobject Main\n")
      test(workspace, file)
    }

  private def withSemanticdbWorkspace(test: (Path, String) => Unit): Unit =
    withTempWorkspace { workspace =>
      val semanticdb = "semanticdb-fixtures/simple/Main.scala.semanticdb"
      val semanticdbPath = workspace.resolve(semanticdb)
      Files.createDirectories(semanticdbPath.getParent)
      Files.writeString(semanticdbPath, "fixture bytes")
      Files.writeString(workspace.resolve("semanticdb-fixtures/simple/Main.scala"), "package example\nobject Main\n")
      test(workspace, semanticdb)
    }

  private def withReconcileWorkspace(test: (Path, String, String) => Unit): Unit =
    withTempWorkspace { workspace =>
      val file = "src/main/scala/example/Main.scala"
      val filePath = workspace.resolve(file)
      Files.createDirectories(filePath.getParent)
      Files.writeString(filePath, "package example\nobject Main\n")

      val semanticdb = "semanticdb-fixtures/simple/Main.scala.semanticdb"
      val semanticdbPath = workspace.resolve(semanticdb)
      Files.createDirectories(semanticdbPath.getParent)
      Files.writeString(semanticdbPath, "fixture bytes")
      Files.writeString(workspace.resolve("semanticdb-fixtures/simple/Main.scala"), "package example\nobject Main\n")

      test(workspace, file, semanticdb)
    }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      Files
        .walk(path)
        .iterator()
        .asScala
        .toList
        .sortBy(_.getNameCount)(Ordering.Int.reverse)
        .foreach(Files.deleteIfExists)

final class RecordingRunner(response: ProcessResult) extends ProcessRunner:
  var calls: List[(List[String], Path)] = Nil

  override def run(command: List[String], cwd: Path): ProcessResult =
    calls = calls :+ (command, cwd)
    response
