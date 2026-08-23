package semantic.harness.cli

import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathCacheMode
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.semanticdb_reader.UsagesCliTarget
import semantic.harness.semanticdb_reader.UsagesPublicSelectors
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.util.Try

object CliParser:
  def parse(args: List[String]): ParseResult =
    args match
      case Nil | "--help" :: Nil | "help" :: Nil =>
        ParseResult.Parsed(CliCommand.Help(None))
      case "--version" :: Nil | "version" :: Nil =>
        ParseResult.Parsed(CliCommand.Version)
      case "help" :: topic :: Nil =>
        ParseResult.Parsed(CliCommand.Help(Some(topic)))
      case "compile" :: rest =>
        parseBuildCommand("compile", rest, CliCommand.Compile.apply)
      case "test" :: rest =>
        parseBuildCommand("test", rest, CliCommand.Test.apply)
      case "errors" :: rest =>
        parseBuildCommand("errors", rest, CliCommand.Errors.apply)
      case "semanticdb-status" :: rest =>
        parseSemanticdbStatus(rest)
      case "semanticdb-coverage" :: rest =>
        parseSemanticdbCoverage(rest)
      case "semanticdb-for-source" :: rest =>
        parseSemanticdbForSource(rest)
      case "point-evidence" :: rest =>
        parsePointEvidence(rest)
      case "symbols" :: rest =>
        parseSymbols(rest)
      case "usages" :: rest =>
        parseUsages(rest)
      case "symbol-at" :: rest =>
        parseSymbolAt(rest)
      case "infer-type" :: rest =>
        parseInferType(rest)
      case "infer-type-batch" :: rest =>
        parseInferTypeBatch(rest)
      case "reconcile-symbol" :: rest =>
        parseReconcileSymbol(rest)
      case "effect-summary" :: rest =>
        parseEffectSummary(rest)
      case unknown =>
        ParseResult.Invalid(s"Unknown command: ${unknown.mkString(" ")}")

  private def parseUsages(args: List[String]): ParseResult =
    final case class Options(
      workspace: Option[String] = None,
      manifest: Option[String] = None,
      symbol: Option[String] = None,
      file: Option[String] = None,
      line: Option[String] = None,
      column: Option[String] = None,
      semanticdb: Option[String] = None,
      includeDefinitions: Boolean = false,
      includeDefinitionsSeen: Boolean = false,
      modules: List[String] = Nil,
      sourceSets: List[String] = Nil,
      includeGenerated: Boolean = false,
      includeGeneratedSeen: Boolean = false,
      limit: Option[String] = None,
      json: Boolean = false,
      jsonSeen: Boolean = false
    )

    def invalid(message: String): ParseResult =
      ParseResult.Invalid(message, usagesJson = Some(args.contains("--json")))

    def duplicate(name: String): Left[String, Nothing] =
      Left(s"Option may only be supplied once for usages: $name")

    def loop(rest: List[String], options: Options): Either[String, Options] =
      rest match
        case Nil => Right(options)
        case "--json" :: tail =>
          if options.jsonSeen then duplicate("--json")
          else loop(tail, options.copy(json = true, jsonSeen = true))
        case "--include-definitions" :: tail =>
          if options.includeDefinitionsSeen then duplicate("--include-definitions")
          else loop(tail, options.copy(includeDefinitions = true, includeDefinitionsSeen = true))
        case "--include-generated" :: tail =>
          if options.includeGeneratedSeen then duplicate("--include-generated")
          else loop(tail, options.copy(includeGenerated = true, includeGeneratedSeen = true))
        case "--workspace" :: value :: tail if !value.startsWith("--") =>
          if options.workspace.nonEmpty then duplicate("--workspace")
          else loop(tail, options.copy(workspace = Some(value)))
        case "--manifest" :: value :: tail if !value.startsWith("--") =>
          if options.manifest.nonEmpty then duplicate("--manifest")
          else loop(tail, options.copy(manifest = Some(value)))
        case "--symbol" :: value :: tail if !value.startsWith("--") =>
          if options.symbol.nonEmpty then duplicate("--symbol")
          else loop(tail, options.copy(symbol = Some(value)))
        case "--file" :: value :: tail if !value.startsWith("--") =>
          if options.file.nonEmpty then duplicate("--file")
          else loop(tail, options.copy(file = Some(value)))
        case "--line" :: value :: tail if !value.startsWith("--") =>
          if options.line.nonEmpty then duplicate("--line")
          else loop(tail, options.copy(line = Some(value)))
        case "--col" :: value :: tail if !value.startsWith("--") =>
          if options.column.nonEmpty then duplicate("--col")
          else loop(tail, options.copy(column = Some(value)))
        case "--semanticdb" :: value :: tail if !value.startsWith("--") =>
          if options.semanticdb.nonEmpty then duplicate("--semanticdb")
          else loop(tail, options.copy(semanticdb = Some(value)))
        case "--module" :: value :: tail if !value.startsWith("--") =>
          loop(tail, options.copy(modules = options.modules :+ value))
        case "--source-set" :: value :: tail if !value.startsWith("--") =>
          loop(tail, options.copy(sourceSets = options.sourceSets :+ value))
        case "--limit" :: value :: tail if !value.startsWith("--") =>
          if options.limit.nonEmpty then duplicate("--limit")
          else loop(tail, options.copy(limit = Some(value)))
        case _ => Left(s"Invalid arguments for usages: ${rest.mkString(" ")}")

    def positiveInt(name: String, value: String): Either[String, Int] =
      value.toIntOption.filter(_ > 0).toRight(s"$name must be a positive 32-bit integer for usages")

    def returnedLimit(value: Option[String]): Either[String, Int] =
      value match
        case None => Right(500)
        case Some(text) =>
          text.toIntOption.filter(current => current >= 1 && current <= 500)
            .toRight("--limit must be between 1 and 500 for usages")

    def validIdentifier(value: String): Boolean =
      value.matches("[A-Za-z0-9][A-Za-z0-9._-]*") &&
        value.getBytes(StandardCharsets.UTF_8).length <= 1024

    loop(args, Options()) match
      case Left(message) => invalid(message)
      case Right(options) =>
        val normalizedModules = options.modules.distinct.sorted
        val normalizedSourceSets = options.sourceSets.distinct.sorted
        val identifiersValid =
          normalizedModules.size <= 256 && normalizedSourceSets.size <= 256 &&
            (normalizedModules ++ normalizedSourceSets).forall(validIdentifier)
        val symbolValid = options.symbol.forall { value =>
          val local = value.matches("local[0-9]+")
          val global = value.nonEmpty && !value.startsWith(";") && Set('#', '.', ')', '/', ']').contains(value.last)
          value.nonEmpty && value.getBytes(StandardCharsets.UTF_8).length <= 1024 &&
            !value.exists(_.isControl) && (local || global)
        }
        val common = for
          workspace <- options.workspace.toRight("--workspace is required for usages")
          manifest <- options.manifest.toRight("--manifest is required for usages")
          _ <- Either.cond(workspace.nonEmpty, (), "--workspace must be non-empty for usages")
          _ <- Either.cond(manifest.nonEmpty, (), "--manifest must be non-empty for usages")
          limit <- returnedLimit(options.limit)
          _ <- Either.cond(identifiersValid, (), "Selector identifiers are invalid or exceed 256 values")
          _ <- Either.cond(symbolValid, (), "--symbol must be a local marker or global SemanticDB symbol within 1024 UTF-8 bytes")
        yield (workspace, manifest, limit)

        val pointValues = List(options.file, options.line, options.column, options.semanticdb)
        val target = (options.symbol, pointValues.forall(_.isEmpty), pointValues.forall(_.nonEmpty)) match
          case (Some(symbol), true, false) => Right(UsagesCliTarget.ExplicitGlobal(symbol))
          case (None, false, true) =>
            for
              line <- positiveInt("--line", options.line.get)
              column <- positiveInt("--col", options.column.get)
            yield UsagesCliTarget.Point(options.file.get, line, column, options.semanticdb.get)
          case _ => Left("usages requires exactly --symbol or the complete --file/--line/--col/--semanticdb target")

        common.flatMap { case (workspace, manifest, limit) =>
          target.map(selectedTarget =>
            CliCommand.Usages(
              workspace = workspace,
              manifest = manifest,
              target = selectedTarget,
              selectors = UsagesPublicSelectors(
                includeDefinitions = options.includeDefinitions,
                modules = normalizedModules,
                sourceSets = normalizedSourceSets,
                includeGenerated = options.includeGenerated
              ),
              returnedOccurrenceLimit = limit,
              json = options.json
            )
          )
        } match
          case Right(command) => ParseResult.Parsed(command)
          case Left(message)  => invalid(message)

  private def parseBuildCommand(
    commandName: String,
    args: List[String],
    build: (Option[SbtProjectId], Boolean, Option[String]) => CliCommand
  ): ParseResult =
    final case class Options(
      sbtProject: Option[SbtProjectId] = None,
      sbtJavaHome: Option[String] = None,
      json: Boolean = false,
      jsonSeen: Boolean = false
    )

    def loop(rest: List[String], options: Options): Either[String, Options] =
      rest match
        case Nil => Right(options)
        case "--json" :: tail =>
          if options.jsonSeen then Left(s"Option may only be supplied once for $commandName: --json")
          else loop(tail, options.copy(json = true, jsonSeen = true))
        case "--sbt-project" :: value :: tail if !value.startsWith("--") =>
          if options.sbtProject.nonEmpty then
            Left(s"Option may only be supplied once for $commandName: --sbt-project")
          else
            SbtProjectId
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-project for $commandName: $message")
              .flatMap(project => loop(tail, options.copy(sbtProject = Some(project))))
        case "--sbt-java-home" :: value :: tail if !value.startsWith("--") =>
          if options.sbtJavaHome.nonEmpty then
            Left(s"Option may only be supplied once for $commandName: --sbt-java-home")
          else
            validateAbsoluteJavaHome(value, commandName)
              .flatMap(validated => loop(tail, options.copy(sbtJavaHome = Some(validated))))
        case other => Left(s"Invalid arguments for $commandName: ${other.mkString(" ")}")

    loop(args, Options()) match
      case Right(options) =>
        ParseResult.Parsed(build(options.sbtProject, options.json, options.sbtJavaHome))
      case Left(message)  => ParseResult.Invalid(message)

  private def validateAbsoluteJavaHome(
      value: String,
      commandName: String
  ): Either[String, String] =
    Try(Path.of(value)).toEither
      .left
      .map(_ => s"Invalid --sbt-java-home for $commandName: expected an absolute directory")
      .flatMap(path =>
        Either.cond(
          value.nonEmpty && path.isAbsolute,
          value,
          s"Invalid --sbt-java-home for $commandName: expected an absolute directory"
        )
      )

  private def parseSymbols(args: List[String]): ParseResult =
    args match
      case "--semanticdb" :: path :: Nil =>
        ParseResult.Parsed(CliCommand.Symbols(path, json = false))
      case "--semanticdb" :: path :: "--json" :: Nil =>
        ParseResult.Parsed(CliCommand.Symbols(path, json = true))
      case "--json" :: "--semanticdb" :: path :: Nil =>
        ParseResult.Parsed(CliCommand.Symbols(path, json = true))
      case other =>
        ParseResult.Invalid(s"Invalid arguments for symbols: ${other.mkString(" ")}")

  private def parseSemanticdbStatus(args: List[String]): ParseResult =
    parseKeyValueArgs("semanticdb-status", args).flatMap { case (options, json) =>
      val allowed = Set("--workspace", "--schema-version")
      val unsupported = options.keySet.diff(allowed)
      if unsupported.nonEmpty then Left(s"Invalid arguments for semanticdb-status: ${unsupported.toList.sorted.mkString(" ")}")
      else
        options.get("--schema-version") match
          case Some(value) if value != "v1" && value != "v2" =>
            Left(s"Unsupported schema version for semanticdb-status: $value (supported: v1, v2)")
          case schemaVersion =>
            options.get("--workspace") match
              case Some(workspace) =>
                val version =
                  if schemaVersion.contains("v2") then SemanticdbStatusVersion.V2
                  else SemanticdbStatusVersion.V1
                Right(CliCommand.SemanticdbStatus(workspace, version, json))
              case None =>
                Left(s"Invalid arguments for semanticdb-status: ${args.mkString(" ")}")
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parseSemanticdbForSource(args: List[String]): ParseResult =
    parseKeyValueArgs("semanticdb-for-source", args).flatMap { case (options, json) =>
      val allowed = Set("--file", "--workspace")
      val unsupported = options.keySet.diff(allowed)
      if unsupported.nonEmpty then Left(s"Invalid arguments for semanticdb-for-source: ${unsupported.toList.sorted.mkString(" ")}")
      else
        (options.get("--file"), options.get("--workspace")) match
          case (Some(file), Some(workspace)) => Right(CliCommand.SemanticdbForSource(file, workspace, json))
          case _ => Left(s"Invalid arguments for semanticdb-for-source: ${args.mkString(" ")}")
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parsePointEvidence(args: List[String]): ParseResult =
    final case class Options(
      values: Map[String, String] = Map.empty,
      json: Boolean = false,
      jsonSeen: Boolean = false
    )

    def loop(rest: List[String], options: Options): Either[String, Options] =
      rest match
        case Nil => Right(options)
        case "--json" :: tail =>
          if options.jsonSeen then Left("Option may only be supplied once for point-evidence: --json")
          else loop(tail, options.copy(json = true, jsonSeen = true))
        case key :: value :: tail if key.startsWith("--") && !value.startsWith("--") =>
          if options.values.contains(key) then Left(s"Option may only be supplied once for point-evidence: $key")
          else loop(tail, options.copy(values = options.values.updated(key, value)))
        case other => Left(s"Invalid arguments for point-evidence: ${other.mkString(" ")}")

    loop(args, Options()).flatMap { options =>
      val allowed = Set("--file", "--workspace", "--line", "--col")
      val unsupported = options.values.keySet.diff(allowed)
      if unsupported.nonEmpty then Left(s"Invalid arguments for point-evidence: ${unsupported.toList.sorted.mkString(" ")}")
      else
        (
          options.values.get("--file"),
          options.values.get("--workspace"),
          options.values.get("--line"),
          options.values.get("--col")
        ) match
          case (Some(file), Some(workspace), Some(lineText), Some(columnText)) =>
            for
              line <- parsePositiveInt("point-evidence", "line", lineText)
              column <- parsePositiveInt("point-evidence", "col", columnText)
            yield CliCommand.PointEvidence(file, workspace, line, column, options.json)
          case _ => Left(s"Invalid arguments for point-evidence: ${args.mkString(" ")}")
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parseSemanticdbCoverage(args: List[String]): ParseResult =
    parseKeyValueArgs("semanticdb-coverage", args).flatMap { case (options, json) =>
      val allowed = Set("--workspace")
      val unsupported = options.keySet.diff(allowed)
      if unsupported.nonEmpty then
        Left(s"Invalid arguments for semanticdb-coverage: ${unsupported.toList.sorted.mkString(" ")}")
      else
        options.get("--workspace") match
          case Some(workspace) => Right(CliCommand.SemanticdbCoverage(workspace, json))
          case None            => Left(s"Invalid arguments for semanticdb-coverage: ${args.mkString(" ")}")
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parseSymbolAt(args: List[String]): ParseResult =
    def parse(options: Map[String, String], json: Boolean): ParseResult =
      (options.get("--file"), options.get("--line"), options.get("--col")) match
        case (Some(file), Some(lineText), Some(columnText)) =>
          parsePositiveInt("symbol-at", "line", lineText).flatMap { line =>
            parsePositiveInt("symbol-at", "col", columnText).map { column =>
              CliCommand.SymbolAt(file, line, column, json)
            }
          } match
            case Right(command) => ParseResult.Parsed(command)
            case Left(message)  => ParseResult.Invalid(message)
        case _ =>
          ParseResult.Invalid(s"Invalid arguments for symbol-at: ${args.mkString(" ")}")

    parseKeyValueArgs("symbol-at", args).flatMap { case (options, json) =>
      val allowed = Set("--file", "--line", "--col")
      val unsupported = options.keySet.diff(allowed)
      if unsupported.nonEmpty then Left(s"Invalid arguments for symbol-at: ${unsupported.toList.sorted.mkString(" ")}")
      else Right((options, json))
    } match
      case Right((options, json)) => parse(options, json)
      case Left(message)          => ParseResult.Invalid(message)

  private def parseInferType(args: List[String]): ParseResult =
    final case class Options(
      file: Option[String] = None,
      line: Option[String] = None,
      column: Option[String] = None,
      workspace: Option[String] = None,
      classpathEntries: List[String] = Nil,
      sbtProject: Option[SbtProjectId] = None,
      sbtConfiguration: Option[SbtClasspathConfiguration] = None,
      sbtCacheMode: Option[SbtClasspathCacheMode] = None,
      sbtJavaHome: Option[String] = None,
      json: Boolean = false
    )

    def duplicate(name: String): Left[String, Nothing] =
      Left(s"Option may only be supplied once for infer-type: $name")

    def loop(rest: List[String], options: Options): Either[String, Options] =
      rest match
        case Nil =>
          Right(options)
        case "--json" :: tail =>
          loop(tail, options.copy(json = true))
        case "--file" :: value :: tail if !value.startsWith("--") =>
          if options.file.nonEmpty then duplicate("--file")
          else loop(tail, options.copy(file = Some(value)))
        case "--line" :: value :: tail if !value.startsWith("--") =>
          if options.line.nonEmpty then duplicate("--line")
          else loop(tail, options.copy(line = Some(value)))
        case "--col" :: value :: tail if !value.startsWith("--") =>
          if options.column.nonEmpty then duplicate("--col")
          else loop(tail, options.copy(column = Some(value)))
        case "--workspace" :: value :: tail if !value.startsWith("--") =>
          if options.workspace.nonEmpty then duplicate("--workspace")
          else loop(tail, options.copy(workspace = Some(value)))
        case "--classpath" :: value :: tail if !value.startsWith("--") =>
          loop(tail, options.copy(classpathEntries = options.classpathEntries :+ value))
        case "--sbt-project" :: value :: tail if !value.startsWith("--") =>
          if options.sbtProject.nonEmpty then duplicate("--sbt-project")
          else
            SbtProjectId
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-project for infer-type: $message")
              .flatMap(project => loop(tail, options.copy(sbtProject = Some(project))))
        case "--sbt-configuration" :: value :: tail if !value.startsWith("--") =>
          if options.sbtConfiguration.nonEmpty then duplicate("--sbt-configuration")
          else
            SbtClasspathConfiguration
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-configuration for infer-type: $message")
              .flatMap(configuration =>
                loop(tail, options.copy(sbtConfiguration = Some(configuration)))
              )
        case "--sbt-cache-mode" :: value :: tail if !value.startsWith("--") =>
          if options.sbtCacheMode.nonEmpty then duplicate("--sbt-cache-mode")
          else
            SbtClasspathCacheMode
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-cache-mode for infer-type: $message")
              .flatMap(mode => loop(tail, options.copy(sbtCacheMode = Some(mode))))
        case "--sbt-java-home" :: value :: tail if !value.startsWith("--") =>
          if options.sbtJavaHome.nonEmpty then duplicate("--sbt-java-home")
          else
            validateAbsoluteJavaHome(value, "infer-type")
              .flatMap(validated => loop(tail, options.copy(sbtJavaHome = Some(validated))))
        case other =>
          Left(s"Invalid arguments for infer-type: ${other.mkString(" ")}")

    loop(args, Options()).flatMap { options =>
      val contextValidation =
        (options.sbtProject, options.sbtConfiguration) match
          case (Some(_), Some(_)) if options.workspace.isEmpty =>
            Left("sbt-backed infer-type requires --workspace")
          case (Some(_), Some(_)) if options.classpathEntries.nonEmpty =>
            Left("sbt-backed infer-type is mutually exclusive with --classpath")
          case (Some(_), Some(_)) =>
            Right(())
          case (Some(_), None) =>
            Left("--sbt-project and --sbt-configuration must be supplied together")
          case (None, Some(_)) =>
            Left("--sbt-project and --sbt-configuration must be supplied together")
          case (None, None) if options.sbtCacheMode.nonEmpty =>
            Left(
              "--sbt-cache-mode requires --workspace, --sbt-project, and --sbt-configuration"
            )
          case (None, None) if options.sbtJavaHome.nonEmpty =>
            Left(
              "--sbt-java-home requires --workspace, --sbt-project, and --sbt-configuration"
            )
          case _ =>
            Right(())

      contextValidation.flatMap { _ =>
        (options.file, options.line, options.column) match
          case (Some(file), Some(lineText), Some(columnText)) =>
            parsePositiveInt("infer-type", "line", lineText).flatMap { line =>
              parsePositiveInt("infer-type", "col", columnText).map { column =>
                CliCommand.InferType(
                  file = file,
                  line = line,
                  column = column,
                  workspace = options.workspace,
                  classpathEntries = options.classpathEntries,
                  sbtProject = options.sbtProject,
                  sbtConfiguration = options.sbtConfiguration,
                  sbtCacheMode =
                    options.sbtProject.map(_ =>
                      options.sbtCacheMode.getOrElse(SbtClasspathCacheMode.Fresh)
                    ),
                  json = options.json,
                  sbtJavaHome = options.sbtJavaHome
                )
              }
            }
          case _ =>
            Left(s"Invalid arguments for infer-type: ${args.mkString(" ")}")
      }
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parseInferTypeBatch(args: List[String]): ParseResult =
    final case class Options(
        requests: Option[String] = None,
        workspace: Option[String] = None,
        sbtProject: Option[SbtProjectId] = None,
        sbtConfiguration: Option[SbtClasspathConfiguration] = None,
        sbtCacheMode: Option[SbtClasspathCacheMode] = None,
        sbtJavaHome: Option[String] = None,
        json: Boolean = false
    )

    def duplicate(name: String): Left[String, Nothing] =
      Left(s"Option may only be supplied once for infer-type-batch: $name")

    def loop(rest: List[String], options: Options): Either[String, Options] =
      rest match
        case Nil => Right(options)
        case "--json" :: tail =>
          if options.json then duplicate("--json")
          else loop(tail, options.copy(json = true))
        case "--requests" :: value :: tail if !value.startsWith("--") =>
          if options.requests.nonEmpty then duplicate("--requests")
          else loop(tail, options.copy(requests = Some(value)))
        case "--workspace" :: value :: tail if !value.startsWith("--") =>
          if options.workspace.nonEmpty then duplicate("--workspace")
          else loop(tail, options.copy(workspace = Some(value)))
        case "--sbt-project" :: value :: tail if !value.startsWith("--") =>
          if options.sbtProject.nonEmpty then duplicate("--sbt-project")
          else
            SbtProjectId
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-project for infer-type-batch: $message")
              .flatMap(project => loop(tail, options.copy(sbtProject = Some(project))))
        case "--sbt-configuration" :: value :: tail if !value.startsWith("--") =>
          if options.sbtConfiguration.nonEmpty then duplicate("--sbt-configuration")
          else
            SbtClasspathConfiguration
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-configuration for infer-type-batch: $message")
              .flatMap(configuration =>
                loop(tail, options.copy(sbtConfiguration = Some(configuration)))
              )
        case "--sbt-cache-mode" :: value :: tail if !value.startsWith("--") =>
          if options.sbtCacheMode.nonEmpty then duplicate("--sbt-cache-mode")
          else
            SbtClasspathCacheMode
              .parse(value)
              .left
              .map(message => s"Invalid --sbt-cache-mode for infer-type-batch: $message")
              .flatMap(mode => loop(tail, options.copy(sbtCacheMode = Some(mode))))
        case "--sbt-java-home" :: value :: tail if !value.startsWith("--") =>
          if options.sbtJavaHome.nonEmpty then duplicate("--sbt-java-home")
          else
            validateAbsoluteJavaHome(value, "infer-type-batch")
              .flatMap(validated => loop(tail, options.copy(sbtJavaHome = Some(validated))))
        case other =>
          Left(s"Invalid arguments for infer-type-batch: ${other.mkString(" ")}")

    loop(args, Options()).flatMap { options =>
      (
        options.requests,
        options.workspace,
        options.sbtProject,
        options.sbtConfiguration,
        options.json
      ) match
        case (
              Some(requests),
              Some(workspace),
              Some(project),
              Some(configuration),
              true
            ) =>
          Right(
            CliCommand.InferTypeBatch(
              requests,
              workspace,
              project,
              configuration,
              options.sbtCacheMode.getOrElse(SbtClasspathCacheMode.Fresh),
              json = true,
              sbtJavaHome = options.sbtJavaHome
            )
          )
        case _ =>
          Left(
            "infer-type-batch requires --requests, --workspace, --sbt-project, --sbt-configuration, and --json"
          )
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parseReconcileSymbol(args: List[String]): ParseResult =
    def parse(options: Map[String, String], json: Boolean): ParseResult =
      (options.get("--file"), options.get("--line"), options.get("--col"), options.get("--semanticdb")) match
        case (Some(file), Some(lineText), Some(columnText), Some(semanticdb)) =>
          parsePositiveInt("reconcile-symbol", "line", lineText).flatMap { line =>
            parsePositiveInt("reconcile-symbol", "col", columnText).map { column =>
              CliCommand.ReconcileSymbol(file, line, column, semanticdb, json)
            }
          } match
            case Right(command) => ParseResult.Parsed(command)
            case Left(message)  => ParseResult.Invalid(message)
        case _ =>
          ParseResult.Invalid(s"Invalid arguments for reconcile-symbol: ${args.mkString(" ")}")

    parseKeyValueArgs("reconcile-symbol", args).flatMap { case (options, json) =>
      val allowed = Set("--file", "--line", "--col", "--semanticdb")
      val unsupported = options.keySet.diff(allowed)
      if unsupported.nonEmpty then Left(s"Invalid arguments for reconcile-symbol: ${unsupported.toList.sorted.mkString(" ")}")
      else Right((options, json))
    } match
      case Right((options, json)) => parse(options, json)
      case Left(message)          => ParseResult.Invalid(message)

  private def parseEffectSummary(args: List[String]): ParseResult =
    parseKeyValueArgs("effect-summary", args).flatMap { case (options, json) =>
      val allowed = Set("--file")
      val unsupported = options.keySet.diff(allowed)
      if unsupported.nonEmpty then Left(s"Invalid arguments for effect-summary: ${unsupported.toList.sorted.mkString(" ")}")
      else
        options.get("--file") match
          case Some(file) => Right(CliCommand.EffectSummary(file, json))
          case None       => Left(s"Invalid arguments for effect-summary: ${args.mkString(" ")}")
    } match
      case Right(command) => ParseResult.Parsed(command)
      case Left(message)  => ParseResult.Invalid(message)

  private def parseKeyValueArgs(
    commandName: String,
    args: List[String]
  ): Either[String, (Map[String, String], Boolean)] =
    def loop(rest: List[String], options: Map[String, String], json: Boolean): Either[String, (Map[String, String], Boolean)] =
      rest match
        case Nil => Right((options, json))
        case "--json" :: tail => loop(tail, options, json = true)
        case key :: value :: tail if key.startsWith("--") && !value.startsWith("--") =>
          loop(tail, options.updated(key, value), json)
        case other =>
          Left(s"Invalid arguments for $commandName: ${other.mkString(" ")}")

    loop(args, Map.empty, json = false)

  private def parsePositiveInt(commandName: String, name: String, value: String): Either[String, Int] =
    value.toIntOption.filter(_ > 0).toRight(s"Invalid $name for $commandName: $value")
