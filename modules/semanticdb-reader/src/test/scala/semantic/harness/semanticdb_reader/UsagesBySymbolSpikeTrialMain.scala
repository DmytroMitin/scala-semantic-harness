package semantic.harness.semanticdb_reader

import io.circe.Json
import io.circe.Printer
import java.nio.file.Path

/** Test-scope, non-public reporter for Task 087 sanitized evidence captures. */
object UsagesBySymbolSpikeTrialMain:
  private val Format = "semantic-scala.internal-usages-by-symbol-trial.v1"
  private val Printer = io.circe.Printer.spaces2.copy(dropNullValues = false)

  def main(arguments: Array[String]): Unit =
    if arguments.length != 2 then
      throw new IllegalArgumentException(
        "Expected: UsagesBySymbolSpikeTrialMain <task086|repository> <workspace>"
      )

    val mode = arguments(0)
    val workspace = Path.of(arguments(1))
    val request =
      mode match
        case "task086"    => task086Request(workspace)
        case "repository" => repositoryRequest(workspace)
        case _             => throw new IllegalArgumentException("Unknown sanitized trial mode")
    val started = java.lang.System.nanoTime()
    val result = UsagesBySymbolSpike.run(request)
    val elapsedNanos = java.lang.System.nanoTime() - started
    result match
      case Left(value) =>
        println(
          Printer.print(
            Json.obj(
              "format" -> Json.fromString(Format),
              "nonPublicDiagnostic" -> Json.fromBoolean(true),
              "mode" -> Json.fromString(mode),
              "failureKind" -> Json.fromString(value.kind.toString),
              "message" -> Json.fromString(value.message),
              "elapsedNanos" -> Json.fromLong(elapsedNanos)
            )
          )
        )
        sys.exit(1)
      case Right(value) =>
        println(Printer.print(snapshot(mode, value, elapsedNanos)))

  private def task086Request(workspace: Path): UsagesBySymbolRequest =
    val sources = List(
      DeclaredUsageSource(
        "fixtures/module-a/src/main/scala/task086/api/Definitions.scala",
        "module-a",
        "main",
        generated = false
      ),
      DeclaredUsageSource(
        "fixtures/module-b/src/main/scala/task086/consumer/Uses.scala",
        "module-b",
        "main",
        generated = false
      ),
      DeclaredUsageSource(
        "fixtures/module-b/src/test/scala/task086/consumer/ConsumerSpec.scala",
        "module-b",
        "test",
        generated = false
      )
    )
    val artifacts = List(
      DeclaredUsageArtifact(
        "semanticdb-a/META-INF/semanticdb/module-a/src/main/scala/task086/api/Definitions.scala.semanticdb",
        "module-a",
        "main",
        generated = false
      ),
      DeclaredUsageArtifact(
        "semanticdb-b/META-INF/semanticdb/module-b/src/main/scala/task086/consumer/Uses.scala.semanticdb",
        "module-b",
        "main",
        generated = false
      ),
      DeclaredUsageArtifact(
        "duplicate/Uses.scala.semanticdb",
        "module-b",
        "main",
        generated = false
      ),
      DeclaredUsageArtifact(
        "semanticdb-test/META-INF/semanticdb/module-b/src/test/scala/task086/consumer/ConsumerSpec.scala.semanticdb",
        "module-b",
        "test",
        generated = false
      )
    )
    UsagesBySymbolRequest(
      workspace = workspace,
      inventoryClosed = true,
      sources = sources,
      artifacts = artifacts,
      target = UsagesBySymbolTarget.ExplicitGlobal("task086/api/Service#run()."),
      selectors = UsagesBySymbolSelectors(
        includeDefinitions = true,
        includeGenerated = true
      )
    )

  private def repositoryRequest(workspace: Path): UsagesBySymbolRequest =
    UsagesBySymbolRequest(
      workspace = workspace,
      inventoryClosed = true,
      sources = List(
        DeclaredUsageSource(
          "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala",
          "semanticdb-reader",
          "test-resource",
          generated = false
        )
      ),
      artifacts = List(
        DeclaredUsageArtifact(
          "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb",
          "semanticdb-reader",
          "test-resource",
          generated = false
        )
      ),
      target = UsagesBySymbolTarget.ExplicitGlobal("example/Main."),
      selectors = UsagesBySymbolSelectors(
        includeDefinitions = true,
        includeGenerated = true
      )
    )

  private def snapshot(
    mode: String,
    report: UsagesBySymbolReport,
    elapsedNanos: Long
  ): Json =
    Json.obj(
      "format" -> Json.fromString(Format),
      "nonPublicDiagnostic" -> Json.fromBoolean(true),
      "mode" -> Json.fromString(mode),
      "state" -> Json.fromString(report.state.toString),
      "targetIdentity" -> report.target.fold(Json.Null)(value =>
        Json.fromString(value.identityKind.toString)
      ),
      "stableTarget" -> report.target.flatMap(_.stableSymbol).fold(Json.Null)(Json.fromString),
      "occurrences" -> Json.fromValues(report.occurrences.map(occurrence)),
      "duplicateGroups" -> Json.fromValues(report.duplicateGroups.map(duplicateGroup)),
      "coverage" -> coverage(report.coverage),
      "limitHits" -> Json.fromValues(report.limits.hit.map(Json.fromString)),
      "warnings" -> Json.fromValues(report.warnings.map(Json.fromString)),
      "elapsedNanos" -> Json.fromLong(elapsedNanos)
    )

  private def occurrence(value: UsageOccurrenceEvidence): Json =
    Json.obj(
      "role" -> Json.fromString(value.role.toString),
      "source" -> value.source.fold(Json.Null)(Json.fromString),
      "range" -> Json.obj(
        "startLine" -> Json.fromInt(value.range.startLine),
        "startCharacter" -> Json.fromInt(value.range.startCharacter),
        "endLine" -> Json.fromInt(value.range.endLine),
        "endCharacter" -> Json.fromInt(value.range.endCharacter)
      ),
      "module" -> Json.fromString(value.module),
      "sourceSet" -> Json.fromString(value.sourceSet),
      "generated" -> Json.fromBoolean(value.generated),
      "freshness" -> Json.fromString(value.freshness.toString),
      "artifactGroupId" -> Json.fromString(value.artifactGroupId)
    )

  private def duplicateGroup(value: UsageDuplicateGroupEvidence): Json =
    Json.obj(
      "groupId" -> Json.fromString(value.groupId),
      "copyCount" -> Json.fromInt(value.copyCount),
      "representative" -> Json.fromString(value.representative),
      "samplePaths" -> Json.fromValues(value.samplePaths.map(Json.fromString)),
      "pathsTruncated" -> Json.fromBoolean(value.pathsTruncated)
    )

  private def coverage(value: UsageCoverageEvidence): Json =
    Json.obj(
      "inventoryClosed" -> Json.fromBoolean(value.inventoryClosed),
      "declaredSources" -> Json.fromInt(value.declaredSources),
      "selectedSources" -> Json.fromInt(value.selectedSources),
      "declaredArtifacts" -> Json.fromInt(value.declaredArtifacts),
      "selectedArtifacts" -> Json.fromInt(value.selectedArtifacts),
      "rawArtifactBytes" -> Json.fromLong(value.rawArtifactBytes),
      "uniqueArtifactContents" -> Json.fromInt(value.uniqueArtifactContents),
      "duplicateCopies" -> Json.fromInt(value.duplicateCopies),
      "parsedDocuments" -> Json.fromInt(value.parsedDocuments),
      "mappedDocuments" -> Json.fromInt(value.mappedDocuments),
      "unmappedDocuments" -> Json.fromInt(value.unmappedDocuments),
      "ambiguousDocuments" -> Json.fromInt(value.ambiguousDocuments),
      "freshDocuments" -> Json.fromInt(value.freshDocuments),
      "staleDocuments" -> Json.fromInt(value.staleDocuments),
      "missingDigestDocuments" -> Json.fromInt(value.missingDigestDocuments),
      "documentsWithoutOccurrences" -> Json.fromInt(value.documentsWithoutOccurrences),
      "documentsWithSynthetics" -> Json.fromInt(value.documentsWithSynthetics),
      "scannedOrdinaryOccurrences" -> Json.fromInt(value.scannedOrdinaryOccurrences),
      "matchingOccurrences" -> Json.fromInt(value.matchingOccurrences),
      "returnedOccurrences" -> Json.fromInt(value.returnedOccurrences)
    )
