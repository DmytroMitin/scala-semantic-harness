package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class SemanticdbArtifactHintsSuite extends munit.FunSuite:
  test("fixture layout produces qualified fixture and test-resource hints without checked-in origin"):
    val result = classify(
      "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    )

    assertEquals(values(result.roles), List(SemanticdbArtifactRole.Fixture))
    assertEquals(values(result.sourceSets), List(SemanticdbSourceSetHint.TestResource))
    assertEquals(values(result.outputKinds), List(SemanticdbOutputKindHint.SourceResource))
    assertEquals(values(result.modules), List("modules/semanticdb-reader"))
    assertEquals(result.origins, Nil)
    assert(result.unresolved.contains("origin"))
    assert(!values(result.origins).contains(SemanticdbArtifactOrigin.CheckedIn))

  test("target test-resource copy exposes copy-level generated and corroborated copied hints"):
    val fixture = "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    val target = "modules/semanticdb-reader/target/scala-3.3.3/test-classes/semanticdb-fixtures/simple/Main.scala.semanticdb"
    val result = classify(
      target,
      duplicateGroupId = Some("sha256:fixture"),
      duplicateArtifactPaths = List(target, fixture)
    )

    assertEquals(
      values(result.origins),
      List(SemanticdbArtifactOrigin.Copied, SemanticdbArtifactOrigin.Generated)
    )
    assertEquals(
      result.origins.find(_.value == SemanticdbArtifactOrigin.Copied).map(_.confidence),
      Some(SemanticdbHintConfidence.Corroborated)
    )
    assertEquals(values(result.roles), List(SemanticdbArtifactRole.Resource))
    assertEquals(values(result.sourceSets), List(SemanticdbSourceSetHint.TestResource))
    assertEquals(
      values(result.outputKinds),
      List(SemanticdbOutputKindHint.ClassOutput, SemanticdbOutputKindHint.ResourceOutput)
    )
    assertEquals(values(result.producers), List(SemanticdbProducerHint.SbtLike))
    assertEquals(values(result.configurations), List(SemanticdbConfigurationHint.Test))
    assertEquals(values(result.scalaVersions), List("3.3.3"))
    assertEquals(values(result.scalaBinaryVersions), List("3"))
    assertEquals(
      values(result.targetDirectories),
      List("modules/semanticdb-reader/target/scala-3.3.3/test-classes")
    )

  test("standard main class output requires full source evidence for Main"):
    val withoutSource = classify(
      "module-a/target/scala-3.3.3/classes/META-INF/semanticdb/example/Main.scala.semanticdb"
    )
    val withSource = classify(
      "module-a/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb",
      documentUris = List("src/main/scala/example/Main.scala")
    )

    assertEquals(withoutSource.sourceSets, Nil)
    assertEquals(values(withoutSource.origins), List(SemanticdbArtifactOrigin.Generated))
    assertEquals(values(withoutSource.roles), List(SemanticdbArtifactRole.OrdinarySourceOutput))
    assertEquals(values(withoutSource.configurations), List(SemanticdbConfigurationHint.Compile))
    assertEquals(values(withSource.sourceSets), List(SemanticdbSourceSetHint.Main))
    assertEquals(values(withSource.languages), List(SemanticdbLanguageHint.Scala))
    assertEquals(values(withSource.modules), List("module-a"))

  test("standard test class output uses full Java evidence for Test"):
    val result = classify(
      "module-a/target/scala-2.13.16/test-classes/META-INF/semanticdb/src/test/java/example/MainTest.java.semanticdb",
      documentUris = List("src/test/java/example/MainTest.java")
    )

    assertEquals(values(result.sourceSets), List(SemanticdbSourceSetHint.Test))
    assertEquals(values(result.languages), List(SemanticdbLanguageHint.Java))
    assertEquals(values(result.configurations), List(SemanticdbConfigurationHint.Test))
    assertEquals(values(result.scalaVersions), List("2.13.16"))
    assertEquals(values(result.scalaBinaryVersions), List("2.13"))

  test("Bloop Metals layout preserves independent producer and output hints"):
    val result = classify(
      ".bloop/root/bloop-internal-classes/classes-Metals-abc/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb",
      documentUris = List("src/main/scala/Main.scala")
    )

    assertEquals(values(result.origins), List(SemanticdbArtifactOrigin.Generated))
    assertEquals(values(result.roles), List(SemanticdbArtifactRole.OrdinarySourceOutput))
    assertEquals(
      values(result.outputKinds),
      List(SemanticdbOutputKindHint.ClassOutput, SemanticdbOutputKindHint.ToolingInternalOutput)
    )
    assertEquals(
      values(result.producers),
      List(SemanticdbProducerHint.Bloop, SemanticdbProducerHint.Metals)
    )
    assertEquals(values(result.modules), List("root"))
    assertEquals(values(result.configurations), List(SemanticdbConfigurationHint.Compile))
    assertEquals(values(result.sourceSets), List(SemanticdbSourceSetHint.Main))
    assertEquals(values(result.languages), List(SemanticdbLanguageHint.Scala))

  test("unknown workspace path retains facts and explicit unresolved dimensions"):
    val result = classify("custom/artifacts/Unknown.semanticdb")

    assertEquals(result.workspaceScope, SemanticdbWorkspaceScope.InsideWorkspace)
    assertEquals(result.origins, Nil)
    assertEquals(result.roles, Nil)
    assertEquals(result.sourceSets, Nil)
    assertEquals(result.outputKinds, Nil)
    assertEquals(result.producers, Nil)
    assertEquals(result.modules, Nil)
    assertEquals(result.configurations, Nil)
    assertEquals(
      result.evidence.map(_.kind),
      List(ArtifactHintEvidenceKind.ArtifactPath, ArtifactHintEvidenceKind.WorkspaceBoundary)
    )
    assertEquals(result.unresolved, result.unresolved.sorted)
    assert(result.unresolved.contains("origin"))
    assert(result.unresolved.contains("generatedSource"))

  test("Windows separators and repeated separators normalize to identical classification"):
    val unix = classify(
      "module/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb",
      documentUris = List("./src/main/scala/Main.scala")
    )
    val windows = classify(
      "module\\target\\scala-3.3.3\\classes\\META-INF\\semanticdb\\src\\main\\scala\\Main.scala.semanticdb",
      documentUris = List(".\\src\\main\\scala\\Main.scala")
    )
    val repeated = classify(
      "./module//target///scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb",
      documentUris = List("src//main///scala/Main.scala")
    )

    assertEquals(windows, unix)
    assertEquals(repeated, unix)

  test("misleading filenames and arbitrary segments do not trigger layout rules"):
    val result = classify("custom/my-target-test-classes-Metals-bloop.semanticdb")

    assertEquals(result.origins, Nil)
    assertEquals(result.roles, Nil)
    assertEquals(result.outputKinds, Nil)
    assertEquals(result.producers, Nil)
    assertEquals(result.modules, Nil)
    assertEquals(result.configurations, Nil)
    assertEquals(result.scalaVersions, Nil)

  test("basename-only URI leaves source set unresolved and language weak"):
    val result = classify("custom/Main.scala.semanticdb", documentUris = List("Main.scala"))

    assertEquals(result.sourceSets, Nil)
    assertEquals(values(result.languages), List(SemanticdbLanguageHint.Scala))
    assertEquals(result.languages.head.confidence, SemanticdbHintConfidence.WeakHeuristic)
    assert(result.unresolved.contains("sourceSet"))

  test("matched source evidence corroborates source set and language"):
    val source = "module-a/src/main/scala/example/Main.scala"
    val result = classify(
      "module-a/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb",
      documentUris = List("src/main/scala/example/Main.scala"),
      matchedSourcePaths = List(source)
    )

    assertEquals(result.sourceSets.size, 1)
    assertEquals(result.sourceSets.head.value, SemanticdbSourceSetHint.Main)
    assertEquals(result.sourceSets.head.confidence, SemanticdbHintConfidence.Corroborated)
    assertEquals(result.languages.head.confidence, SemanticdbHintConfidence.Corroborated)
    assertEquals(values(result.modules), List("module-a"))

  test("Scala and Java evidence aggregates deterministically to Mixed"):
    val result = classify(
      "custom/Multi.semanticdb",
      documentUris = List(
        "src/test/java/example/J.java",
        "src/main/scala/example/S.scala"
      )
    )

    assertEquals(values(result.languages), List(SemanticdbLanguageHint.Mixed))
    assert(result.languages.head.evidenceIds.size >= 4)
    assertEquals(
      values(result.sourceSets),
      List(SemanticdbSourceSetHint.Main, SemanticdbSourceSetHint.Test)
    )

  test("resource roots classify without fabricating a language"):
    val result = classify(
      "custom/Resources.semanticdb",
      documentUris = List("src/main/resources/example.conf", "src/test/resources/test.conf")
    )

    assertEquals(
      values(result.sourceSets),
      List(SemanticdbSourceSetHint.MainResource, SemanticdbSourceSetHint.TestResource)
    )
    assertEquals(result.languages, Nil)

  test("URI schemes are retained as evidence but not interpreted as source roots"):
    val result = classify(
      "custom/Scheme.semanticdb",
      documentUris = List("file:///workspace/src/main/scala/Main.scala")
    )

    assertEquals(result.sourceSets, Nil)
    assertEquals(result.languages, Nil)
    assert(result.evidence.exists(item => item.kind == ArtifactHintEvidenceKind.DocumentUri && item.value.startsWith("file:///")))

  test("duplicate fixture and target copies retain different copy-level hints"):
    val group = "sha256:same"
    val fixture = "module/src/test/resources/semanticdb-fixtures/Main.scala.semanticdb"
    val target = "module/target/scala-3.3.3/test-classes/semanticdb-fixtures/Main.scala.semanticdb"
    val paths = List(target, fixture)
    val fixtureHints = classify(fixture, duplicateGroupId = Some(group), duplicateArtifactPaths = paths)
    val targetHints = classify(target, duplicateGroupId = Some(group), duplicateArtifactPaths = paths)

    assertEquals(values(fixtureHints.roles), List(SemanticdbArtifactRole.Fixture))
    assertEquals(fixtureHints.origins, Nil)
    assertEquals(
      values(targetHints.origins),
      List(SemanticdbArtifactOrigin.Copied, SemanticdbArtifactOrigin.Generated)
    )
    assert(fixtureHints.evidence.exists(item => item.kind == ArtifactHintEvidenceKind.ExactDuplicateGroup && item.value == group))
    assert(targetHints.evidence.exists(item => item.kind == ArtifactHintEvidenceKind.ExactDuplicateGroup && item.value == group))

  test("same content under main and test outputs preserves conflicting copy-level configurations"):
    val group = "sha256:same-content"
    val main = "module/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb"
    val testPath = "module/target/scala-3.3.3/test-classes/META-INF/semanticdb/src/test/scala/MainSuite.scala.semanticdb"
    val paths = List(main, testPath)
    val mainHints = classify(
      main,
      documentUris = List("src/main/scala/Main.scala"),
      duplicateGroupId = Some(group),
      duplicateArtifactPaths = paths
    )
    val testHints = classify(
      testPath,
      documentUris = List("src/test/scala/MainSuite.scala"),
      duplicateGroupId = Some(group),
      duplicateArtifactPaths = paths
    )

    assertEquals(values(mainHints.configurations), List(SemanticdbConfigurationHint.Compile))
    assertEquals(values(mainHints.sourceSets), List(SemanticdbSourceSetHint.Main))
    assertEquals(values(testHints.configurations), List(SemanticdbConfigurationHint.Test))
    assertEquals(values(testHints.sourceSets), List(SemanticdbSourceSetHint.Test))

  test("evidence IDs, evidence order, hint order, and citations are deterministic"):
    val input = SemanticdbArtifactHintInput(
      artifactPath = "module/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb",
      workspaceScope = SemanticdbWorkspaceScope.InsideWorkspace,
      documentUris = List("src/main/scala/Main.scala", "src/main/scala/Main.scala"),
      matchedSourcePaths = List("module/src/main/scala/Main.scala"),
      duplicateGroupId = Some("sha256:stable"),
      duplicateArtifactPaths = List("z/path.semanticdb", "a/path.semanticdb")
    )
    val first = SemanticdbArtifactHintClassifier.classify(input)
    val second = SemanticdbArtifactHintClassifier.classify(input.copy(documentUris = input.documentUris.reverse))
    val evidenceIds = first.evidence.map(_.id)

    assertEquals(second, first)
    assert(evidenceIds.forall(_.matches("evidence:sha256:[0-9a-f]{64}")))
    assertEquals(first.evidence, first.evidence.sortBy(item => (item.kind.toString, item.source, item.value, item.id)))
    assertEquals(evidenceIds.distinct.size, evidenceIds.size)
    assert(allCitedEvidenceIds(first).forall(evidenceIds.contains))
    assert(allHintEvidenceIdsAreSorted(first))

  test("duplicate supporting rules merge one value and retain strongest confidence"):
    val result = classify(
      "module/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb",
      documentUris = List("src/main/scala/Main.scala", "src/main/scala/Main.scala"),
      matchedSourcePaths = List("module/src/main/scala/Main.scala")
    )

    assertEquals(result.sourceSets.size, 1)
    assertEquals(result.sourceSets.head.value, SemanticdbSourceSetHint.Main)
    assertEquals(result.sourceSets.head.confidence, SemanticdbHintConfidence.Corroborated)
    assertEquals(result.sourceSets.head.evidenceIds.distinct, result.sourceSets.head.evidenceIds)
    assertEquals(result.sourceSets.head.evidenceIds, result.sourceSets.head.evidenceIds.sorted)

  test("different supported values remain visible instead of selecting one"):
    val result = classify(
      "custom/Conflicting.semanticdb",
      documentUris = List(
        "module-a/src/main/scala/Main.scala",
        "module-b/src/test/scala/Main.scala"
      )
    )

    assertEquals(
      values(result.sourceSets),
      List(SemanticdbSourceSetHint.Main, SemanticdbSourceSetHint.Test)
    )
    assertEquals(values(result.modules), List("module-a", "module-b"))

  test("model contains neither canonical target identity nor selection fields"):
    val names = classify("custom/Unknown.semanticdb").productElementNames.toList

    assert(!names.contains("buildTargetId"))
    assert(!names.exists(_.toLowerCase.contains("selected")))
    assert(!names.exists(_.toLowerCase.contains("preferred")))

  test("generated artifact does not imply generated source"):
    val result = classify(
      "module/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb",
      documentUris = List("src/main/scala/Main.scala")
    )

    assertEquals(values(result.origins), List(SemanticdbArtifactOrigin.Generated))
    assertEquals(result.generatedSource, Nil)
    assert(result.unresolved.contains("generatedSource"))

  test("loose it substrings do not imply IntegrationTest"):
    val result = classify(
      "module-it/target/scala-3.3.3/test-classes/META-INF/semanticdb/src/test/scala/It.scala.semanticdb",
      documentUris = List("src/test/scala/It.scala")
    )

    assertEquals(values(result.configurations), List(SemanticdbConfigurationHint.Test))
    assert(!values(result.configurations).contains(SemanticdbConfigurationHint.IntegrationTest))

  test("inventory invokes classifier per copy without exposing hints in status v2"):
    val workspace = Files.createTempDirectory("semanticdb-hints-inventory")
    val uri = "src/main/scala/example/Main.scala"
    val semanticdb = workspace.resolve(
      "module/target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb"
    )
    writeSemanticdb(semanticdb, uri)

    val inventory = SemanticdbArtifactInventory.inspect(workspace).fold(message => fail(message), identity)
    val candidate = inventory.candidates.head
    val status = SemanticdbStatusV2.inspect(workspace).fold(message => fail(message), identity)

    assertEquals(values(candidate.hints.origins), List(SemanticdbArtifactOrigin.Generated))
    assertEquals(values(candidate.hints.sourceSets), List(SemanticdbSourceSetHint.Main))
    assertEquals(status.schemaVersion, SemanticdbStatusReportV2.SchemaVersion)
    assert(!status.productElementNames.exists(_.toLowerCase.contains("hint")))
    assert(!status.candidates.head.productElementNames.exists(_.toLowerCase.contains("hint")))
    assertEquals(status.candidates.map(_.semanticdb), List(candidate.semanticdb))

  private def classify(
    artifactPath: String,
    documentUris: List[String] = Nil,
    matchedSourcePaths: List[String] = Nil,
    duplicateGroupId: Option[String] = None,
    duplicateArtifactPaths: List[String] = Nil
  ): SemanticdbArtifactHints =
    SemanticdbArtifactHintClassifier.classify(
      SemanticdbArtifactHintInput(
        artifactPath = artifactPath,
        workspaceScope = SemanticdbWorkspaceScope.InsideWorkspace,
        documentUris = documentUris,
        matchedSourcePaths = matchedSourcePaths,
        duplicateGroupId = duplicateGroupId,
        duplicateArtifactPaths = duplicateArtifactPaths
      )
    )

  private def values[A](hints: List[QualifiedArtifactHint[A]]): List[A] =
    hints.map(_.value)

  private def allCitedEvidenceIds(hints: SemanticdbArtifactHints): List[String] =
    hints.origins.flatMap(_.evidenceIds) ++
      hints.roles.flatMap(_.evidenceIds) ++
      hints.sourceSets.flatMap(_.evidenceIds) ++
      hints.languages.flatMap(_.evidenceIds) ++
      hints.outputKinds.flatMap(_.evidenceIds) ++
      hints.producers.flatMap(_.evidenceIds) ++
      hints.modules.flatMap(_.evidenceIds) ++
      hints.configurations.flatMap(_.evidenceIds) ++
      hints.scalaVersions.flatMap(_.evidenceIds) ++
      hints.scalaBinaryVersions.flatMap(_.evidenceIds) ++
      hints.targetDirectories.flatMap(_.evidenceIds) ++
      hints.generatedSource.flatMap(_.evidenceIds)

  private def allHintEvidenceIdsAreSorted(hints: SemanticdbArtifactHints): Boolean =
    val all = List(
      hints.origins.map(_.evidenceIds),
      hints.roles.map(_.evidenceIds),
      hints.sourceSets.map(_.evidenceIds),
      hints.languages.map(_.evidenceIds),
      hints.outputKinds.map(_.evidenceIds),
      hints.producers.map(_.evidenceIds),
      hints.modules.map(_.evidenceIds),
      hints.configurations.map(_.evidenceIds),
      hints.scalaVersions.map(_.evidenceIds),
      hints.scalaBinaryVersions.map(_.evidenceIds),
      hints.targetDirectories.map(_.evidenceIds),
      hints.generatedSource.map(_.evidenceIds)
    ).flatten
    all.forall(ids => ids == ids.distinct.sorted)

  private def writeSemanticdb(path: Path, uri: String): Path =
    Files.createDirectories(path.getParent)
    Files.write(path, TextDocuments(documents = Seq(TextDocument(uri = uri))).toByteArray)
    path
