package semantic.harness.semanticdb_reader

import io.circe.Json
import io.circe.Printer
import java.nio.file.Path

/** Test-scope, non-public diagnostic reporter for artifact-hint evidence captures. */
object SemanticdbArtifactHintsTrialMain:
  private val Format = "semantic-scala.internal-artifact-hints-trial.v1"
  private val JsonPrinter = Printer.spaces2.copy(dropNullValues = false)

  def main(arguments: Array[String]): Unit =
    if arguments.length != 2 then
      throw new IllegalArgumentException(
        "Expected: SemanticdbArtifactHintsTrialMain <workspace> <sanitized-workspace-label>"
      )

    val workspace = Path.of(arguments(0))
    val workspaceLabel = arguments(1)
    val inventory = SemanticdbArtifactInventory
      .inspect(workspace)
      .fold(message => throw new IllegalArgumentException(message), identity)

    println(JsonPrinter.print(snapshot(inventory, workspaceLabel)))

  private def snapshot(
    inventory: SemanticdbArtifactInventory,
    workspaceLabel: String
  ): Json =
    val contentByPath = inventory.contents.flatMap { content =>
      content.artifactPaths.map(path => path -> content)
    }.toMap
    val artifacts = inventory.candidates.sortBy(_.semanticdb).map { candidate =>
      val content = contentByPath.get(candidate.semanticdb)
      artifact(candidate, content)
    }

    Json.obj(
      "format" -> Json.fromString(Format),
      "nonPublicDiagnostic" -> Json.fromBoolean(true),
      "workspace" -> Json.fromString(workspaceLabel),
      "artifacts" -> Json.fromValues(artifacts)
    )

  private def artifact(
    candidate: SemanticdbArtifactCandidate,
    content: Option[SemanticdbArtifactContent]
  ): Json =
    val hints = candidate.hints
    Json.obj(
      "artifactPath" -> Json.fromString(candidate.semanticdb),
      "contentHash" -> candidate.contentHash.fold(Json.Null)(Json.fromString),
      "duplicateGroupId" -> content.fold(Json.Null)(value => Json.fromString(value.contentHash)),
      "duplicateMemberPaths" -> Json.fromValues(
        content.toList.flatMap(_.artifactPaths).sorted.map(Json.fromString)
      ),
      "documentUris" -> Json.fromValues(candidate.documents.map(_.uri).map(Json.fromString)),
      "parseStatus" -> Json.fromString(candidate.parseStatus),
      "workspaceScope" -> Json.fromString(hints.workspaceScope.toString),
      "origins" -> qualified(hints.origins),
      "roles" -> qualified(hints.roles),
      "sourceSets" -> qualified(hints.sourceSets),
      "languages" -> qualified(hints.languages),
      "outputKinds" -> qualified(hints.outputKinds),
      "producers" -> qualified(hints.producers),
      "modules" -> qualified(hints.modules),
      "configurations" -> qualified(hints.configurations),
      "scalaVersions" -> qualified(hints.scalaVersions),
      "scalaBinaryVersions" -> qualified(hints.scalaBinaryVersions),
      "targetDirectories" -> qualified(hints.targetDirectories),
      "generatedSource" -> qualified(hints.generatedSource),
      "evidence" -> Json.fromValues(hints.evidence.map(evidence)),
      "unresolved" -> Json.fromValues(hints.unresolved.map(Json.fromString))
    )

  private def qualified[A](values: List[QualifiedArtifactHint[A]]): Json =
    Json.fromValues(values.map { value =>
      Json.obj(
        "value" -> Json.fromString(value.value.toString),
        "confidence" -> Json.fromString(value.confidence.toString),
        "evidenceIds" -> Json.fromValues(value.evidenceIds.map(Json.fromString))
      )
    })

  private def evidence(value: ArtifactHintEvidence): Json =
    Json.obj(
      "id" -> Json.fromString(value.id),
      "kind" -> Json.fromString(value.kind.toString),
      "source" -> Json.fromString(value.source),
      "value" -> Json.fromString(value.value)
    )
