package semantic.harness.mcp

import java.nio.file.Path

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import semantic.harness.core.SbtProjectIdSyntax
import semantic.harness.sbt_runner.SbtScalaVersion
import scala.util.Try

final class SemanticScalaMcpServer(cli: SemanticScalaCli):
  import SemanticScalaMcpServer.*

  def handleLine(line: String): Option[String] =
    val trimmed = line.trim
    if trimmed.isEmpty then None
    else
      parse(trimmed) match
        case Left(_) =>
          Some(jsonRpcError(Json.Null, JsonRpcErrors.ParseError, "Parse error").noSpaces)
        case Right(json) =>
          handleMessage(json).map(_.noSpaces)

  def handleMessage(message: Json): Option[Json] =
    handleMessage(message, ProcessExecution.default)

  def handleMessage(message: Json, execution: ProcessExecution): Option[Json] =
    val cursor = message.hcursor
    val id = cursor.downField("id").focus.getOrElse(Json.Null)
    val isNotification = cursor.downField("id").focus.isEmpty

    cursor.downField("method").as[String] match
      case Left(_) =>
        Some(jsonRpcError(id, JsonRpcErrors.InvalidRequest, "Invalid request: missing method"))
      case Right(_) if isNotification =>
        None
      case Right("initialize") =>
        Some(jsonRpcResponse(id, initializeResult(message)))
      case Right("ping") =>
        Some(jsonRpcResponse(id, Json.obj()))
      case Right("tools/list") =>
        Some(jsonRpcResponse(id, Json.obj("tools" -> Json.arr(semanticCompileTool, semanticErrorsTool, semanticTestTool, semanticEffectSummaryTool, semanticSymbolAtTool, semanticSymbolsTool, semanticReconcileSymbolTool, semanticPointEvidenceTool))))
      case Right("tools/call") =>
        Some(handleToolCall(id, message, execution))
      case Right(_) =>
        Some(jsonRpcError(id, JsonRpcErrors.MethodNotFound, "Method not found"))

  private def handleToolCall(id: Json, message: Json, execution: ProcessExecution): Json =
    val params = message.hcursor.downField("params")
    params.downField("name").as[String] match
      case Right(SemanticCompileToolName) =>
        handleBuildToolCall(id, params, SemanticCompileToolName, (workspace, project, javaHome) => cli.semanticCompile(workspace, project, javaHome, execution))
      case Right(SemanticErrorsToolName) =>
        handleBuildToolCall(id, params, SemanticErrorsToolName, (workspace, project, javaHome) => cli.semanticErrors(workspace, project, javaHome, execution))
      case Right(SemanticTestToolName) =>
        handleBuildToolCall(id, params, SemanticTestToolName, (workspace, project, javaHome) => cli.semanticTest(workspace, project, javaHome, execution))
      case Right(SemanticEffectSummaryToolName) =>
        handleEffectSummaryToolCall(id, params, execution)
      case Right(SemanticSymbolAtToolName) =>
        handleSymbolAtToolCall(id, params, execution)
      case Right(SemanticSymbolsToolName) =>
        handleSymbolsToolCall(id, params, execution)
      case Right(SemanticReconcileSymbolToolName) =>
        handleReconcileSymbolToolCall(id, params, execution)
      case Right(SemanticPointEvidenceToolName) =>
        handlePointEvidenceToolCall(id, params, execution)
      case Right(_) =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, "Unknown tool")
      case Left(_) =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, "Missing tool name")

  private def handleBuildToolCall(
    id: Json,
    params: io.circe.ACursor,
    toolName: String,
    run: (Path, Option[String], Option[String]) => McpToolResult
  ): Json =
    params.downField("arguments").focus match
      case Some(arguments) =>
        buildArguments(arguments) match
          case Left(message) =>
            jsonRpcError(id, JsonRpcErrors.InvalidParams, message)
          case Right((workspace, sbtProject, sbtJavaHome)) =>
            jsonRpcResponse(id, toolResult(run(workspace, sbtProject, sbtJavaHome)))
      case None =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, s"Missing arguments for $toolName")

  private def buildArguments(
      arguments: Json
  ): Either[String, (Path, Option[String], Option[String])] =
    for
      workspace <- workspaceArgument(arguments)
      sbtProject <- optionalSbtProjectArgument(arguments)
      sbtJavaHome <- optionalSbtJavaHomeArgument(arguments)
    yield (workspace, sbtProject, sbtJavaHome)

  private def optionalSbtProjectArgument(arguments: Json): Either[String, Option[String]] =
    arguments.hcursor.downField("sbtProject").focus match
      case None => Right(None)
      case Some(value) =>
        value.asString match
          case None => Left("Invalid sbtProject: expected string field")
          case Some(text) =>
            SbtProjectIdSyntax
              .validate(text)
              .left
              .map(message => s"Invalid sbtProject: $message")
              .map(Some.apply)

  private def optionalSbtJavaHomeArgument(
      arguments: Json
  ): Either[String, Option[String]] =
    arguments.hcursor.downField("sbtJavaHome").focus match
      case None => Right(None)
      case Some(value) =>
        value.asString match
          case None => Left("Invalid sbtJavaHome: expected string field")
          case Some(text) =>
            Try(Path.of(text)).toEither
              .left
              .map(_ => "Invalid sbtJavaHome: expected an absolute directory")
              .flatMap(path =>
                Either.cond(
                  text.nonEmpty && path.isAbsolute,
                  Some(text),
                  "Invalid sbtJavaHome: expected an absolute directory"
                )
              )

  private def handleEffectSummaryToolCall(
    id: Json,
    params: io.circe.ACursor,
    execution: ProcessExecution
  ): Json =
    params.downField("arguments").focus match
      case Some(arguments) =>
        effectSummaryArguments(arguments) match
          case Left(message) =>
            jsonRpcError(id, JsonRpcErrors.InvalidParams, message)
          case Right((workspace, file)) =>
            jsonRpcResponse(id, toolResult(cli.semanticEffectSummary(workspace, file, execution)))
      case None =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, s"Missing arguments for $SemanticEffectSummaryToolName")

  private def handleSymbolAtToolCall(
    id: Json,
    params: io.circe.ACursor,
    execution: ProcessExecution
  ): Json =
    params.downField("arguments").focus match
      case Some(arguments) =>
        symbolAtArguments(arguments) match
          case Left(message) =>
            jsonRpcError(id, JsonRpcErrors.InvalidParams, message)
          case Right((workspace, file, line, col)) =>
            jsonRpcResponse(id, toolResult(cli.semanticSymbolAt(workspace, file, line, col, execution)))
      case None =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, s"Missing arguments for $SemanticSymbolAtToolName")

  private def handleSymbolsToolCall(
    id: Json,
    params: io.circe.ACursor,
    execution: ProcessExecution
  ): Json =
    params.downField("arguments").focus match
      case Some(arguments) =>
        symbolsArguments(arguments) match
          case Left(message) =>
            jsonRpcError(id, JsonRpcErrors.InvalidParams, message)
          case Right((workspace, semanticdb)) =>
            jsonRpcResponse(id, toolResult(cli.semanticSymbols(workspace, semanticdb, execution)))
      case None =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, s"Missing arguments for $SemanticSymbolsToolName")

  private def handleReconcileSymbolToolCall(
    id: Json,
    params: io.circe.ACursor,
    execution: ProcessExecution
  ): Json =
    params.downField("arguments").focus match
      case Some(arguments) =>
        reconcileSymbolArguments(arguments) match
          case Left(message) =>
            jsonRpcError(id, JsonRpcErrors.InvalidParams, message)
          case Right((workspace, file, line, col, semanticdb)) =>
            jsonRpcResponse(
              id,
              toolResult(cli.semanticReconcileSymbol(workspace, file, line, col, semanticdb, execution))
            )
      case None =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, s"Missing arguments for $SemanticReconcileSymbolToolName")

  private def handlePointEvidenceToolCall(
    id: Json,
    params: io.circe.ACursor,
    execution: ProcessExecution
  ): Json =
    params.downField("arguments").focus match
      case Some(arguments) =>
        pointEvidenceArguments(arguments) match
          case Left(message) => jsonRpcError(id, JsonRpcErrors.InvalidParams, message)
          case Right((workspace, file, line, col, sbtProject, sbtScalaVersion, sbtJavaHome)) =>
            jsonRpcResponse(id, toolResult(cli.semanticPointEvidence(
              workspace,
              file,
              line,
              col,
              sbtProject,
              sbtScalaVersion,
              sbtJavaHome,
              execution
            )))
      case None =>
        jsonRpcError(id, JsonRpcErrors.InvalidParams, s"Missing arguments for $SemanticPointEvidenceToolName")

  private def workspaceArgument(arguments: Json): Either[String, Path] =
    val cursor = arguments.hcursor
    cursor.downField("workspace").as[String] match
      case Right(workspace) if workspace.trim.nonEmpty =>
        Right(Path.of(workspace))
      case Right(_) =>
        Left("Invalid workspace: expected non-empty string")
      case Left(_) =>
        Left("Invalid workspace: expected string field")

  private def effectSummaryArguments(arguments: Json): Either[String, (Path, String)] =
    val cursor = arguments.hcursor
    for
      workspace <- workspaceArgument(arguments)
      file <- fileArgument(arguments)
    yield (workspace, file)

  private def symbolAtArguments(arguments: Json): Either[String, (Path, String, Int, Int)] =
    for
      workspace <- workspaceArgument(arguments)
      file <- fileArgument(arguments)
      line <- positiveIntArgument(arguments, "line")
      col <- positiveIntArgument(arguments, "col")
    yield (workspace, file, line, col)

  private def pointEvidenceArguments(
      arguments: Json
  ): Either[String, (Path, String, Int, Int, Option[String], Option[String], Option[String])] =
    for
      base <- symbolAtArguments(arguments)
      sbtProject <- optionalSbtProjectArgument(arguments)
      sbtScalaVersion <- optionalSbtScalaVersionArgument(arguments)
      sbtJavaHome <- optionalSbtJavaHomeArgument(arguments)
      _ <- Either.cond(
        sbtJavaHome.isEmpty || sbtProject.nonEmpty,
        (),
        "sbtJavaHome requires sbtProject for semantic_point_evidence"
      )
      _ <- Either.cond(
        sbtScalaVersion.isEmpty || sbtProject.nonEmpty,
        (),
        "sbtScalaVersion requires sbtProject for semantic_point_evidence"
      )
    yield (base._1, base._2, base._3, base._4, sbtProject, sbtScalaVersion, sbtJavaHome)

  private def optionalSbtScalaVersionArgument(arguments: Json): Either[String, Option[String]] =
    arguments.hcursor.downField("sbtScalaVersion").focus match
      case None => Right(None)
      case Some(value) => value.asString match
        case None => Left("Invalid sbtScalaVersion: expected string field")
        case Some(text) => SbtScalaVersion.parse(text)
          .left.map(message => s"Invalid sbtScalaVersion: $message")
          .map(axis => Some(axis.value))

  private def symbolsArguments(arguments: Json): Either[String, (Path, String)] =
    for
      workspace <- workspaceArgument(arguments)
      semanticdb <- semanticdbArgument(arguments)
    yield (workspace, semanticdb)

  private def reconcileSymbolArguments(arguments: Json): Either[String, (Path, String, Int, Int, String)] =
    for
      workspace <- workspaceArgument(arguments)
      file <- fileArgument(arguments)
      line <- positiveIntArgument(arguments, "line")
      col <- positiveIntArgument(arguments, "col")
      semanticdb <- semanticdbArgument(arguments)
    yield (workspace, file, line, col, semanticdb)

  private def fileArgument(arguments: Json): Either[String, String] =
    val cursor = arguments.hcursor
    cursor.downField("file").as[String] match
      case Right(value) if value.trim.nonEmpty => Right(value)
      case Right(_)                            => Left("Invalid file: expected non-empty string")
      case Left(_)                             => Left("Invalid file: expected string field")

  private def semanticdbArgument(arguments: Json): Either[String, String] =
    val cursor = arguments.hcursor
    cursor.downField("semanticdb").as[String] match
      case Right(value) if value.trim.nonEmpty => Right(value)
      case Right(_)                            => Left("Invalid semanticdb: expected non-empty string")
      case Left(_)                             => Left("Invalid semanticdb: expected string field")

  private def positiveIntArgument(arguments: Json, field: String): Either[String, Int] =
    val cursor = arguments.hcursor
    cursor.downField(field).focus match
      case Some(json) if json.isNumber =>
        json.asNumber.flatMap(_.toInt) match
          case Some(value) if value > 0 => Right(value)
          case Some(_)                  => Left(s"Invalid $field: expected positive integer")
          case None                     => Left(s"Invalid $field: expected integer field")
      case Some(_) =>
        Left(s"Invalid $field: expected integer field")
      case None =>
        Left(s"Invalid $field: expected integer field")

object SemanticScalaMcpServer:
  val ProtocolVersion: String = "2025-06-18"
  val ServerName: String = "semantic-harness-mcp-server"
  val ServerVersion: String = BuildVersion.value
  val SemanticCompileToolName: String = "semantic_compile"
  val SemanticErrorsToolName: String = "semantic_errors"
  val SemanticTestToolName: String = "semantic_test"
  val SemanticEffectSummaryToolName: String = "semantic_effect_summary"
  val SemanticSymbolAtToolName: String = "semantic_symbol_at"
  val SemanticSymbolsToolName: String = "semantic_symbols"
  val SemanticReconcileSymbolToolName: String = "semantic_reconcile_symbol"
  val SemanticPointEvidenceToolName: String = "semantic_point_evidence"

  object JsonRpcErrors:
    val ParseError = -32700
    val InvalidRequest = -32600
    val MethodNotFound = -32601
    val InvalidParams = -32602
    val InternalError = -32603
    val ServerBusy = -32000
    val DuplicateRequestId = -32001
    val ResponseTooLarge = -32002

  def initializeResult(request: Json): Json =
    val requestedVersion =
      request.hcursor.downField("params").downField("protocolVersion").as[String].getOrElse(ProtocolVersion)
    val selectedVersion =
      if requestedVersion == ProtocolVersion then requestedVersion else ProtocolVersion

    Json.obj(
      "protocolVersion" -> Json.fromString(selectedVersion),
      "capabilities" -> Json.obj(
        "tools" -> Json.obj(
          "listChanged" -> Json.False
        )
      ),
      "serverInfo" -> Json.obj(
        "name" -> Json.fromString(ServerName),
        "version" -> Json.fromString(ServerVersion)
      ),
      "instructions" -> Json.fromString(
        "Exposes semantic_compile, semantic_errors, semantic_test, semantic_effect_summary, semantic_symbol_at, semantic_symbols, semantic_reconcile_symbol, and semantic_point_evidence as CLI-backed semantic-scala tools."
      )
    )

  def semanticCompileTool: Json =
    workspaceTool(
      name = SemanticCompileToolName,
      description = "Run semantic-scala compile --json in a workspace and return the wrapper result.",
      workspaceDescription = "Workspace directory where semantic-scala compile --json should run."
    )

  def semanticErrorsTool: Json =
    workspaceTool(
      name = SemanticErrorsToolName,
      description = "Run semantic-scala errors --json in a workspace and return the wrapper result.",
      workspaceDescription = "Workspace directory where semantic-scala errors --json should run."
    )

  def semanticTestTool: Json =
    workspaceTool(
      name = SemanticTestToolName,
      description = "Run semantic-scala test --json in a workspace and return the wrapper result.",
      workspaceDescription = "Workspace directory where semantic-scala test --json should run."
    )

  def semanticEffectSummaryTool: Json =
    Json.obj(
      "name" -> Json.fromString(SemanticEffectSummaryToolName),
      "title" -> Json.fromString(SemanticEffectSummaryToolName),
      "description" -> Json.fromString("Run semantic-scala effect-summary --file <path> --json in a workspace and return the wrapper result."),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "workspace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Workspace directory where semantic-scala should run.")
          ),
          "file" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Scala source file path relative to the workspace.")
          )
        ),
        "required" -> Json.arr(Json.fromString("workspace"), Json.fromString("file"))
      )
    )

  def semanticSymbolAtTool: Json =
    Json.obj(
      "name" -> Json.fromString(SemanticSymbolAtToolName),
      "title" -> Json.fromString(SemanticSymbolAtToolName),
      "description" -> Json.fromString("Run semantic-scala symbol-at --file <path> --line <n> --col <n> --json in a workspace and return the wrapper result."),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "workspace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Workspace directory where semantic-scala should run.")
          ),
          "file" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Scala source file path relative to the workspace.")
          ),
          "line" -> Json.obj(
            "type" -> Json.fromString("integer"),
            "description" -> Json.fromString("One-based source line.")
          ),
          "col" -> Json.obj(
            "type" -> Json.fromString("integer"),
            "description" -> Json.fromString("One-based source column.")
          )
        ),
        "required" -> Json.arr(Json.fromString("workspace"), Json.fromString("file"), Json.fromString("line"), Json.fromString("col"))
      )
    )

  def semanticSymbolsTool: Json =
    Json.obj(
      "name" -> Json.fromString(SemanticSymbolsToolName),
      "title" -> Json.fromString(SemanticSymbolsToolName),
      "description" -> Json.fromString("Run semantic-scala symbols --semanticdb <path> --json in a workspace and return the wrapper result."),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "workspace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Workspace directory where semantic-scala should run.")
          ),
          "semanticdb" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("SemanticDB file path relative to the workspace.")
          )
        ),
        "required" -> Json.arr(Json.fromString("workspace"), Json.fromString("semanticdb"))
      )
    )

  def semanticReconcileSymbolTool: Json =
    Json.obj(
      "name" -> Json.fromString(SemanticReconcileSymbolToolName),
      "title" -> Json.fromString(SemanticReconcileSymbolToolName),
      "description" -> Json.fromString("Run semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> --json in a workspace and return the wrapper result."),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "workspace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Workspace directory where semantic-scala should run.")
          ),
          "file" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Scala source file path relative to the workspace.")
          ),
          "line" -> Json.obj(
            "type" -> Json.fromString("integer"),
            "description" -> Json.fromString("One-based source line.")
          ),
          "col" -> Json.obj(
            "type" -> Json.fromString("integer"),
            "description" -> Json.fromString("One-based source column.")
          ),
          "semanticdb" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("SemanticDB file path relative to the workspace.")
          )
        ),
        "required" -> Json.arr(Json.fromString("workspace"), Json.fromString("file"), Json.fromString("line"), Json.fromString("col"), Json.fromString("semanticdb"))
      )
    )

  def semanticPointEvidenceTool: Json =
    Json.obj(
      "name" -> Json.fromString(SemanticPointEvidenceToolName),
      "title" -> Json.fromString(SemanticPointEvidenceToolName),
      "description" -> Json.fromString("Run semantic-scala point-evidence with an explicit workspace-relative Scala source and one-based UTF-16 position."),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "workspace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Existing workspace directory where semantic-scala should run.")
          ),
          "file" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Contained Scala source file path relative to the workspace.")
          ),
          "line" -> Json.obj(
            "type" -> Json.fromString("integer"),
            "description" -> Json.fromString("Positive one-based source line.")
          ),
          "col" -> Json.obj(
            "type" -> Json.fromString("integer"),
            "description" -> Json.fromString("Positive one-based UTF-16 source column.")
          ),
          "sbtProject" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Optional validated sbt project ID selecting fixed Compile target-aware v4 partial existing-output evidence.")
          ),
          "sbtScalaVersion" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Optional validated Scala axis; requires sbtProject.")
          ),
          "sbtJavaHome" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("Optional absolute target JDK home; requires sbtProject.")
          )
        ),
        "required" -> Json.arr(Json.fromString("workspace"), Json.fromString("file"), Json.fromString("line"), Json.fromString("col"))
      )
    )

  private def workspaceTool(name: String, description: String, workspaceDescription: String): Json =
    Json.obj(
      "name" -> Json.fromString(name),
      "title" -> Json.fromString(name),
      "description" -> Json.fromString(description),
      "inputSchema" -> Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "workspace" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString(workspaceDescription)
          ),
          "sbtProject" -> Json.obj(
            "type" -> Json.fromString("string"),
            "pattern" -> Json.fromString(SbtProjectIdSyntax.Pattern),
            "description" -> Json.fromString(
              "Optional validated sbt project ID; compile/errors use Compile and test uses Test."
            )
          ),
          "sbtJavaHome" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString(
              "Optional absolute Java home used only by the child sbt process."
            )
          )
        ),
        "required" -> Json.arr(Json.fromString("workspace"))
      )
    )

  def toolResult(result: McpToolResult): Json =
    val wrapper = result.asJson
    Json.obj(
      "content" -> Json.arr(
        Json.obj(
          "type" -> Json.fromString("text"),
          "text" -> Json.fromString(wrapper.noSpaces)
        )
      ),
      "structuredContent" -> wrapper,
      "isError" -> Json.fromBoolean(!result.ok)
    )

  def jsonRpcResponse(id: Json, result: Json): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString("2.0"),
      "id" -> id,
      "result" -> result
    )

  def jsonRpcError(id: Json, code: Int, message: String): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString("2.0"),
      "id" -> id,
      "error" -> Json.obj(
        "code" -> Json.fromInt(code),
        "message" -> Json.fromString(message)
      )
    )
