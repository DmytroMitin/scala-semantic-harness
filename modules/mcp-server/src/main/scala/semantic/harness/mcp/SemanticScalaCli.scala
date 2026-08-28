package semantic.harness.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal
import scala.util.Try

import io.circe.Json
import io.circe.parser.parse
import semantic.harness.core.CompileReport
import semantic.harness.core.SbtProjectIdSyntax
import semantic.harness.core.TestReport
import semantic.harness.fp.EffectSummaryReport
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.reconciliation.ReconciliationResult
import semantic.harness.reconciliation.PointEvidenceReport
import semantic.harness.reconciliation.ReconciliationResultV2
import semantic.harness.reconciliation.PointEvidenceReportV2
import semantic.harness.semanticdb_reader.SemanticFileSummary

final case class SemanticScalaCli(
  cliPath: Path,
  processRunner: ProcessRunner = ProcessRunner.default
):
  private val buildPermit = Semaphore(1, true)

  def semanticCompile(workspace: Path): McpToolResult =
    semanticCompile(workspace, None, ProcessExecution.default)

  def semanticCompile(workspace: Path, execution: ProcessExecution): McpToolResult =
    semanticCompile(workspace, None, execution)

  def semanticCompile(workspace: Path, sbtProject: Option[String]): McpToolResult =
    semanticCompile(workspace, sbtProject, ProcessExecution.default)

  def semanticCompile(
      workspace: Path,
      sbtProject: Option[String],
      execution: ProcessExecution
  ): McpToolResult =
    semanticCompile(workspace, sbtProject, None, execution)

  def semanticCompile(
      workspace: Path,
      sbtProject: Option[String],
      sbtJavaHome: Option[String],
      execution: ProcessExecution
  ): McpToolResult =
    runSelectedBuildJsonTool(
      workspace,
      "compile",
      sbtProject,
      sbtJavaHome,
      SemanticScalaCli.CompileSchemaVersion,
      execution
    )

  def semanticErrors(workspace: Path): McpToolResult =
    semanticErrors(workspace, None, ProcessExecution.default)

  def semanticErrors(workspace: Path, execution: ProcessExecution): McpToolResult =
    semanticErrors(workspace, None, execution)

  def semanticErrors(workspace: Path, sbtProject: Option[String]): McpToolResult =
    semanticErrors(workspace, sbtProject, ProcessExecution.default)

  def semanticErrors(
      workspace: Path,
      sbtProject: Option[String],
      execution: ProcessExecution
  ): McpToolResult =
    semanticErrors(workspace, sbtProject, None, execution)

  def semanticErrors(
      workspace: Path,
      sbtProject: Option[String],
      sbtJavaHome: Option[String],
      execution: ProcessExecution
  ): McpToolResult =
    runSelectedBuildJsonTool(
      workspace,
      "errors",
      sbtProject,
      sbtJavaHome,
      SemanticScalaCli.ErrorsSchemaVersion,
      execution
    )

  def semanticTest(workspace: Path): McpToolResult =
    semanticTest(workspace, None, ProcessExecution.default)

  def semanticTest(workspace: Path, execution: ProcessExecution): McpToolResult =
    semanticTest(workspace, None, execution)

  def semanticTest(workspace: Path, sbtProject: Option[String]): McpToolResult =
    semanticTest(workspace, sbtProject, ProcessExecution.default)

  def semanticTest(
      workspace: Path,
      sbtProject: Option[String],
      execution: ProcessExecution
  ): McpToolResult =
    semanticTest(workspace, sbtProject, None, execution)

  def semanticTest(
      workspace: Path,
      sbtProject: Option[String],
      sbtJavaHome: Option[String],
      execution: ProcessExecution
  ): McpToolResult =
    runSelectedBuildJsonTool(
      workspace,
      "test",
      sbtProject,
      sbtJavaHome,
      SemanticScalaCli.TestSchemaVersion,
      execution
    )

  def semanticEffectSummary(workspace: Path, file: String): McpToolResult =
    semanticEffectSummary(workspace, file, ProcessExecution.default)

  def semanticEffectSummary(
    workspace: Path,
    file: String,
    execution: ProcessExecution
  ): McpToolResult =
    val args = SemanticScalaCli.effectSummaryArgs(file)
    val command = cliCommand(args)
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()

    if !Files.exists(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace is not a directory: $normalizedWorkspace")
    else
      validateRelativeScalaFile(normalizedWorkspace, file, command) match
        case Some(failure) => failure
        case None          => runJsonTool(workspace, args, SemanticScalaCli.EffectSummarySchemaVersion, execution)

  def semanticSymbolAt(workspace: Path, file: String, line: Int, col: Int): McpToolResult =
    semanticSymbolAt(workspace, file, line, col, ProcessExecution.default)

  def semanticSymbolAt(
    workspace: Path,
    file: String,
    line: Int,
    col: Int,
    execution: ProcessExecution
  ): McpToolResult =
    val args = SemanticScalaCli.symbolAtArgs(file, line, col)
    val command = cliCommand(args)
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()

    if !Files.exists(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace is not a directory: $normalizedWorkspace")
    else if line <= 0 then
      validationFailure(command, normalizedWorkspace, "Invalid line: expected positive integer")
    else if col <= 0 then
      validationFailure(command, normalizedWorkspace, "Invalid col: expected positive integer")
    else
      validateRelativeScalaFile(normalizedWorkspace, file, command) match
        case Some(failure) => failure
        case None          => runJsonTool(workspace, args, SemanticScalaCli.SymbolAtSchemaVersion, execution)

  def semanticSymbols(workspace: Path, semanticdb: String): McpToolResult =
    semanticSymbols(workspace, semanticdb, ProcessExecution.default)

  def semanticSymbols(
    workspace: Path,
    semanticdb: String,
    execution: ProcessExecution
  ): McpToolResult =
    val args = SemanticScalaCli.symbolsArgs(semanticdb)
    val command = cliCommand(args)
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()

    if !Files.exists(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace is not a directory: $normalizedWorkspace")
    else
      validateRelativeFile(
        workspace = normalizedWorkspace,
        value = semanticdb,
        command = command,
        label = "semanticdb",
        displayName = "SemanticDB file",
        expectedExtension = ".semanticdb",
        expectedDescription = ".semanticdb file"
      ) match
        case Some(failure) => failure
        case None          => runJsonTool(workspace, args, SemanticScalaCli.SymbolsSchemaVersion, execution)

  def semanticReconcileSymbol(
    workspace: Path,
    file: String,
    line: Int,
    col: Int,
    semanticdb: String
  ): McpToolResult =
    semanticReconcileSymbol(workspace, file, line, col, semanticdb, ProcessExecution.default)

  def semanticReconcileSymbol(
    workspace: Path,
    file: String,
    line: Int,
    col: Int,
    semanticdb: String,
    execution: ProcessExecution
  ): McpToolResult =
    val args = SemanticScalaCli.reconcileSymbolArgs(file, line, col, semanticdb)
    val command = cliCommand(args)
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()

    if !Files.exists(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace is not a directory: $normalizedWorkspace")
    else if line <= 0 then
      validationFailure(command, normalizedWorkspace, "Invalid line: expected positive integer")
    else if col <= 0 then
      validationFailure(command, normalizedWorkspace, "Invalid col: expected positive integer")
    else
      validateRelativeScalaFile(normalizedWorkspace, file, command) match
        case Some(failure) => failure
        case None =>
          validateRelativeFile(
            workspace = normalizedWorkspace,
            value = semanticdb,
            command = command,
            label = "semanticdb",
            displayName = "SemanticDB file",
            expectedExtension = ".semanticdb",
            expectedDescription = ".semanticdb file"
          ) match
            case Some(failure) => failure
            case None          => runJsonTool(workspace, args, SemanticScalaCli.ReconcileSymbolSchemaVersion, execution)

  def semanticPointEvidence(workspace: Path, file: String, line: Int, col: Int): McpToolResult =
    semanticPointEvidence(workspace, file, line, col, ProcessExecution.default)

  def semanticPointEvidence(
    workspace: Path,
    file: String,
    line: Int,
    col: Int,
    execution: ProcessExecution
  ): McpToolResult =
    val args = SemanticScalaCli.pointEvidenceArgs(file, line, col)
    val command = cliCommand(args)
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()

    if !Files.exists(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace is not a directory: $normalizedWorkspace")
    else if line <= 0 then
      validationFailure(command, normalizedWorkspace, "Invalid line: expected positive integer")
    else if col <= 0 then
      validationFailure(command, normalizedWorkspace, "Invalid col: expected positive integer")
    else
      validateRelativeScalaFile(normalizedWorkspace, file, command) match
        case Some(failure) => failure
        case None => runJsonTool(workspace, args, SemanticScalaCli.PointEvidenceSchemaVersion, execution)

  def semanticCompileCommand: List[String] =
    cliCommand(SemanticScalaCli.CompileArgs)

  def semanticErrorsCommand: List[String] =
    cliCommand(SemanticScalaCli.ErrorsArgs)

  def semanticTestCommand: List[String] =
    cliCommand(SemanticScalaCli.TestArgs)

  def semanticEffectSummaryCommand(file: String): List[String] =
    cliCommand(SemanticScalaCli.effectSummaryArgs(file))

  def semanticSymbolAtCommand(file: String, line: Int, col: Int): List[String] =
    cliCommand(SemanticScalaCli.symbolAtArgs(file, line, col))

  def semanticSymbolsCommand(semanticdb: String): List[String] =
    cliCommand(SemanticScalaCli.symbolsArgs(semanticdb))

  def semanticReconcileSymbolCommand(file: String, line: Int, col: Int, semanticdb: String): List[String] =
    cliCommand(SemanticScalaCli.reconcileSymbolArgs(file, line, col, semanticdb))

  def semanticPointEvidenceCommand(file: String, line: Int, col: Int): List[String] =
    cliCommand(SemanticScalaCli.pointEvidenceArgs(file, line, col))

  private def runJsonTool(
    workspace: Path,
    args: List[String],
    expectedSchemaVersion: String,
    execution: ProcessExecution
  ): McpToolResult =
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()
    val command = cliCommand(args)

    if !Files.exists(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then
      validationFailure(command, normalizedWorkspace, s"Workspace is not a directory: $normalizedWorkspace")
    else
      try
        val result = processRunner.run(command, normalizedWorkspace, execution)
        result.failure match
          case Some(failure) => processFailure(command, normalizedWorkspace, failure)
          case None =>
            if result.exitCode == 0 then parsePayload(command, normalizedWorkspace, result, expectedSchemaVersion)
            else runtimeFailure(command, normalizedWorkspace, result)
      catch
        case NonFatal(error) =>
          failureResult(command, normalizedWorkspace, None, "semantic-scala process could not be started")

  private def runBuildJsonTool(
    workspace: Path,
    args: List[String],
    expectedSchemaVersion: String,
    execution: ProcessExecution
  ): McpToolResult =
    var acquired = false
    try
      while !acquired && !execution.isCancelled && !execution.isTimedOut do
        acquired = buildPermit.tryAcquire(25L, TimeUnit.MILLISECONDS)

      if execution.isCancelled then
        processFailure(cliCommand(args), workspace.toAbsolutePath.normalize(), ProcessFailure.Cancelled)
      else if execution.isTimedOut then
        processFailure(cliCommand(args), workspace.toAbsolutePath.normalize(), ProcessFailure.TimedOut)
      else runJsonTool(workspace, args, expectedSchemaVersion, execution)
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        processFailure(cliCommand(args), workspace.toAbsolutePath.normalize(), ProcessFailure.Cancelled)
    finally
      if acquired then buildPermit.release()

  private def runSelectedBuildJsonTool(
      workspace: Path,
      commandName: String,
      sbtProject: Option[String],
      sbtJavaHome: Option[String],
      expectedSchemaVersion: String,
      execution: ProcessExecution
  ): McpToolResult =
    val validatedProject = sbtProject match
      case None => Right(None)
      case Some(value) => SbtProjectIdSyntax.validate(value).map(Some.apply)
    val validatedJavaHome = sbtJavaHome match
      case None => Right(None)
      case Some(value) =>
        Try(Path.of(value)).toEither
          .left
          .map(_ => "Invalid sbtJavaHome: expected an absolute directory")
          .flatMap(path =>
            Either.cond(
              value.nonEmpty && path.isAbsolute,
              Some(value),
              "Invalid sbtJavaHome: expected an absolute directory"
            )
          )
    (validatedProject, validatedJavaHome) match
      case (Left(message), _) =>
        validationFailure(
          cliCommand(SemanticScalaCli.buildArgs(commandName, None, None)),
          workspace.toAbsolutePath.normalize(),
          message
        )
      case (_, Left(message)) =>
        validationFailure(
          cliCommand(SemanticScalaCli.buildArgs(commandName, None, None)),
          workspace.toAbsolutePath.normalize(),
          message
        )
      case (Right(project), Right(javaHome)) =>
        val result = runBuildJsonTool(
          workspace,
          SemanticScalaCli.buildArgs(commandName, project, javaHome),
          expectedSchemaVersion,
          execution
        )
        redactSelectedJava(result, javaHome)

  private def cliCommand(args: List[String]): List[String] =
    cliPath.toString :: args

  private def validateRelativeScalaFile(
    workspace: Path,
    file: String,
    command: List[String]
  ): Option[McpToolResult] =
    validateRelativeFile(
      workspace = workspace,
      value = file,
      command = command,
      label = "file",
      displayName = "File",
      expectedExtension = ".scala",
      expectedDescription = ".scala source file"
    )

  private def validateRelativeFile(
    workspace: Path,
    value: String,
    command: List[String],
    label: String,
    displayName: String,
    expectedExtension: String,
    expectedDescription: String
  ): Option[McpToolResult] =
    val trimmed = value.trim
    if trimmed.isEmpty then
      Some(validationFailure(command, workspace, s"Invalid $label: expected non-empty relative $expectedExtension path"))
    else
      val filePath = Path.of(trimmed)
      if filePath.isAbsolute then
        Some(validationFailure(command, workspace, s"Invalid $label: expected relative path"))
      else
        val resolved = workspace.resolve(filePath).normalize()
        if !resolved.startsWith(workspace) then
          Some(validationFailure(command, workspace, s"$displayName escapes workspace"))
        else if !Files.exists(resolved) then
          Some(validationFailure(command, workspace, s"$displayName does not exist"))
        else if !Files.isRegularFile(resolved) then
          Some(validationFailure(command, workspace, s"$displayName is not a regular file"))
        else if !trimmed.endsWith(expectedExtension) then
          Some(validationFailure(command, workspace, s"Invalid $label: expected $expectedDescription"))
        else None

  private def parsePayload(
    command: List[String],
    workspace: Path,
    result: ProcessResult,
    expectedSchemaVersion: String
  ): McpToolResult =
    parse(result.stdout) match
      case Left(_) =>
        protocolFailure(
          command,
          workspace,
          Some(result.exitCode),
          result.stderr,
          "Invalid JSON from semantic-scala"
        )
      case Right(json) =>
        json.asObject match
          case None =>
            protocolFailure(
              command,
              workspace,
              Some(result.exitCode),
              result.stderr,
              "Expected stdout JSON object"
            )
          case Some(obj) =>
            obj("schemaVersion").flatMap(_.asString) match
              case Some(`expectedSchemaVersion`) =>
                McpToolResult(
                  ok = true,
                  command = publicCommand(command),
                  workspace = workspace.toString,
                  exitCode = Some(result.exitCode),
                  schemaVersion = Some(expectedSchemaVersion),
                  payload = Some(json),
                  stderr = result.stderr,
                  error = None
                )
              case Some(_) =>
                protocolFailure(
                  command,
                  workspace,
                  Some(result.exitCode),
                  result.stderr,
                  s"schemaVersion mismatch: expected $expectedSchemaVersion"
                )
              case None =>
                protocolFailure(
                  command,
                  workspace,
                  Some(result.exitCode),
                  result.stderr,
                  s"Missing schemaVersion: expected $expectedSchemaVersion"
                )

  private def validationFailure(
    command: List[String],
    workspace: Path,
    message: String
  ): McpToolResult =
    failureResult(command, workspace, None, sanitize(message, workspace))

  private def runtimeFailure(
    command: List[String],
    workspace: Path,
    result: ProcessResult
  ): McpToolResult =
    failureResult(
      command,
      workspace,
      Some(result.exitCode),
      s"semantic-scala command failed with exit code ${result.exitCode}"
    )

  private def protocolFailure(
    command: List[String],
    workspace: Path,
    exitCode: Option[Int],
    stderr: String,
    message: String
  ): McpToolResult =
    failureResult(command, workspace, exitCode, sanitize(message, workspace))

  private def processFailure(
    command: List[String],
    workspace: Path,
    failure: ProcessFailure
  ): McpToolResult =
    val message =
      failure match
        case ProcessFailure.Cancelled              => "semantic-scala request was cancelled"
        case ProcessFailure.TimedOut               => "semantic-scala request timed out"
        case ProcessFailure.StdoutLimitExceeded    => "semantic-scala stdout exceeded the configured limit"
        case ProcessFailure.StderrLimitExceeded    => "semantic-scala stderr exceeded the configured limit"
        case ProcessFailure.AggregateLimitExceeded => "semantic-scala output exceeded the configured aggregate limit"
        case ProcessFailure.InvalidUtf8            => "semantic-scala output was not valid UTF-8"
        case ProcessFailure.CleanupFailed          => "semantic-scala process cleanup failed"
    failureResult(command, workspace, None, message)

  private def failureResult(
    command: List[String],
    workspace: Path,
    exitCode: Option[Int],
    message: String
  ): McpToolResult =
    McpToolResult(
      ok = false,
      command = publicCommand(command),
      workspace = "<workspace>",
      exitCode = exitCode,
      schemaVersion = None,
      payload = None,
      stderr = "",
      error = Some(message)
    )

  private def publicCommand(command: List[String]): List[String] =
    def redact(values: List[String]): List[String] =
      values match
        case "--sbt-java-home" :: _ :: tail =>
          "--sbt-java-home" :: "<sbt-java-home>" :: redact(tail)
        case head :: tail => head :: redact(tail)
        case Nil => Nil
    "semantic-scala" :: redact(command.drop(1))

  private def redactSelectedJava(
      result: McpToolResult,
      sbtJavaHome: Option[String]
  ): McpToolResult =
    sbtJavaHome match
      case None => result
      case Some(value) =>
        val privatePaths =
          (value :: Try(Path.of(value).toRealPath().toString).toOption.toList)
            .distinct
            .sortBy(path => -path.length)
        def sanitize(text: String): String =
          privatePaths.foldLeft(text)((current, path) =>
            current.replace(path, "<sbt-java-home>")
          )
        result.copy(
          command = publicCommand(result.command),
          stderr = sanitize(result.stderr),
          error = result.error.map(sanitize)
        )

  private def sanitize(message: String, workspace: Path): String =
    message
      .replace(workspace.toString, "<workspace>")
      .replace(cliPath.toString, "semantic-scala")

object SemanticScalaCli:
  val CompileArgs: List[String] = buildArgs("compile", None, None)
  val CompileSchemaVersion: String = CompileReport.SchemaVersion
  val ErrorsArgs: List[String] = buildArgs("errors", None, None)
  val ErrorsSchemaVersion: String = CompileReport.ErrorsSchemaVersion
  val TestArgs: List[String] = buildArgs("test", None, None)
  val TestSchemaVersion: String = TestReport.SchemaVersion
  val EffectSummarySchemaVersion: String = EffectSummaryReport.SchemaVersion
  val SymbolAtSchemaVersion: String = SymbolAtResult.SchemaVersion
  val SymbolsSchemaVersion: String = SemanticFileSummary.SchemaVersion
  val ReconcileSymbolSchemaVersion: String = ReconciliationResultV2.SchemaVersion
  val PointEvidenceSchemaVersion: String = PointEvidenceReportV2.SchemaVersion
  val DefaultCliPath: Path = Path.of("semantic-scala")

  def buildArgs(
      commandName: String,
      sbtProject: Option[String],
      sbtJavaHome: Option[String] = None
  ): List[String] =
    List(commandName) ++
      sbtProject.toList.flatMap(value => List("--sbt-project", value)) ++
      sbtJavaHome.toList.flatMap(value => List("--sbt-java-home", value)) ++
      List("--json")

  def effectSummaryArgs(file: String): List[String] =
    List("effect-summary", "--file", file, "--json")

  def symbolAtArgs(file: String, line: Int, col: Int): List[String] =
    List("symbol-at", "--file", file, "--line", line.toString, "--col", col.toString, "--json")

  def symbolsArgs(semanticdb: String): List[String] =
    List("symbols", "--semanticdb", semanticdb, "--json")

  def reconcileSymbolArgs(file: String, line: Int, col: Int, semanticdb: String): List[String] =
    List("reconcile-symbol", "--file", file, "--line", line.toString, "--col", col.toString, "--semanticdb", semanticdb, "--json")

  def pointEvidenceArgs(file: String, line: Int, col: Int): List[String] =
    List("point-evidence", "--workspace", ".", "--file", file, "--line", line.toString, "--col", col.toString, "--json")

  def default: SemanticScalaCli =
    SemanticScalaCli(DefaultCliPath)
