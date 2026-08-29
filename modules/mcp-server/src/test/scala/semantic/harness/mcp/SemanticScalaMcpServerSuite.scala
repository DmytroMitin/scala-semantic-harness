package semantic.harness.mcp

import java.nio.file.Files
import java.nio.file.Path

import io.circe.Json
import io.circe.parser.parse
import semantic.harness.reconciliation.ReconciliationResult
import semantic.harness.reconciliation.PointEvidenceReport
import semantic.harness.reconciliation.ReconciliationResultV2
import semantic.harness.reconciliation.PointEvidenceReportV2
import semantic.harness.reconciliation.PointEvidenceReportV3
import semantic.harness.reconciliation.PointEvidenceReportV4
import semantic.harness.semanticdb_reader.SemanticFileSummary
import scala.jdk.CollectionConverters.*

class SemanticScalaMcpServerSuite extends munit.FunSuite:
  test("initialize advertises tools capability"):
    val server = serverWith(successPayload(success = true))

    val response = responseJson(
      server,
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    )

    assertEquals(response.hcursor.downField("jsonrpc").as[String].toOption, Some("2.0"))
    assertEquals(response.hcursor.downField("id").as[Int].toOption, Some(1))
    assertEquals(response.hcursor.downField("result").downField("protocolVersion").as[String].toOption, Some("2025-06-18"))
    assertEquals(response.hcursor.downField("result").downField("capabilities").downField("tools").downField("listChanged").as[Boolean].toOption, Some(false))

  test("initialized notification produces no stdout message"):
    val server = serverWith(successPayload(success = true))

    assertEquals(server.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), None)

  test("tools/list exposes exactly build-oracle tools and semantic microscope tools with schemas"):
    val server = serverWith(successPayload(success = true))

    val response = responseJson(server, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
    val tools = response.hcursor.downField("result").downField("tools").values.getOrElse(Vector.empty)

    assertEquals(
      tools.map(_.hcursor.downField("name").as[String].toOption),
      Vector(
        Some("semantic_compile"),
        Some("semantic_errors"),
        Some("semantic_test"),
        Some("semantic_effect_summary"),
        Some("semantic_symbol_at"),
        Some("semantic_symbols"),
        Some("semantic_reconcile_symbol"),
        Some("semantic_point_evidence")
      )
    )
    tools.filter { tool =>
      val name = tool.hcursor.downField("name").as[String].toOption
      !name.contains("semantic_effect_summary") &&
      !name.contains("semantic_symbol_at") &&
      !name.contains("semantic_symbols") &&
      !name.contains("semantic_reconcile_symbol") &&
      !name.contains("semantic_point_evidence")
    }.foreach { tool =>
      assertEquals(
        tool.hcursor.downField("inputSchema").downField("required").as[List[String]].toOption,
        Some(List("workspace"))
      )
      assertEquals(
        tool.hcursor.downField("inputSchema").downField("properties").downField("workspace").downField("type").as[String].toOption,
        Some("string")
      )
      assertEquals(
        tool.hcursor.downField("inputSchema").downField("properties").downField("sbtProject").downField("type").as[String].toOption,
        Some("string")
      )
      assertEquals(
        tool.hcursor.downField("inputSchema").downField("properties").downField("sbtJavaHome").downField("type").as[String].toOption,
        Some("string")
      )
    }

    tools.drop(3).filterNot(
      _.hcursor.downField("name").as[String].toOption.contains("semantic_point_evidence")
    ).foreach { tool =>
      assertEquals(
        tool.hcursor.downField("inputSchema").downField("properties").downField("sbtProject").focus,
        None,
        clue(tool)
      )
      assertEquals(
        tool.hcursor.downField("inputSchema").downField("properties").downField("sbtJavaHome").focus,
        None,
        clue(tool)
      )
    }

    val effectSummary = tools.find(_.hcursor.downField("name").as[String].toOption.contains("semantic_effect_summary")).getOrElse(fail("Missing semantic_effect_summary"))
    assertEquals(
      effectSummary.hcursor.downField("inputSchema").downField("required").as[List[String]].toOption,
      Some(List("workspace", "file"))
    )
    assertEquals(
      effectSummary.hcursor.downField("inputSchema").downField("properties").downField("workspace").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      effectSummary.hcursor.downField("inputSchema").downField("properties").downField("file").downField("type").as[String].toOption,
      Some("string")
    )

    val symbolAt = tools.find(_.hcursor.downField("name").as[String].toOption.contains("semantic_symbol_at")).getOrElse(fail("Missing semantic_symbol_at"))
    assertEquals(
      symbolAt.hcursor.downField("inputSchema").downField("required").as[List[String]].toOption,
      Some(List("workspace", "file", "line", "col"))
    )
    assertEquals(
      symbolAt.hcursor.downField("inputSchema").downField("properties").downField("workspace").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      symbolAt.hcursor.downField("inputSchema").downField("properties").downField("file").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      symbolAt.hcursor.downField("inputSchema").downField("properties").downField("line").downField("type").as[String].toOption,
      Some("integer")
    )
    assertEquals(
      symbolAt.hcursor.downField("inputSchema").downField("properties").downField("col").downField("type").as[String].toOption,
      Some("integer")
    )

    val symbols = tools.find(_.hcursor.downField("name").as[String].toOption.contains("semantic_symbols")).getOrElse(fail("Missing semantic_symbols"))
    assertEquals(
      symbols.hcursor.downField("inputSchema").downField("required").as[List[String]].toOption,
      Some(List("workspace", "semanticdb"))
    )
    assertEquals(
      symbols.hcursor.downField("inputSchema").downField("properties").downField("workspace").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      symbols.hcursor.downField("inputSchema").downField("properties").downField("semanticdb").downField("type").as[String].toOption,
      Some("string")
    )

    val reconcile = tools.find(_.hcursor.downField("name").as[String].toOption.contains("semantic_reconcile_symbol")).getOrElse(fail("Missing semantic_reconcile_symbol"))
    assertEquals(
      reconcile.hcursor.downField("inputSchema").downField("required").as[List[String]].toOption,
      Some(List("workspace", "file", "line", "col", "semanticdb"))
    )
    assertEquals(
      reconcile.hcursor.downField("inputSchema").downField("properties").downField("workspace").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      reconcile.hcursor.downField("inputSchema").downField("properties").downField("file").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      reconcile.hcursor.downField("inputSchema").downField("properties").downField("line").downField("type").as[String].toOption,
      Some("integer")
    )
    assertEquals(
      reconcile.hcursor.downField("inputSchema").downField("properties").downField("col").downField("type").as[String].toOption,
      Some("integer")
    )
    assertEquals(
      reconcile.hcursor.downField("inputSchema").downField("properties").downField("semanticdb").downField("type").as[String].toOption,
      Some("string")
    )

    val pointEvidence = tools.find(_.hcursor.downField("name").as[String].toOption.contains("semantic_point_evidence")).getOrElse(fail("Missing semantic_point_evidence"))
    assertEquals(
      pointEvidence.hcursor.downField("inputSchema").downField("required").as[List[String]].toOption,
      Some(List("workspace", "file", "line", "col"))
    )
    assertEquals(
      pointEvidence.hcursor.downField("inputSchema").downField("properties").downField("sbtProject").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      pointEvidence.hcursor.downField("inputSchema").downField("properties").downField("sbtJavaHome").downField("type").as[String].toOption,
      Some("string")
    )
    assertEquals(
      pointEvidence.hcursor.downField("inputSchema").downField("properties").downField("sbtScalaVersion").downField("type").as[String].toOption,
      Some("string")
    )

  test("tools/call semantic_point_evidence returns the CLI report through the standard wrapper"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(pointEvidencePayload)
      val response = callSemanticPointEvidence(server, workspace, file, line = 2, col = 7)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("schemaVersion").as[String].toOption, Some(PointEvidenceReportV2.SchemaVersion))
      assertEquals(
        wrapper.hcursor.downField("payload").downField("selection").downField("status").as[String].toOption,
        Some("NotSelectedUnavailable")
      )
    }

  test("tools/call semantic_point_evidence dynamically requires v4 and forwards axis for selected target context"):
    withScalaWorkspace { (workspace, file) =>
      val response = responseJson(
        serverWith(successPayload(success = true, schemaVersion = PointEvidenceReportV4.SchemaVersion)),
        s"""{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"semantic_point_evidence","arguments":{"workspace":"${workspace.toString}","file":"$file","line":2,"col":7,"sbtProject":"kernelJVM","sbtScalaVersion":"3.3.7","sbtJavaHome":"/opt/jdk"}}}"""
      )
      val wrapper = structuredContent(response)
      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("schemaVersion").as[String].toOption, Some(PointEvidenceReportV4.SchemaVersion))
      assertEquals(
        wrapper.hcursor.downField("command").as[List[String]].toOption.map(_.takeRight(5)),
        Some(List("--sbt-scala-version", "3.3.7", "--sbt-java-home", "<sbt-java-home>", "--json"))
      )

      val invalid = responseJson(
        serverWith(pointEvidencePayload),
        s"""{"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"semantic_point_evidence","arguments":{"workspace":"${workspace.toString}","file":"$file","line":2,"col":7,"sbtJavaHome":"/opt/jdk"}}}"""
      )
      assertEquals(invalid.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))

      val invalidAxis = responseJson(
        serverWith(pointEvidencePayload),
        s"""{"jsonrpc":"2.0","id":24,"method":"tools/call","params":{"name":"semantic_point_evidence","arguments":{"workspace":"${workspace.toString}","file":"$file","line":2,"col":7,"sbtScalaVersion":"3.3.7"}}}"""
      )
      assertEquals(invalidAxis.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
    }

  test("tools/call semantic_point_evidence rejects incomplete transport input"):
    withTempWorkspace { workspace =>
      val response = responseJson(
        serverWith(pointEvidencePayload),
        s"""{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"semantic_point_evidence","arguments":{"workspace":"${workspace.toString}","line":1,"col":1}}}"""
      )
      assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
    }

  test("tools/call semantic_point_evidence carries adapter schema mismatch as tool error"):
    withScalaWorkspace { (workspace, file) =>
      val response = callSemanticPointEvidence(serverWith(successPayload(success = true)), workspace, file, 2, 7)
      val wrapper = structuredContent(response)
      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains(PointEvidenceReportV2.SchemaVersion)))
    }

  test("tools/call returns wrapper JSON for successful compile payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(successPayload(success = true))

      val response = callSemanticCompile(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.CompileSchemaVersion))
    }

  test("build tools admit one absolute sbtJavaHome and reject unsafe transport values before launch"):
    withTempWorkspace { workspace =>
      val selected = Path.of("/private/task166-selected-jdk")
      val runner = RecordingRunner(successPayload(success = true))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))
      val accepted = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":166,"method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":"${workspace.toString}","sbtJavaHome":"${selected.toString}"}}}"""
      )
      val acceptedWrapper = structuredContent(accepted)

      assertEquals(
        runner.calls.map(_._1),
        List(List("semantic-scala", "compile", "--sbt-java-home", selected.toString, "--json"))
      )
      assertEquals(
        acceptedWrapper.hcursor.downField("command").as[List[String]].toOption,
        Some(List("semantic-scala", "compile", "--sbt-java-home", "<sbt-java-home>", "--json"))
      )

      val invalid = List(
        "\"relative-jdk\"",
        "17"
      )
      invalid.zipWithIndex.foreach { case (value, index) =>
        val response = responseJson(
          server,
          s"""{"jsonrpc":"2.0","id":${167 + index},"method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":"${workspace.toString}","sbtJavaHome":$value}}}"""
        )
        assertEquals(
          response.hcursor.downField("error").downField("code").as[Int].toOption,
          Some(-32602)
        )
      }
      assertEquals(runner.calls.size, 1)
    }

  test("tools/call returns ok true for compile failure payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(successPayload(success = false))

      val response = callSemanticCompile(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(false))
    }

  test("tools/call semantic_errors returns wrapper JSON for successful errors payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))

      val response = callSemanticErrors(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.ErrorsSchemaVersion))
    }

  test("tools/call semantic_errors returns ok true for compile failure payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(successPayload(success = false, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))

      val response = callSemanticErrors(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.ErrorsSchemaVersion))
    }

  test("tools/call semantic_test returns wrapper JSON for successful test payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(testPayload(success = true))

      val response = callSemanticTest(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.TestSchemaVersion))
    }

  test("tools/call semantic_test returns ok true for test failure payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(testPayload(success = false, failed = 1))

      val response = callSemanticTest(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("success").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("payload").downField("failed").as[Int].toOption, Some(1))
      assertEquals(wrapper.hcursor.downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.TestSchemaVersion))
    }

  test("tools/call semantic_effect_summary returns wrapper JSON for valid summary"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(effectSummaryPayload(List("example.UserRepo.find", "example.Main.getName")))

      val response = callSemanticEffectSummary(server, workspace, file)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.EffectSummarySchemaVersion))
      assertEquals(effectMethods(wrapper), List("example.UserRepo.find", "example.Main.getName"))
    }

  test("tools/call semantic_symbol_at returns wrapper JSON for valid point query"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(symbolAtPayload(symbol = Some("example/Main.add()."), displayName = Some("add")))

      val response = callSemanticSymbolAt(server, workspace, file, line = 6, col = 16)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.SymbolAtSchemaVersion))
      assertEquals(wrapper.hcursor.downField("payload").downField("symbol").as[String].toOption, Some("example/Main.add()."))
      assertEquals(wrapper.hcursor.downField("payload").downField("displayName").as[String].toOption, Some("add"))
    }

  test("tools/call semantic_symbol_at returns wrapper JSON for valid no-symbol point"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(symbolAtPayload(symbol = None, displayName = None))

      val response = callSemanticSymbolAt(server, workspace, file, line = 6, col = 1)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.SymbolAtSchemaVersion))
      assertEquals(wrapper.hcursor.downField("payload").downField("symbol").focus, Some(Json.Null))
      assertEquals(wrapper.hcursor.downField("payload").downField("displayName").focus, Some(Json.Null))
    }

  test("tools/call semantic_symbols returns wrapper JSON for valid SemanticDB summary"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val server = serverWith(symbolsPayload(uri = "simple/Main.scala", symbols = List("example/Main#")))

      val response = callSemanticSymbols(server, workspace, semanticdb)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.SymbolsSchemaVersion))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(SemanticFileSummary.SchemaVersion))
      assertEquals(wrapper.hcursor.downField("payload").downField("uri").as[String].toOption, Some("simple/Main.scala"))
      assertEquals(symbols(wrapper), List("example/Main#"))
    }

  test("tools/call semantic_reconcile_symbol returns wrapper JSON for valid reconciliation"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val server = serverWith(reconcilePayload(status = "ExactMatch"))

      val response = callSemanticReconcileSymbol(server, workspace, file, line = 6, col = 16, semanticdb = semanticdb)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("schemaVersion").as[String].toOption, Some(SemanticScalaCli.ReconcileSymbolSchemaVersion))
      assertEquals(wrapper.hcursor.downField("payload").downField("schemaVersion").as[String].toOption, Some(ReconciliationResultV2.SchemaVersion))
      assertEquals(wrapper.hcursor.downField("payload").downField("file").as[String].toOption, Some("src/main/scala/example/Main.scala"))
      assertEquals(reconciliationStatus(wrapper), Some("ExactMatch"))
    }

  test("tools/call semantic_reconcile_symbol treats non-exact status as successful domain evidence"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val server = serverWith(reconcilePayload(status = "NoMatch"))

      val response = callSemanticReconcileSymbol(server, workspace, file, line = 6, col = 16, semanticdb = semanticdb)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(reconciliationStatus(wrapper), Some("NoMatch"))
    }

  test("tools/call preserves stderr outside payload"):
    withTempWorkspace { workspace =>
      val server = serverWith(successPayload(success = true).copy(stderr = "WARNING..."))

      val response = callSemanticCompile(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(wrapper.hcursor.downField("stderr").as[String].toOption, Some("WARNING..."))
      assertEquals(wrapper.hcursor.downField("payload").downField("stderr").as[String].toOption, None)
    }

  test("tools/call returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(successPayload(success = true))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticCompile(server, missingWorkspace)
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_errors returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-errors-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(successPayload(success = true, schemaVersion = SemanticScalaCli.ErrorsSchemaVersion))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticErrors(server, missingWorkspace)
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_test returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-test-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(testPayload(success = true))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticTest(server, missingWorkspace)
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_effect_summary returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-effect-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(effectSummaryPayload(Nil))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticEffectSummary(server, missingWorkspace, "src/main/scala/example/Main.scala")
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_symbol_at returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-symbol-at-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(symbolAtPayload(None, None))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticSymbolAt(server, missingWorkspace, "src/main/scala/example/Main.scala", line = 6, col = 16)
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_symbols returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-symbols-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(symbolsPayload("simple/Main.scala", Nil))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticSymbols(server, missingWorkspace, "semanticdb-fixtures/simple/Main.scala.semanticdb")
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_reconcile_symbol returns wrapper ok false for invalid workspace"):
    val missingWorkspace = Path.of("target", "missing-mcp-reconcile-workspace").toAbsolutePath.normalize()
    val runner = RecordingRunner(reconcilePayload("ExactMatch"))
    val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

    val response = callSemanticReconcileSymbol(server, missingWorkspace, "src/main/scala/example/Main.scala", line = 6, col = 16, "semanticdb-fixtures/simple/Main.scala.semanticdb")
    val wrapper = structuredContent(response)

    assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
    assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
    assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("Workspace does not exist")))
    assertEquals(runner.calls, Nil)

  test("tools/call semantic_effect_summary returns wrapper ok false for invalid file"):
    withScalaWorkspace { (workspace, _) =>
      val runner = RecordingRunner(effectSummaryPayload(Nil))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = callSemanticEffectSummary(server, workspace, "../outside.scala")
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("File escapes workspace")))
      assertEquals(runner.calls, Nil)
    }

  test("tools/call semantic_symbol_at returns wrapper ok false for invalid file"):
    withScalaWorkspace { (workspace, _) =>
      val runner = RecordingRunner(symbolAtPayload(None, None))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = callSemanticSymbolAt(server, workspace, "../outside.scala", line = 6, col = 16)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("File escapes workspace")))
      assertEquals(runner.calls, Nil)
    }

  test("tools/call semantic_symbols returns wrapper ok false for invalid semanticdb file"):
    withSemanticdbWorkspace { (workspace, _) =>
      val runner = RecordingRunner(symbolsPayload("simple/Main.scala", Nil))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = callSemanticSymbols(server, workspace, "../outside.semanticdb")
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("SemanticDB file escapes workspace")))
      assertEquals(runner.calls, Nil)
    }

  test("tools/call semantic_reconcile_symbol returns wrapper ok false for invalid source file"):
    withReconcileWorkspace { (workspace, _, semanticdb) =>
      val runner = RecordingRunner(reconcilePayload("ExactMatch"))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = callSemanticReconcileSymbol(server, workspace, "../outside.scala", line = 6, col = 16, semanticdb = semanticdb)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("File escapes workspace")))
      assertEquals(runner.calls, Nil)
    }

  test("tools/call semantic_reconcile_symbol returns wrapper ok false for invalid semanticdb file"):
    withReconcileWorkspace { (workspace, file, _) =>
      val runner = RecordingRunner(reconcilePayload("ExactMatch"))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = callSemanticReconcileSymbol(server, workspace, file, line = 6, col = 16, semanticdb = "../outside.semanticdb")
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("SemanticDB file escapes workspace")))
      assertEquals(runner.calls, Nil)
    }

  test("tools/call returns protocol error for missing workspace"):
    val server = serverWith(successPayload(success = true))

    val response = responseJson(
      server,
      """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"semantic_compile","arguments":{}}}"""
    )

    assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
    assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("workspace")))

  test("tools/call returns protocol error for non-string workspace"):
    val server = serverWith(successPayload(success = true))

    val response = responseJson(
      server,
      """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":42}}}"""
    )

    assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
    assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("workspace")))

  test("tools/call build oracles accept one optional safe sbt project"):
    withTempWorkspace { workspace =>
      val runner = RecordingRunner(successPayload(success = true))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":"${workspace.toString}","sbtProject":"core2_13"}}}"""
      )
      val wrapper = structuredContent(response)

      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(true))
      assertEquals(
        runner.calls.map(_._1),
        List(List("semantic-scala", "compile", "--sbt-project", "core2_13", "--json"))
      )
    }

  test("tools/call build oracles reject invalid sbt projects at the adapter boundary"):
    withTempWorkspace { workspace =>
      val runner = RecordingRunner(successPayload(success = true))
      val server = SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), runner))

      val response = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":32,"method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":"${workspace.toString}","sbtProject":"core2_13;test"}}}"""
      )

      assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("sbt project ID must start with a letter")))
      assertEquals(runner.calls, Nil)
    }

  test("tools/call semantic_effect_summary returns protocol error for missing file"):
    withTempWorkspace { workspace =>
      val server = serverWith(effectSummaryPayload(Nil))

      val response = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"semantic_effect_summary","arguments":{"workspace":"${workspace.toString}"}}}"""
      )

      assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("file")))
    }

  test("tools/call semantic_effect_summary returns protocol error for non-string file"):
    withTempWorkspace { workspace =>
      val server = serverWith(effectSummaryPayload(Nil))

      val response = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"semantic_effect_summary","arguments":{"workspace":"${workspace.toString}","file":42}}}"""
      )

      assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("file")))
    }

  test("tools/call semantic_symbol_at returns protocol error for missing or non-string file"):
    withTempWorkspace { workspace =>
      val server = serverWith(symbolAtPayload(None, None))

      val missing = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"semantic_symbol_at","arguments":{"workspace":"${workspace.toString}","line":6,"col":16}}}"""
      )
      assertEquals(missing.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(missing.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("file")))

      val nonString = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"semantic_symbol_at","arguments":{"workspace":"${workspace.toString}","file":42,"line":6,"col":16}}}"""
      )
      assertEquals(nonString.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(nonString.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("file")))
    }

  test("tools/call semantic_symbols returns protocol error for missing or non-string semanticdb"):
    withTempWorkspace { workspace =>
      val server = serverWith(symbolsPayload("simple/Main.scala", Nil))

      val missing = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"semantic_symbols","arguments":{"workspace":"${workspace.toString}"}}}"""
      )
      assertEquals(missing.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(missing.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("semanticdb")))

      val nonString = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"semantic_symbols","arguments":{"workspace":"${workspace.toString}","semanticdb":42}}}"""
      )
      assertEquals(nonString.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(nonString.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("semanticdb")))
    }

  test("tools/call semantic_reconcile_symbol returns protocol error for missing or non-string file and semanticdb"):
    withTempWorkspace { workspace =>
      val server = serverWith(reconcilePayload("ExactMatch"))

      val missingFile = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":26,"method":"tools/call","params":{"name":"semantic_reconcile_symbol","arguments":{"workspace":"${workspace.toString}","line":6,"col":16,"semanticdb":"semanticdb-fixtures/simple/Main.scala.semanticdb"}}}"""
      )
      assertEquals(missingFile.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(missingFile.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("file")))

      val nonStringFile = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":27,"method":"tools/call","params":{"name":"semantic_reconcile_symbol","arguments":{"workspace":"${workspace.toString}","file":42,"line":6,"col":16,"semanticdb":"semanticdb-fixtures/simple/Main.scala.semanticdb"}}}"""
      )
      assertEquals(nonStringFile.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(nonStringFile.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("file")))

      val missingSemanticdb = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":28,"method":"tools/call","params":{"name":"semantic_reconcile_symbol","arguments":{"workspace":"${workspace.toString}","file":"src/main/scala/example/Main.scala","line":6,"col":16}}}"""
      )
      assertEquals(missingSemanticdb.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(missingSemanticdb.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("semanticdb")))

      val nonStringSemanticdb = responseJson(
        server,
        s"""{"jsonrpc":"2.0","id":29,"method":"tools/call","params":{"name":"semantic_reconcile_symbol","arguments":{"workspace":"${workspace.toString}","file":"src/main/scala/example/Main.scala","line":6,"col":16,"semanticdb":42}}}"""
      )
      assertEquals(nonStringSemanticdb.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602))
      assert(nonStringSemanticdb.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains("semanticdb")))
    }

  test("tools/call semantic_symbol_at returns protocol error for missing, non-integer, or non-positive line and col"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(symbolAtPayload(None, None))

      val cases = List(
        s"""{"workspace":"${workspace.toString}","file":"$file","col":16}""" -> "line",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":"6","col":16}""" -> "line",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":0,"col":16}""" -> "line",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":6}""" -> "col",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":6,"col":"16"}""" -> "col",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":6,"col":0}""" -> "col"
      )

      cases.zipWithIndex.foreach { case ((arguments, expectedField), index) =>
        val response = responseJson(
          server,
          s"""{"jsonrpc":"2.0","id":${20 + index},"method":"tools/call","params":{"name":"semantic_symbol_at","arguments":$arguments}}"""
        )

        assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602), clue(arguments))
        assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains(expectedField)), clue(response))
      }
    }

  test("tools/call semantic_reconcile_symbol returns protocol error for missing, non-integer, or non-positive line and col"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val server = serverWith(reconcilePayload("ExactMatch"))

      val cases = List(
        s"""{"workspace":"${workspace.toString}","file":"$file","col":16,"semanticdb":"$semanticdb"}""" -> "line",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":"6","col":16,"semanticdb":"$semanticdb"}""" -> "line",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":0,"col":16,"semanticdb":"$semanticdb"}""" -> "line",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":6,"semanticdb":"$semanticdb"}""" -> "col",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":6,"col":"16","semanticdb":"$semanticdb"}""" -> "col",
        s"""{"workspace":"${workspace.toString}","file":"$file","line":6,"col":0,"semanticdb":"$semanticdb"}""" -> "col"
      )

      cases.zipWithIndex.foreach { case ((arguments, expectedField), index) =>
        val response = responseJson(
          server,
          s"""{"jsonrpc":"2.0","id":${40 + index},"method":"tools/call","params":{"name":"semantic_reconcile_symbol","arguments":$arguments}}"""
        )

        assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32602), clue(arguments))
        assert(response.hcursor.downField("error").downField("message").as[String].toOption.exists(_.contains(expectedField)), clue(response))
      }
    }

  test("tools/call carries adapter runtime failure as tool error"):
    withTempWorkspace { workspace =>
      val server = serverWith(ProcessResult(exitCode = 1, stdout = "", stderr = "boom"))

      val response = callSemanticCompile(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assertEquals(wrapper.hcursor.downField("exitCode").as[Int].toOption, Some(1))
      assertEquals(wrapper.hcursor.downField("stderr").as[String].toOption, Some(""))
    }

  test("tools/call carries adapter schema mismatch as tool error"):
    withTempWorkspace { workspace =>
      val server = serverWith(ProcessResult(exitCode = 0, stdout = """{"schemaVersion":"wrong","success":true,"diagnostics":[]}""", stderr = ""))

      val response = callSemanticCompile(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("tools/call semantic_errors carries adapter schema mismatch as tool error"):
    withTempWorkspace { workspace =>
      val server = serverWith(ProcessResult(exitCode = 0, stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","success":true,"diagnostics":[]}""", stderr = ""))

      val response = callSemanticErrors(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("tools/call semantic_test carries adapter schema mismatch as tool error"):
    withTempWorkspace { workspace =>
      val server = serverWith(ProcessResult(exitCode = 0, stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","success":true,"total":1,"passed":1,"failed":0,"failures":[]}""", stderr = ""))

      val response = callSemanticTest(server, workspace)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("tools/call semantic_effect_summary carries adapter schema mismatch as tool error"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(ProcessResult(exitCode = 0, stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","source":"$file","methods":[]}""", stderr = ""))

      val response = callSemanticEffectSummary(server, workspace, file)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("tools/call semantic_symbol_at carries adapter schema mismatch as tool error"):
    withScalaWorkspace { (workspace, file) =>
      val server = serverWith(ProcessResult(exitCode = 0, stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","symbol":null,"displayName":null,"range":null,"source":"$file"}""", stderr = ""))

      val response = callSemanticSymbolAt(server, workspace, file, line = 6, col = 16)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("tools/call semantic_symbols carries adapter schema mismatch as tool error"):
    withSemanticdbWorkspace { (workspace, semanticdb) =>
      val server = serverWith(ProcessResult(exitCode = 0, stdout = s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","uri":"simple/Main.scala","symbols":[],"occurrences":[]}""", stderr = ""))

      val response = callSemanticSymbols(server, workspace, semanticdb)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("tools/call semantic_reconcile_symbol carries adapter schema mismatch as tool error"):
    withReconcileWorkspace { (workspace, file, semanticdb) =>
      val stdout =
        s"""{"schemaVersion":"${SemanticScalaCli.CompileSchemaVersion}","file":"$file","queryPosition":{"startLine":5,"startCharacter":15,"endLine":5,"endCharacter":15},"result":{"semanticdbSymbol":null,"compilerSymbol":null,"displayName":null,"range":null,"status":"NoMatch"}}"""
      val server = serverWith(ProcessResult(exitCode = 0, stdout = stdout, stderr = ""))

      val response = callSemanticReconcileSymbol(server, workspace, file, line = 6, col = 16, semanticdb = semanticdb)
      val wrapper = structuredContent(response)

      assertEquals(response.hcursor.downField("result").downField("isError").as[Boolean].toOption, Some(true))
      assertEquals(wrapper.hcursor.downField("ok").as[Boolean].toOption, Some(false))
      assert(wrapper.hcursor.downField("error").as[String].toOption.exists(_.contains("schemaVersion mismatch")))
    }

  test("malformed JSON-RPC input returns parse error"):
    val server = serverWith(successPayload(success = true))

    val response = responseJson(server, "not json")

    assertEquals(response.hcursor.downField("error").downField("code").as[Int].toOption, Some(-32700))

  private def serverWith(result: ProcessResult): SemanticScalaMcpServer =
    SemanticScalaMcpServer(SemanticScalaCli(Path.of("semantic-scala"), RecordingRunner(result)))

  private def callSemanticCompile(server: SemanticScalaMcpServer, workspace: Path): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"semantic_compile","arguments":{"workspace":"${workspace.toString}"}}}"""
    )

  private def callSemanticErrors(server: SemanticScalaMcpServer, workspace: Path): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"semantic_errors","arguments":{"workspace":"${workspace.toString}"}}}"""
    )

  private def callSemanticTest(server: SemanticScalaMcpServer, workspace: Path): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"semantic_test","arguments":{"workspace":"${workspace.toString}"}}}"""
    )

  private def callSemanticEffectSummary(server: SemanticScalaMcpServer, workspace: Path, file: String): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"semantic_effect_summary","arguments":{"workspace":"${workspace.toString}","file":"$file"}}}"""
    )

  private def callSemanticSymbolAt(server: SemanticScalaMcpServer, workspace: Path, file: String, line: Int, col: Int): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"semantic_symbol_at","arguments":{"workspace":"${workspace.toString}","file":"$file","line":$line,"col":$col}}}"""
    )

  private def callSemanticSymbols(server: SemanticScalaMcpServer, workspace: Path, semanticdb: String): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"semantic_symbols","arguments":{"workspace":"${workspace.toString}","semanticdb":"$semanticdb"}}}"""
    )

  private def callSemanticReconcileSymbol(server: SemanticScalaMcpServer, workspace: Path, file: String, line: Int, col: Int, semanticdb: String): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"semantic_reconcile_symbol","arguments":{"workspace":"${workspace.toString}","file":"$file","line":$line,"col":$col,"semanticdb":"$semanticdb"}}}"""
    )

  private def callSemanticPointEvidence(server: SemanticScalaMcpServer, workspace: Path, file: String, line: Int, col: Int): Json =
    responseJson(
      server,
      s"""{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"semantic_point_evidence","arguments":{"workspace":"${workspace.toString}","file":"$file","line":$line,"col":$col}}}"""
    )

  private def responseJson(server: SemanticScalaMcpServer, request: String): Json =
    val response = server.handleLine(request).getOrElse(fail("Expected JSON-RPC response"))
    parse(response).fold(error => fail(s"Invalid response JSON: ${error.message}"), identity)

  private def structuredContent(response: Json): Json =
    response.hcursor.downField("result").downField("structuredContent").focus.getOrElse(fail("Missing structuredContent"))

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

  private def reconcilePayload(status: String): ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout =
        s"""{"schemaVersion":"${ReconciliationResultV2.SchemaVersion}","file":"src/main/scala/example/Main.scala","semanticdb":"target/Main.scala.semanticdb","queryPosition":{"startLine":5,"startCharacter":15,"endLine":5,"endCharacter":15},"freshness":null,"outcome":{"status":"CompletedFresh","result":{"semanticdbSymbol":"example/Main.add().","compilerSymbol":"example/Main.add().","displayName":"add","range":{"startLine":5,"startCharacter":6,"endLine":5,"endCharacter":9},"status":"$status"},"qualificationReason":null,"notAttemptedReason":null}}""",
      stderr = ""
    )

  private def pointEvidencePayload: ProcessResult =
    ProcessResult(
      exitCode = 0,
      stdout = s"""{"schemaVersion":"${PointEvidenceReportV2.SchemaVersion}","workspace":"/workspace","sourceFile":"/workspace/src/main/scala/example/Main.scala","position":{"line":2,"column":7,"encoding":"UTF-16"},"discovery":{},"selection":{"status":"NotSelectedUnavailable"},"livePoint":{"status":"Unresolved"},"reconciliation":{"outcome":{"status":"NotAttempted"}}}""",
      stderr = ""
    )

  private def effectMethods(wrapper: Json): List[String] =
    wrapper.hcursor
      .downField("payload")
      .downField("methods")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("packageQualifiedName").as[String].toOption)

  private def symbols(wrapper: Json): List[String] =
    wrapper.hcursor
      .downField("payload")
      .downField("symbols")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("symbol").as[String].toOption)

  private def reconciliationStatus(wrapper: Json): Option[String] =
    wrapper.hcursor
      .downField("payload")
      .downField("outcome")
      .downField("result")
      .downField("status")
      .as[String]
      .toOption

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
