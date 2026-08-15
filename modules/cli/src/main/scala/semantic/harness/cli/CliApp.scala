package semantic.harness.cli

import io.circe.syntax.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.core.CompileReport
import semantic.harness.core.TestReport
import semantic.harness.fp.EffectSummaryAnalyzer
import semantic.harness.fp.EffectSummaryReport
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.InferTypeAcquisitionOrigin
import semantic.harness.presentation.InferTypeContextKind
import semantic.harness.presentation.InferTypeContextSummary
import semantic.harness.presentation.InferTypeFreshnessAssessment
import semantic.harness.presentation.InferTypeReport
import semantic.harness.presentation.InferTypeRequest
import semantic.harness.presentation.InferTypeResult
import semantic.harness.presentation.InferTypeStatus
import semantic.harness.presentation.InferTypeBatchBounds
import semantic.harness.presentation.InferTypeBatchReport
import semantic.harness.presentation.InferTypeBatchRequest
import semantic.harness.presentation.InferTypeBatchService
import semantic.harness.presentation.PresentationCompilerContext
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.reconciliation.ReconciliationResult
import semantic.harness.reconciliation.PointEvidenceReport
import semantic.harness.reconciliation.PointEvidenceService
import semantic.harness.reconciliation.SemanticPointEvidenceRequest
import semantic.harness.reconciliation.SemanticReconciler
import semantic.harness.sbt_runner.ReportConverter
import semantic.harness.sbt_runner.SbtClasspathAcquirer
import semantic.harness.sbt_runner.SbtClasspathCacheFailure
import semantic.harness.sbt_runner.SbtClasspathCacheMode
import semantic.harness.sbt_runner.SbtClasspathCacheResolutionOrigin
import semantic.harness.sbt_runner.SbtClasspathCacheService
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathRequest
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtRunner
import semantic.harness.semanticdb_reader.SemanticFileSummary
import semantic.harness.semanticdb_reader.SemanticdbCoverage
import semantic.harness.semanticdb_reader.SemanticdbCoverageReport
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReport
import semantic.harness.semanticdb_reader.SemanticdbStatus
import semantic.harness.semanticdb_reader.SemanticdbStatusReport
import semantic.harness.semanticdb_reader.SemanticdbStatusReportV2
import semantic.harness.semanticdb_reader.SemanticdbStatusV2
import semantic.harness.semanticdb_reader.SemanticdbReader
import semantic.harness.semanticdb_reader.UsagesCliRequest
import semantic.harness.semanticdb_reader.UsagesCliService
import semantic.harness.semanticdb_reader.UsagesPublicFailure
import semantic.harness.semanticdb_reader.UsagesPublicFailureKind
import semantic.harness.semanticdb_reader.UsagesPublicResult

object CliApp:
  val Version = "0.1.0-alpha.2-SNAPSHOT"

  def run(args: List[String]): CliResult =
    val acquirer = SbtClasspathAcquirer.default
    run(
      args,
      SbtRunner.default,
      acquirer,
      SbtClasspathCacheService.default(acquirer),
      defaultProjectDir
    )

  def run(args: List[String], runner: SbtRunner, projectDir: Path): CliResult =
    val acquirer = SbtClasspathAcquirer.default
    run(
      args,
      runner,
      acquirer,
      SbtClasspathCacheService.default(acquirer),
      projectDir
    )

  def run(
      args: List[String],
      runner: SbtRunner,
      classpathAcquirer: SbtClasspathAcquirer,
      projectDir: Path
  ): CliResult =
    run(
      args,
      runner,
      classpathAcquirer,
      SbtClasspathCacheService.default(classpathAcquirer),
      projectDir
    )

  def run(
      args: List[String],
      runner: SbtRunner,
      classpathAcquirer: SbtClasspathAcquirer,
      cacheService: SbtClasspathCacheService,
      projectDir: Path
  ): CliResult =
    CliParser.parse(args) match
      case ParseResult.Parsed(command) =>
        runCommand(command, runner, classpathAcquirer, cacheService, projectDir)
      case ParseResult.Invalid(message, Some(json)) =>
        usagesFailure(
          UsagesPublicFailure(
            failureKind = UsagesPublicFailureKind.InvalidInput,
            message = message
          ),
          json
        )
      case ParseResult.Invalid(message, None) =>
        CliResult(
          stdout = None,
          stderr = Some(s"$message\n\n$helpText"),
          exitCode = 1
        )

  def runCommand(command: CliCommand, runner: SbtRunner, projectDir: Path): CliResult =
    val acquirer = SbtClasspathAcquirer.default
    runCommand(
      command,
      runner,
      acquirer,
      SbtClasspathCacheService.default(acquirer),
      projectDir
    )

  def runCommand(
      command: CliCommand,
      runner: SbtRunner,
      classpathAcquirer: SbtClasspathAcquirer,
      projectDir: Path
  ): CliResult =
    runCommand(
      command,
      runner,
      classpathAcquirer,
      SbtClasspathCacheService.default(classpathAcquirer),
      projectDir
    )

  def runCommand(
      command: CliCommand,
      runner: SbtRunner,
      classpathAcquirer: SbtClasspathAcquirer,
      cacheService: SbtClasspathCacheService,
      projectDir: Path
  ): CliResult =
    command match
      case CliCommand.Help(topic) =>
        help(topic)
      case CliCommand.Version =>
        CliResult(Some(Version), None, 0)
      case CliCommand.Compile(json) =>
        compile(json, runner, projectDir)
      case CliCommand.Test(json) =>
        test(json, runner, projectDir)
      case CliCommand.Errors(json) =>
        errors(json, runner, projectDir)
      case CliCommand.SemanticdbStatus(workspace, schemaVersion, json) =>
        semanticdbStatus(workspace, schemaVersion, json, projectDir)
      case CliCommand.SemanticdbCoverage(workspace, json) =>
        semanticdbCoverage(workspace, json, projectDir)
      case CliCommand.SemanticdbForSource(file, workspace, json) =>
        semanticdbForSource(file, workspace, json, projectDir)
      case CliCommand.PointEvidence(file, workspace, line, column, json) =>
        pointEvidence(file, workspace, line, column, json, projectDir)
      case CliCommand.Symbols(semanticdb, json) =>
        symbols(semanticdb, json, projectDir)
      case CliCommand.Usages(workspace, manifest, target, selectors, returnedLimit, json) =>
        usages(workspace, manifest, target, selectors, returnedLimit, json, projectDir)
      case CliCommand.SymbolAt(file, line, column, json) =>
        symbolAt(file, line, column, json, projectDir)
      case CliCommand.InferType(
            file,
            line,
            column,
            workspace,
            classpathEntries,
            sbtProject,
            sbtConfiguration,
            sbtCacheMode,
            json
          ) =>
        inferType(
          file,
          line,
          column,
          workspace,
          classpathEntries,
          sbtProject,
          sbtConfiguration,
          sbtCacheMode,
          json,
          cacheService,
          projectDir
        )
      case CliCommand.InferTypeBatch(
            requests,
            workspace,
            sbtProject,
            sbtConfiguration,
            sbtCacheMode,
            json
          ) =>
        inferTypeBatch(
          requests,
          workspace,
          sbtProject,
          sbtConfiguration,
          sbtCacheMode,
          json,
          cacheService,
          projectDir
        )
      case CliCommand.ReconcileSymbol(file, line, column, semanticdb, json) =>
        reconcileSymbol(file, line, column, semanticdb, json, projectDir)
      case CliCommand.EffectSummary(file, json) =>
        effectSummary(file, json, projectDir)

  private def help(topic: Option[String]): CliResult =
    topic match
      case None =>
        CliResult(Some(helpText), None, 0)
      case Some(name) =>
        commandHelp(name) match
          case Some(text) => CliResult(Some(text), None, 0)
          case None =>
            CliResult(
              stdout = None,
              stderr = Some(s"Unknown help topic: $name\n\n$helpText"),
              exitCode = 1
            )

  private def compile(json: Boolean, runner: SbtRunner, projectDir: Path): CliResult =
    try
      val report = ReportConverter.compileReport(runner.compile(projectDir))
      if json then
        CliResult(Some(report.asJson.noSpaces), None, 0)
      else
        CliResult(Some(compileSummary(report)), None, 0)
    catch
      case exception: Exception =>
        CliResult(None, Some(s"Unable to run sbt compile: ${exception.getMessage}"), 1)

  private def test(json: Boolean, runner: SbtRunner, projectDir: Path): CliResult =
    try
      val report = ReportConverter.testReport(runner.test(projectDir))
      if json then
        CliResult(Some(report.asJson.noSpaces), None, 0)
      else
        CliResult(Some(testSummary(report)), None, 0)
    catch
      case exception: Exception =>
        CliResult(None, Some(s"Unable to run sbt test: ${exception.getMessage}"), 1)

  private def errors(json: Boolean, runner: SbtRunner, projectDir: Path): CliResult =
    try
      val report = ReportConverter
        .compileReport(runner.compile(projectDir))
        .copy(schemaVersion = CompileReport.ErrorsSchemaVersion)
      if json then
        CliResult(Some(report.asJson.noSpaces), None, 0)
      else
        CliResult(Some(errorsSummary(report)), None, 0)
    catch
      case exception: Exception =>
        CliResult(None, Some(s"Unable to run sbt errors: ${exception.getMessage}"), 1)

  private def symbols(semanticdb: String, json: Boolean, projectDir: Path): CliResult =
    val path = resolveProjectPath(projectDir, semanticdb)
    SemanticdbReader.read(path) match
      case Right(summary) =>
        if json then CliResult(Some(summary.asJson.noSpaces), None, 0)
        else CliResult(Some(symbolsSummary(summary)), None, 0)
      case Left(message) =>
        CliResult(None, Some(message), 1)

  private def semanticdbStatus(
    workspace: String,
    schemaVersion: SemanticdbStatusVersion,
    json: Boolean,
    projectDir: Path
  ): CliResult =
    val path = resolveProjectPath(projectDir, workspace)
    schemaVersion match
      case SemanticdbStatusVersion.V1 =>
        SemanticdbStatus.inspect(path) match
          case Right(report) =>
            if json then CliResult(Some(report.asJson.noSpaces), None, 0)
            else CliResult(Some(semanticdbStatusSummary(report)), None, 0)
          case Left(message) =>
            CliResult(None, Some(message), 1)
      case SemanticdbStatusVersion.V2 =>
        SemanticdbStatusV2.inspect(path) match
          case Right(report) =>
            if json then CliResult(Some(report.asJson.noSpaces), None, 0)
            else CliResult(Some(semanticdbStatusV2Summary(report)), None, 0)
          case Left(message) =>
            CliResult(None, Some(message), 1)

  private def semanticdbForSource(file: String, workspace: String, json: Boolean, projectDir: Path): CliResult =
    val sourcePath = resolveProjectPath(projectDir, file)
    val workspacePath = resolveProjectPath(projectDir, workspace)
    SemanticdbForSource.inspect(workspacePath, sourcePath) match
      case Right(report) =>
        if json then CliResult(Some(report.asJson.noSpaces), None, 0)
        else CliResult(Some(semanticdbForSourceSummary(report)), None, 0)
      case Left(message) =>
        CliResult(None, Some(message), 1)

  private def pointEvidence(
    file: String,
    workspace: String,
    line: Int,
    column: Int,
    json: Boolean,
    projectDir: Path
  ): CliResult =
    val workspacePath = resolveProjectPath(projectDir, workspace).toAbsolutePath.normalize()
    val sourcePath = resolveProjectPath(projectDir, file).toAbsolutePath.normalize()
    if !Files.exists(workspacePath) then
      CliResult(None, Some(s"Workspace does not exist: $workspacePath"), 1)
    else if !Files.isDirectory(workspacePath) then
      CliResult(None, Some(s"Workspace is not a directory: $workspacePath"), 1)
    else if !sourcePath.startsWith(workspacePath) then
      CliResult(None, Some("Source file escapes workspace"), 1)
    else if !Files.exists(sourcePath) then
      CliResult(None, Some(s"Source file does not exist: $sourcePath"), 1)
    else if !Files.isRegularFile(sourcePath) then
      CliResult(None, Some(s"Source path is not a file: $sourcePath"), 1)
    else if !sourcePath.getFileName.toString.endsWith(".scala") then
      CliResult(None, Some("Invalid file: expected .scala source file"), 1)
    else
      PointEvidenceService()
        .inspect(SemanticPointEvidenceRequest(workspacePath, sourcePath, line, column))
        .flatMap(PointEvidenceReport.fromInternal) match
        case Right(report) =>
          if json then CliResult(Some(report.asJson.noSpaces), None, 0)
          else CliResult(Some(pointEvidenceSummary(report)), None, 0)
        case Left(message) =>
          CliResult(None, Some(message), 1)

  private def semanticdbCoverage(workspace: String, json: Boolean, projectDir: Path): CliResult =
    val workspacePath = resolveProjectPath(projectDir, workspace)
    SemanticdbCoverage.inspect(workspacePath) match
      case Right(report) =>
        if json then CliResult(Some(report.asJson.noSpaces), None, 0)
        else CliResult(Some(semanticdbCoverageSummary(report)), None, 0)
      case Left(message) =>
        CliResult(None, Some(message), 1)

  private def symbolAt(file: String, line: Int, column: Int, json: Boolean, projectDir: Path): CliResult =
    val path = resolveProjectPath(projectDir, file)
    PresentationCompilerService().symbolAt(path, line, column) match
      case Right(result) =>
        if json then CliResult(Some(result.asJson.noSpaces), None, 0)
        else CliResult(Some(symbolAtSummary(result)), None, 0)
      case Left(message) =>
        CliResult(None, Some(message), 1)

  private def inferType(
    file: String,
    line: Int,
    column: Int,
    workspace: Option[String],
    classpathEntries: List[String],
    sbtProject: Option[SbtProjectId],
    sbtConfiguration: Option[SbtClasspathConfiguration],
    sbtCacheMode: Option[SbtClasspathCacheMode],
    json: Boolean,
    cacheService: SbtClasspathCacheService,
    projectDir: Path
  ): CliResult =
    val path = resolveProjectPath(projectDir, file)
    val workspacePath = workspace.map(resolveProjectPath(projectDir, _))
    (sbtProject, sbtConfiguration) match
      case (Some(project), Some(configuration)) =>
        val request = SbtClasspathRequest(workspacePath.get, project, configuration)
        cacheService
          .resolve(request, sbtCacheMode.getOrElse(SbtClasspathCacheMode.Fresh)) match
          case Right(resolution) =>
            val acquired = resolution.result
            val context = PresentationCompilerContext.explicit(
              acquired.entries.map(_.path),
              workspacePath
            )
            PresentationCompilerService()
              .inferType(InferTypeRequest(path, line, column, context)) match
              case Right(result) =>
                renderInferType(
                  sbtReport(
                    result,
                    acquired.project,
                    acquired.configuration,
                    resolution.origin
                  ),
                  json
                )
              case Left(message) =>
                CliResult(None, Some(message), 1)
          case Left(failure) =>
            CliResult(None, Some(SbtClasspathCacheFailure.message(failure)), 1)
      case _ =>
        val context =
          if classpathEntries.isEmpty then PresentationCompilerContext(workspace = workspacePath)
          else
            PresentationCompilerContext.explicit(
              classpathEntries.map(resolveProjectPath(projectDir, _)),
              workspacePath
            )
        PresentationCompilerService().inferType(InferTypeRequest(path, line, column, context)) match
          case Right(result) =>
            renderInferType(InferTypeReport.from(result), json)
          case Left(message) =>
            CliResult(None, Some(message), 1)

  private def renderInferType(report: InferTypeReport, json: Boolean): CliResult =
    if json then CliResult(Some(report.asJson.noSpaces), None, 0)
    else CliResult(Some(inferTypeSummary(report)), None, 0)

  private def inferTypeBatch(
      requestsFile: String,
      workspace: String,
      project: SbtProjectId,
      configuration: SbtClasspathConfiguration,
      cacheMode: SbtClasspathCacheMode,
      json: Boolean,
      cacheService: SbtClasspathCacheService,
      projectDir: Path
  ): CliResult =
    val requestPath = resolveProjectPath(projectDir, requestsFile)
    val workspacePath = resolveProjectPath(projectDir, workspace)
    readBatchRequest(requestPath) match
      case Left(message) => CliResult(None, Some(message), 1)
      case Right(batchRequest) =>
        val classpathRequest = SbtClasspathRequest(workspacePath, project, configuration)
        cacheService.resolve(classpathRequest, cacheMode) match
          case Left(failure) =>
            CliResult(None, Some(SbtClasspathCacheFailure.message(failure)), 1)
          case Right(resolution) =>
            val acquired = resolution.result
            val context = PresentationCompilerContext.explicit(
              acquired.entries.map(_.path),
              Some(workspacePath)
            )
            InferTypeBatchService().infer(
              workspacePath,
              batchRequest.requests,
              context
            ) match
              case Left(message) => CliResult(None, Some(message), 1)
              case Right(results) =>
                val (origin, freshness, provenanceWarnings) =
                  batchProvenance(
                    acquired.project,
                    acquired.configuration,
                    resolution.origin
                  )
                val report = InferTypeBatchReport(
                  requestCount = batchRequest.requests.size,
                  context = InferTypeContextSummary(
                    kind = InferTypeContextKind.SbtClasspath,
                    classpathEntryCount = acquired.entries.size,
                    workspaceProvided = true,
                    sbtProject = Some(acquired.project.value),
                    sbtConfiguration =
                      Some(SbtClasspathConfiguration.value(acquired.configuration)),
                    acquisitionOrigin = Some(origin),
                    freshnessAssessment = Some(freshness)
                  ),
                  contextWarnings = List(
                    "Compiler hover rendering is version-dependent.",
                    "Compiler hover rendering is not canonical type identity.",
                    "A compiler hover query does not prove whole-project compilation."
                  ) ++ provenanceWarnings ++ List(
                    "Sibling uncompiled or open sources are not modeled.",
                    "A batch is not an atomic workspace snapshot; each item source is captured once when that item is processed."
                  ),
                  results = results
                )
                InferTypeBatchReport.encodeBounded(report) match
                  case Right(encoded) if json => CliResult(Some(encoded), None, 0)
                  case Right(_) =>
                    CliResult(None, Some("infer-type-batch requires --json"), 1)
                  case Left(message) => CliResult(None, Some(message), 1)

  private def usages(
    workspace: String,
    manifest: String,
    target: semantic.harness.semanticdb_reader.UsagesCliTarget,
    selectors: semantic.harness.semanticdb_reader.UsagesPublicSelectors,
    returnedOccurrenceLimit: Int,
    json: Boolean,
    projectDir: Path
  ): CliResult =
    val request = UsagesCliRequest(
      workspace = resolveProjectPath(projectDir, workspace),
      manifest = manifest,
      target = target,
      selectors = selectors,
      returnedOccurrenceLimit = returnedOccurrenceLimit
    )
    UsagesCliService.run(request) match
      case Right(result) =>
        val output = if json then result.asJson.noSpaces else usagesSummary(result)
        CliResult(Some(output), None, 0)
      case Left(failure) => usagesFailure(failure, json)

  private def usagesFailure(failure: UsagesPublicFailure, json: Boolean): CliResult =
    if json then CliResult(Some(failure.asJson.noSpaces), None, 1)
    else CliResult(None, Some(s"${failure.failureKind}: ${failure.message}"), 1)

  private def readBatchRequest(path: Path): Either[String, InferTypeBatchRequest] =
    try
      if Files.isSymbolicLink(path) then Left("Infer-type batch request file symbolic links are not permitted")
      else if !Files.exists(path) then Left("Infer-type batch request file does not exist")
      else if !Files.isRegularFile(path) then Left("Infer-type batch request path is not a regular file")
      else if Files.size(path) > InferTypeBatchBounds.MaxRequestFileBytes then
        Left(s"Infer-type batch request file exceeds ${InferTypeBatchBounds.MaxRequestFileBytes} bytes")
      else
        val stream = Files.newInputStream(path)
        val bytes =
          try stream.readNBytes((InferTypeBatchBounds.MaxRequestFileBytes + 1).toInt)
          finally stream.close()
        if bytes.length > InferTypeBatchBounds.MaxRequestFileBytes then
          Left(s"Infer-type batch request file exceeds ${InferTypeBatchBounds.MaxRequestFileBytes} bytes")
        else
          val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          try InferTypeBatchRequest.decodeAndValidate(decoder.decode(ByteBuffer.wrap(bytes)).toString)
          catch
            case _: java.nio.charset.CharacterCodingException =>
              Left("Infer-type batch request file is not valid UTF-8")
    catch
      case _: Exception => Left("Unable to read infer-type batch request file")

  private def batchProvenance(
      project: SbtProjectId,
      configuration: SbtClasspathConfiguration,
      origin: SbtClasspathCacheResolutionOrigin
  ): (InferTypeAcquisitionOrigin, InferTypeFreshnessAssessment, List[String]) =
    val configurationValue = SbtClasspathConfiguration.value(configuration)
    origin match
      case SbtClasspathCacheResolutionOrigin.FreshSbt =>
        (
          InferTypeAcquisitionOrigin.FreshSbt,
          InferTypeFreshnessAssessment.FreshBySbtEvaluation,
          List(
            s"sbt evaluated project '${project.value}' configuration '$configurationValue' once for this batch."
          )
        )
      case SbtClasspathCacheResolutionOrigin.CachedExplicitReuse =>
        (
          InferTypeAcquisitionOrigin.CachedExplicitReuse,
          InferTypeFreshnessAssessment.ReusedWithMatchingEvidence,
          List(
            "The compiled classpath was reused once from an explicitly requested cache after matching bounded evidence; sbt did not run for this batch.",
            "Matching bounded cache evidence does not prove current sbt freshness or cover arbitrary build inputs."
          )
        )

  private def sbtReport(
      result: InferTypeResult,
      project: SbtProjectId,
      configuration: SbtClasspathConfiguration,
      origin: SbtClasspathCacheResolutionOrigin
  ): InferTypeReport =
    val configurationValue = SbtClasspathConfiguration.value(configuration)
    val (publicOrigin, publicFreshness, provenanceWarnings) =
      origin match
        case SbtClasspathCacheResolutionOrigin.FreshSbt =>
          (
            InferTypeAcquisitionOrigin.FreshSbt,
            InferTypeFreshnessAssessment.FreshBySbtEvaluation,
            List(
              s"sbt evaluated project '${project.value}' configuration '$configurationValue' for this invocation."
            )
          )
        case SbtClasspathCacheResolutionOrigin.CachedExplicitReuse =>
          (
            InferTypeAcquisitionOrigin.CachedExplicitReuse,
            InferTypeFreshnessAssessment.ReusedWithMatchingEvidence,
            List(
              "The compiled classpath was reused from an explicitly requested cache after matching bounded evidence; sbt did not run for this invocation.",
              "Matching bounded cache evidence does not prove current sbt freshness or cover arbitrary build inputs."
            )
          )
    val report = InferTypeReport.from(result)
    report.copy(
      context = InferTypeContextSummary(
        kind = InferTypeContextKind.SbtClasspath,
        classpathEntryCount = result.classpathEntryCount,
        workspaceProvided = result.workspaceProvided,
        sbtProject = Some(project.value),
        sbtConfiguration = Some(configurationValue),
        acquisitionOrigin = Some(publicOrigin),
        freshnessAssessment = Some(publicFreshness)
      ),
      warnings = report.warnings.flatMap {
        case "Beyond the built-in Scala runtime, only the supplied compiled classpath entries are available." =>
          provenanceWarnings
        case warning => List(warning)
      }
    )

  private def reconcileSymbol(file: String, line: Int, column: Int, semanticdb: String, json: Boolean, projectDir: Path): CliResult =
    val sourcePath = resolveProjectPath(projectDir, file)
    val semanticdbPath = resolveProjectPath(projectDir, semanticdb)
    SemanticReconciler.reconcile(sourcePath, line, column, semanticdbPath) match
      case Right(result) =>
        if json then CliResult(Some(result.asJson.noSpaces), None, 0)
        else CliResult(Some(reconciliationSummary(result)), None, 0)
      case Left(message) =>
        CliResult(None, Some(message), 1)

  private def effectSummary(file: String, json: Boolean, projectDir: Path): CliResult =
    val sourcePath = resolveProjectPath(projectDir, file)
    EffectSummaryAnalyzer.summarize(sourcePath) match
      case Right(report) =>
        if json then CliResult(Some(report.asJson.noSpaces), None, 0)
        else CliResult(Some(effectSummaryText(report)), None, 0)
      case Left(message) =>
        CliResult(None, Some(message), 1)

  private def compileSummary(report: CompileReport): String =
    if report.success then "compile succeeded"
    else s"compile failed with ${report.diagnostics.size} diagnostic(s)"

  private def testSummary(report: TestReport): String =
    if report.success then s"test succeeded: ${report.passed}/${report.total} passed"
    else s"test failed: ${report.failed}/${report.total} failed"

  private def errorsSummary(report: CompileReport): String =
    if report.success then "no compile errors"
    else s"compile errors: ${report.diagnostics.size} diagnostic(s)"

  private def symbolsSummary(summary: SemanticFileSummary): String =
    s"${summary.uri}: ${summary.symbols.size} symbol(s), ${summary.occurrences.size} occurrence(s)"

  private def usagesSummary(report: UsagesPublicResult): String =
    val identity = report.target.flatMap(_.stableSymbol)
      .orElse(report.target.flatMap(_.localSymbolMarker))
      .getOrElse("<unresolved>")
    val scope =
      s"selected ${report.coverage.selectedSources}/${report.coverage.declaredSources} source(s), " +
        s"${report.coverage.selectedArtifacts}/${report.coverage.declaredArtifacts} artifact(s), " +
        s"closed=${report.coverage.inventoryBasis.inventoryClosed}"
    val status = report.state.toString match
      case "NoUsagesObserved" =>
        "No exact ordinary occurrences were observed in the closed selected declared scope; this never means globally unused."
      case "EvidenceFound" =>
        s"Found ${report.occurrences.size} returned exact ordinary occurrence(s)."
      case "CoverageIncomplete" =>
        s"Coverage is incomplete: ${report.reasons.map(_.code).mkString(", ")}."
      case "ArtifactStaleOrInconsistent" =>
        s"Evidence is stale or inconsistent: ${report.reasons.map(_.code).mkString(", ")}."
      case "Truncated" =>
        s"Evidence is bounded partial output: ${report.limits.hits.mkString(", ")}."
      case "TargetAmbiguous" => "The point target is ambiguous; no zero conclusion is available."
      case "TargetUnresolved" => "The point target is unresolved; no zero conclusion is available."
      case "UnsupportedConstruct" => "The explicit local symbol is unsupported; use point mode."
      case other => s"Outcome: $other."
    val evidence = report.occurrences.map { occurrence =>
      val path = occurrence.source.orElse(occurrence.safeUri).getOrElse("<unmapped>")
      val range = occurrence.range
      s"${occurrence.role} $path:${range.startLine}:${range.startCharacter}-${range.endLine}:${range.endCharacter}"
    }
    val summary = List(
      s"usages ${report.state} (${report.targetMode}) target=$identity",
      status,
      s"Inventory: $scope; fresh=${report.coverage.freshDocuments}, stale=${report.coverage.staleDocuments}, duplicateCopies=${report.coverage.duplicateCopies}."
    )
    (summary ++ evidence).mkString("\n")

  private def semanticdbStatusSummary(report: SemanticdbStatusReport): String =
    s"${report.workspace}: ${report.status}, ${report.parseableFiles}/${report.semanticdbFiles} parseable SemanticDB file(s)"

  private def semanticdbStatusV2Summary(report: SemanticdbStatusReportV2): String =
    s"${report.workspace}: ${report.artifactStatus}, ${report.parseableFiles}/${report.semanticdbFiles} parseable SemanticDB file(s), ${report.uniqueContentFiles} unique content file(s)"

  private def semanticdbForSourceSummary(report: SemanticdbForSourceReport): String =
    s"${report.sourceFile}: ${report.status}, ${report.matches.size} SemanticDB match(es)"

  private def pointEvidenceSummary(report: PointEvidenceReport): String =
    s"${report.sourceFile}:${report.position.line}:${report.position.column}: ${report.selection.status}, ${report.livePoint.status}, ${report.reconciliation.status}"

  private def semanticdbCoverageSummary(report: SemanticdbCoverageReport): String =
    s"${report.workspace}: ${report.coverageStatus}, ${report.coveredSourceFiles}/${report.sourceFiles} inventory source(s) covered; freshness and build-target completeness are not assessed"

  private def symbolAtSummary(result: SymbolAtResult): String =
    val symbol = result.symbol.orElse(result.displayName).getOrElse("<unknown>")
    s"${result.source}: $symbol"

  private def inferTypeSummary(report: InferTypeReport): String =
    val rendering =
      report.status match
        case InferTypeStatus.Resolved   => report.rendering.getOrElse("<unresolved>")
        case InferTypeStatus.Unresolved => "<unresolved>"
    s"${report.source}:${report.position.line}:${report.position.column}: $rendering"

  private def reconciliationSummary(result: ReconciliationResult): String =
    s"${result.file}: ${result.result.status}"

  private def effectSummaryText(report: EffectSummaryReport): String =
    s"${report.source}: ${report.methods.size} method(s)"

  private def resolveProjectPath(projectDir: Path, value: String): Path =
    val path = Path.of(value)
    if path.isAbsolute then path.normalize()
    else projectDir.resolve(path).normalize()

  private def defaultProjectDir: Path =
    sys.env
      .get("SEMANTIC_SCALA_CWD")
      .map(Path.of(_).toAbsolutePath)
      .getOrElse(Path.of("").toAbsolutePath)

  val helpText: String =
    """semantic-scala
      |
      |Usage:
      |  semantic-scala help [command]
      |  semantic-scala version
      |  semantic-scala compile [--json]
      |  semantic-scala test [--json]
      |  semantic-scala errors [--json]
      |  semantic-scala semanticdb-status --workspace <path> [--schema-version v1|v2] [--json]
      |  semantic-scala semanticdb-coverage --workspace <path> [--json]
      |  semantic-scala semanticdb-for-source --file <path> --workspace <path> [--json]
      |  semantic-scala point-evidence --file <path> --workspace <path> --line <n> --col <n> [--json]
      |  semantic-scala symbols --semanticdb <path> [--json]
      |  semantic-scala usages --workspace <path> --manifest <relative.json> --symbol <global-symbol> [selectors] [--json]
      |  semantic-scala usages --workspace <path> --manifest <relative.json> --file <relative-source> --line <n> --col <n> --semanticdb <relative-artifact> [selectors] [--json]
      |  semantic-scala symbol-at --file <path> --line <n> --col <n> [--json]
      |  semantic-scala infer-type --file <path> --line <n> --col <n> [--workspace <path>] [--classpath <entry>]... [--json]
      |  semantic-scala infer-type --file <path> --line <n> --col <n> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] [--json]
      |  semantic-scala infer-type-batch --requests <batch-request.json> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] --json
      |  semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> [--json]
      |  semantic-scala effect-summary --file <path> [--json]
      |
      |Commands:
      |  help      Print global or command-specific help.
      |  version   Print the CLI version.
      |  compile   Run sbt compile and report diagnostics.
      |  test      Run sbt test and report failures.
      |  errors    Rerun compile and report compile diagnostics.
      |  semanticdb-status Discover existing SemanticDB files in a workspace.
      |  semanticdb-coverage Compare a recursive Scala/Java source inventory with SemanticDB documents.
      |  semanticdb-for-source Map a source file to existing SemanticDB candidates.
      |  point-evidence Compose discovery, safe selection, live point evidence, and reconciliation.
      |  symbols   Read a single SemanticDB file and report symbols.
      |  usages    Query exact ordinary occurrences in one explicit manifest scope.
      |  symbol-at Query the symbol at a source position.
      |  infer-type Query compiler-rendered type/signature evidence at a source position.
      |  infer-type-batch Query an ordered bounded request list with one shared sbt context.
      |  reconcile-symbol Compare SemanticDB and presentation compiler symbol facts.
      |  effect-summary Summarize declared effect-like method return types.
      |""".stripMargin.trim

  private def commandHelp(name: String): Option[String] =
    name match
      case "help" =>
        Some(
          """Usage:
            |  semantic-scala help [command]
            |  semantic-scala --help
            |""".stripMargin.trim
        )
      case "version" =>
        Some(
          """Usage:
            |  semantic-scala version
            |  semantic-scala --version
            |""".stripMargin.trim
        )
      case "compile" =>
        Some(
          """Usage:
            |  semantic-scala compile [--json]
            |
            |Runs sbt compile in the current working directory.
            |""".stripMargin.trim
        )
      case "test" =>
        Some(
          """Usage:
            |  semantic-scala test [--json]
            |
            |Runs sbt test in the current working directory.
            |""".stripMargin.trim
        )
      case "errors" =>
        Some(
          """Usage:
            |  semantic-scala errors [--json]
            |
            |Temporary behavior: reruns compile and reports compile diagnostics.
            |""".stripMargin.trim
        )
      case "semanticdb-status" =>
        Some(
          """Usage:
            |  semantic-scala semanticdb-status --workspace <path> [--schema-version v1|v2] [--json]
            |
            |Read-only scan for existing .semanticdb files in a workspace.
            |v1 is the default. Opt-in v2 adds all-document, raw artifact,
            |duplicate-group, and bounded-candidate metadata; coverage is NotAssessed.
            |Does not generate SemanticDB or run sbt.
            |""".stripMargin.trim
        )
      case "semanticdb-for-source" =>
        Some(
          """Usage:
            |  semantic-scala semanticdb-for-source --file <path> --workspace <path> [--json]
            |
            |Read-only mapping from a source file to existing SemanticDB candidates.
            |Reports missing and ambiguous matches explicitly; does not generate SemanticDB or run sbt.
            |""".stripMargin.trim
        )
      case "point-evidence" =>
        Some(
          """Usage:
            |  semantic-scala point-evidence --file <path> --workspace <path> --line <n> --col <n> [--json]
            |
            |Composes existing SemanticDB discovery with one live presentation-compiler point query.
            |The source must be contained by the explicit workspace. Input positions are one-based UTF-16.
            |Only one unique parsed artifact is selected; ambiguity, absence, partial evidence, and unavailability remain explicit.
            |This command reads existing artifacts and does not generate SemanticDB or assess freshness.
            |""".stripMargin.trim
        )
      case "semanticdb-coverage" =>
        Some(
          """Usage:
            |  semantic-scala semanticdb-coverage --workspace <path> [--json]
            |
            |Coverage is based on a recursive Scala/Java source inventory.
            |Known generated and metadata directories are excluded before existing SemanticDB documents
            |are matched by exact or unique multi-segment URI suffixes.
            |Coverage is scoped to that inventory and does not imply freshness or build-target completeness.
            |Does not generate SemanticDB or run sbt.
            |""".stripMargin.trim
        )
      case "symbols" =>
        Some(
          """Usage:
            |  semantic-scala symbols --semanticdb <path> [--json]
            |
            |Reads one existing .semanticdb file and reports file-scoped symbols.
            |""".stripMargin.trim
        )
      case "usages" =>
        Some(
          """Usage:
            |  semantic-scala usages --workspace <path> --manifest <relative.json> --symbol <global-symbol> [--include-definitions] [--module <id>]... [--source-set <id>]... [--include-generated] [--limit <1..500>] [--json]
            |  semantic-scala usages --workspace <path> --manifest <relative.json> --file <relative-source> --line <n> --col <n> --semanticdb <relative-artifact> [selectors] [--json]
            |
            |Reports only exact ordinary SemanticDB occurrences in the selected caller-declared manifest scope.
            |It does not discover artifacts, does not generate SemanticDB, does not run a build, and does not refresh existing artifacts.
            |It excludes synthetics, inferred/desugared references, selected contexts, and override/dispatch families.
            |NoUsagesObserved never means globally unused.
            |Point inputs are one-based UTF-16; output ranges are zero-based UTF-16.
            |Manifest, source, artifact, and emitted evidence paths are workspace-relative and privacy-bounded.
            |""".stripMargin.trim
        )
      case "symbol-at" =>
        Some(
          """Usage:
            |  semantic-scala symbol-at --file <path> --line <n> --col <n> [--json]
            |
            |Queries the symbol at a one-based source line and column.
            |""".stripMargin.trim
        )
      case "infer-type" =>
        Some(
          """Usage:
            |  semantic-scala infer-type --file <path> --line <n> --col <n> [--workspace <path>] [--classpath <entry>]... [--json]
            |  semantic-scala infer-type --file <path> --line <n> --col <n> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] [--json]
            |
            |Queries compiler-rendered hover type/signature evidence at a one-based line and UTF-16 column.
            |Explicit classpath entries are compiled directories or JAR files and do not run a build.
            |The opt-in sbt mode requires an explicit workspace, project, and Compile/Test configuration
            |and is mutually exclusive with --classpath. Cache mode defaults to fresh. Fresh runs sbt without
            |persistent cache access; refresh runs sbt and atomically replaces one private entry; reuse does
            |not run sbt and requires matching bounded evidence. Matching evidence does not prove arbitrary
            |sbt inputs are fresh.
            |""".stripMargin.trim
        )
      case "infer-type-batch" =>
        Some(
          """Usage:
            |  semantic-scala infer-type-batch --requests <batch-request.json> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] --json
            |
            |Reads a bounded semantic-scala.infer-type-batch-request.v1 document and returns
            |semantic-scala.infer-type-batch-result.v1. The ordered batch shares exactly one
            |explicit sbt/cache context operation and one sequential presentation-compiler
            |session. Fresh is the default; persistent reuse remains explicit.
            |""".stripMargin.trim
        )
      case "reconcile-symbol" =>
        Some(
          """Usage:
            |  semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> [--json]
            |
            |Compares SemanticDB and presentation compiler symbol facts for a one-based source position.
            |""".stripMargin.trim
        )
      case "effect-summary" =>
        Some(
          """Usage:
            |  semantic-scala effect-summary --file <path> [--json]
            |
            |Summarizes declared method return types and classifies obvious outer effect types.
            |""".stripMargin.trim
        )
      case _ =>
        None
