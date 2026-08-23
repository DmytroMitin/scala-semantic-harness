package semantic.harness.cli

import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathCacheMode
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.semanticdb_reader.UsagesCliTarget
import semantic.harness.semanticdb_reader.UsagesPublicSelectors

enum CliCommand:
  case Help(topic: Option[String])
  case Version
  case Compile(
    sbtProject: Option[SbtProjectId],
    json: Boolean,
    sbtJavaHome: Option[String] = None
  )
  case Test(
    sbtProject: Option[SbtProjectId],
    json: Boolean,
    sbtJavaHome: Option[String] = None
  )
  case Errors(
    sbtProject: Option[SbtProjectId],
    json: Boolean,
    sbtJavaHome: Option[String] = None
  )
  case SemanticdbStatus(workspace: String, schemaVersion: SemanticdbStatusVersion, json: Boolean)
  case SemanticdbCoverage(workspace: String, json: Boolean)
  case SemanticdbForSource(file: String, workspace: String, json: Boolean)
  case PointEvidence(file: String, workspace: String, line: Int, column: Int, json: Boolean)
  case TastyPointEvidence(
    workspace: String,
    sbtProject: SbtProjectId,
    file: String,
    line: Int,
    column: Int,
    sbtJavaHome: Option[String],
    json: Boolean
  )
  case Symbols(semanticdb: String, json: Boolean)
  case Usages(
    workspace: String,
    manifest: String,
    target: UsagesCliTarget,
    selectors: UsagesPublicSelectors,
    returnedOccurrenceLimit: Int,
    json: Boolean
  )
  case SymbolAt(file: String, line: Int, column: Int, json: Boolean)
  case InferType(
    file: String,
    line: Int,
    column: Int,
    workspace: Option[String],
    classpathEntries: List[String],
    sbtProject: Option[SbtProjectId],
    sbtConfiguration: Option[SbtClasspathConfiguration],
    sbtCacheMode: Option[SbtClasspathCacheMode],
    json: Boolean,
    sbtJavaHome: Option[String] = None
  )
  case InferTypeBatch(
    requests: String,
    workspace: String,
    sbtProject: SbtProjectId,
    sbtConfiguration: SbtClasspathConfiguration,
    sbtCacheMode: SbtClasspathCacheMode,
    json: Boolean,
    sbtJavaHome: Option[String] = None
  )
  case ReconcileSymbol(file: String, line: Int, column: Int, semanticdb: String, json: Boolean)
  case EffectSummary(file: String, json: Boolean)

enum SemanticdbStatusVersion:
  case V1
  case V2

enum ParseResult:
  case Parsed(command: CliCommand)
  case Invalid(message: String, usagesJson: Option[Boolean] = None)

final case class CliResult(
  stdout: Option[String],
  stderr: Option[String],
  exitCode: Int
)
