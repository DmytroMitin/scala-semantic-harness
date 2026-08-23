package semantic.harness.tasty

import io.circe.parser.decode
import io.circe.syntax.*

class TastyPointEvidenceModelsSuite extends munit.FunSuite:
  test("public enums and schema use the frozen exact strings"):
    val statuses = List(
      TastyPointEvidenceStatus.Resolved -> "Resolved",
      TastyPointEvidenceStatus.NoTypedTreeAtPoint -> "NoTypedTreeAtPoint",
      TastyPointEvidenceStatus.CompileFailed -> "CompileFailed",
      TastyPointEvidenceStatus.ArtifactUnavailable -> "ArtifactUnavailable",
      TastyPointEvidenceStatus.SourceChangedDuringRequest -> "SourceChangedDuringRequest",
      TastyPointEvidenceStatus.UnsupportedTargetScala -> "UnsupportedTargetScala",
      TastyPointEvidenceStatus.InspectorUnavailable -> "InspectorUnavailable",
      TastyPointEvidenceStatus.InspectorFailed -> "InspectorFailed"
    )
    statuses.foreach { case (status, value) =>
      assertEquals(status.asJson.noSpaces, s"\"$value\"")
    }
    assertEquals(TastyPointEvidenceReport.SchemaVersion, "semantic-scala.tasty-point-evidence.v1")
    assertEquals(TastyCompileStatus.Succeeded.asJson.noSpaces, "\"Succeeded\"")
    assertEquals(TastyArtifactState.RejectedByBounds.asJson.noSpaces, "\"RejectedByBounds\"")
    assertEquals(TastyFreshnessDisposition.SameRequestSourceStable.asJson.noSpaces, "\"SameRequestSourceStable\"")

  test("resolved report round-trips with the exact stable field shape"):
    val report = TastyPointEvidenceReport(
      status = TastyPointEvidenceStatus.Resolved,
      request = TastyPointRequest(
        "src/main/scala/example/Example.scala",
        TastyPointPosition(10, 36),
        "pluginTests"
      ),
      compile = TastyCompileEvidence(TastyCompileStatus.Succeeded),
      targetScalaVersion = Some("3.8.4"),
      artifactEvidence = TastyArtifactEvidence(
        TastyArtifactState.Available,
        candidateCount = 3,
        inspectedCount = 2,
        selectedArtifact = Some(TastyArtifactDigest("TASTy", 1234L, "a" * 64))
      ),
      selectedTree = Some(
        TastySelectedTree(
          "Select",
          TastySourceRange(10, 34, 10, 51),
          Some("example.User.generatedHello"),
          Some("generatedHello"),
          Some("def generatedHello: String"),
          Some("String")
        )
      ),
      freshness = TastyFreshnessEvidence(
        TastyFreshnessDisposition.SameRequestSourceStable,
        "b" * 64,
        "b" * 64
      ),
      inspector = Some(
        TastyInspectorProvenance(
          protocolVersion = "semantic-scala.internal-tasty-worker-output.v1",
          implementation = "ExactScalaTastyInspectorChild",
          scalaVersion = "3.8.4",
          workerSourceSha256 = "c" * 64,
          toolchainSha256 = "d" * 64,
          targetCompilerOptionsReplayed = false,
          targetPluginsReplayed = false
        )
      ),
      warnings = List("Post-compile evidence is bounded to one selected compile request.")
    )
    val json = report.asJson.noSpaces
    assertEquals(decode[TastyPointEvidenceReport](json), Right(report))
    val expectedFields = List(
      "schemaVersion",
      "status",
      "request",
      "compile",
      "targetScalaVersion",
      "artifactEvidence",
      "selectedTree",
      "freshness",
      "inspector",
      "warnings"
    )
    val fields = io.circe.parser.parse(json).toOption.flatMap(_.asObject).map(_.keys.toList)
    assertEquals(fields, Some(expectedFields))
    assert(!json.contains("/home/"))
