package semantic.harness.cli

import io.circe.parser.decode
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.nio.file.Files
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReport
import semantic.harness.semanticdb_reader.SemanticdbCoverage
import semantic.harness.semanticdb_reader.SemanticdbCoverageReport
import semantic.harness.semanticdb_reader.SemanticdbStatus
import semantic.harness.semanticdb_reader.SemanticdbStatusReport
import semantic.harness.semanticdb_reader.SemanticdbStatusReportV2
import semantic.harness.semanticdb_reader.SemanticdbStatusV2
import semantic.harness.core.CompileReport
import semantic.harness.core.TestReport
import semantic.harness.fp.EffectSummaryReport
import semantic.harness.presentation.InferTypeContextKind
import semantic.harness.presentation.InferTypeAcquisitionOrigin
import semantic.harness.presentation.InferTypeFreshnessAssessment
import semantic.harness.presentation.InferTypeRenderingKind
import semantic.harness.presentation.InferTypeReport
import semantic.harness.presentation.InferTypeStatus
import semantic.harness.presentation.InferTypeBatchItemStatus
import semantic.harness.presentation.InferTypeBatchBounds
import semantic.harness.presentation.InferTypeBatchReport
import semantic.harness.presentation.InferTypeBatchRequest
import semantic.harness.presentation.InferTypeBatchRequestItem
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.reconciliation.ReconciliationResult
import semantic.harness.reconciliation.ReconciliationStatus
import semantic.harness.reconciliation.PointArtifactSelectionStatus
import semantic.harness.reconciliation.PointEvidenceReport
import semantic.harness.semanticdb_reader.SemanticFileSummary
import semantic.harness.sbt_runner.SbtRunResult
import semantic.harness.sbt_runner.SbtRunner
import semantic.harness.sbt_runner.SbtClasspathAcquirer
import semantic.harness.sbt_runner.SbtClasspathCacheFailure
import semantic.harness.sbt_runner.SbtClasspathCacheMode
import semantic.harness.sbt_runner.SbtClasspathCacheResolution
import semantic.harness.sbt_runner.SbtClasspathCacheResolutionOrigin
import semantic.harness.sbt_runner.SbtClasspathCacheService
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathEntry
import semantic.harness.sbt_runner.SbtClasspathEntryKind
import semantic.harness.sbt_runner.SbtClasspathFailure
import semantic.harness.sbt_runner.SbtClasspathRequest
import semantic.harness.sbt_runner.SbtClasspathResult
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.ValidatedSbtJavaHome
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class CliJsonSuite extends munit.FunSuite:
  test("compile --json returns successful CompileReport JSON only"):
    val result = CliApp.run(
      List("compile", "--json"),
      FakeSbtRunner(compileResult = SbtRunResult("compile", 0, "[success] Total time: 1 s", "")),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[CompileReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(CompileReport.SchemaVersion))
    assertEquals(decoded, Right(CompileReport(success = true, diagnostics = Nil)))

  test("compile --json returns failed CompileReport without CLI failure"):
    val result = CliApp.run(
      List("compile", "--json"),
      FakeSbtRunner(compileResult = SbtRunResult("compile", 1, "[error] missing symbol", "")),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[CompileReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(CompileReport.SchemaVersion))
    assertEquals(decoded.map(_.success), Right(false))

  test("test --json returns TestReport JSON only"):
    val result = CliApp.run(
      List("test", "--json"),
      FakeSbtRunner(testResult = SbtRunResult("test", 0, "[info] Passed: Total 2, Failed 0, Errors 0, Passed 2", "")),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[TestReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(TestReport.SchemaVersion))
    assert(decoded.isRight)

  test("errors --json returns CompileReport JSON only"):
    val result = CliApp.run(
      List("errors", "--json"),
      FakeSbtRunner(compileResult = SbtRunResult("compile", 1, "[error] compile failed", "")),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[CompileReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(CompileReport.ErrorsSchemaVersion))
    assert(decoded.isRight)

  test("build-oracle commands forward the optional validated sbt project"):
    val runner = FakeSbtRunner()

    val rootCompile = CliApp.run(List("compile", "--json"), runner, Path.of("."))
    val selectedCompile = CliApp.run(
      List("compile", "--sbt-project", "core2_13", "--json"),
      runner,
      Path.of(".")
    )
    val selectedErrors = CliApp.run(
      List("errors", "--sbt-project", "core2_13", "--json"),
      runner,
      Path.of(".")
    )
    val selectedTest = CliApp.run(
      List("test", "--sbt-project", "tests_2", "--json"),
      runner,
      Path.of(".")
    )

    assertEquals(List(rootCompile, selectedCompile, selectedErrors, selectedTest).map(_.exitCode), List(0, 0, 0, 0))
    assertEquals(
      runner.compileProjects,
      List(None, Some(project("core2_13")), Some(project("core2_13")))
    )
    assertEquals(runner.testProjects, List(Some(project("tests_2"))))

  test("invalid build-oracle sbt project is rejected before the runner"):
    val runner = FakeSbtRunner()

    val result = CliApp.run(
      List("compile", "--sbt-project", "core2_13;test", "--json"),
      runner,
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("sbt project ID must start with a letter")))
    assertEquals(runner.compileProjects, Nil)
    assertEquals(runner.testProjects, Nil)

  test("build-oracle selected Java is validated once and forwarded without public path leakage"):
    val home = fakeJavaHome("task166-cli-java-home")
    val alias = home.resolveSibling(s"${home.getFileName}-current")
    Files.createSymbolicLink(alias, home)
    val runner = FakeSbtRunner()
    try
      val result = CliApp.run(
        List(
          "compile",
          "--sbt-project",
          "core2_13",
          "--sbt-java-home",
          alias.toString,
          "--json"
        ),
        runner,
        Path.of(".")
      )

      assertEquals(result.exitCode, 0)
      assertEquals(runner.compileProjects, List(Some(project("core2_13"))))
      assertEquals(
        runner.compileJavaHomes.map(_.map(_.canonicalHome)),
        List(Some(home.toRealPath()))
      )
      assert(!result.stdout.exists(_.contains(alias.toString)))
      assert(!result.stdout.exists(_.contains(home.toString)))
    finally
      Files.deleteIfExists(alias)
      deleteFakeJavaHome(home)

  test("invalid build-oracle Java home fails before runner invocation without echoing its path"):
    val runner = FakeSbtRunner()
    val missing = Path.of("/tmp/task166-definitely-missing-java-home")
    val result = CliApp.run(
      List("compile", "--sbt-java-home", missing.toString, "--json"),
      runner,
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assertEquals(runner.compileProjects, Nil)
    assert(!result.stderr.exists(_.contains(missing.toString)))

  test("point-evidence --json returns the versioned composed report for a contained source"):
    val workspace = Files.createTempDirectory("cli-point-evidence")
    val source = workspace.resolve("src/main/scala/example/Main.scala")
    Files.createDirectories(source.getParent)
    Files.writeString(source, "package example\nobject Main:\n  val answer = 42\n")

    val result = CliApp.run(
      List(
        "point-evidence",
        "--workspace",
        workspace.toString,
        "--file",
        source.toString,
        "--line",
        "3",
        "--col",
        "7",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.exitCode, 0)
    assertEquals(result.stderr, None)
    val decoded = decode[PointEvidenceReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(PointEvidenceReport.SchemaVersion))
    assertEquals(decoded.map(_.position.encoding), Right("UTF-16"))
    assertEquals(
      decoded.map(_.selection.status),
      Right(PointArtifactSelectionStatus.NotSelectedUnavailable)
    )
    assert(decoded.exists(_.discovery.matches.isEmpty))

  test("point-evidence rejects a source outside the explicit workspace"):
    val workspace = Files.createTempDirectory("cli-point-evidence-workspace")
    val outside = Files.createTempFile("cli-point-evidence-outside", ".scala")
    Files.writeString(outside, "object Outside\n")

    val result = CliApp.run(
      List(
        "point-evidence",
        "--workspace",
        workspace.toString,
        "--file",
        outside.toString,
        "--line",
        "1",
        "--col",
        "1",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assertEquals(result.stdout, None)
    assert(result.stderr.exists(_.contains("Source file escapes workspace")))

  test("point-evidence rejects a contained non-Scala file at the CLI boundary"):
    val workspace = Files.createTempDirectory("cli-point-evidence-extension")
    val source = workspace.resolve("Main.txt")
    Files.writeString(source, "object Main\n")

    val result = CliApp.run(
      List(
        "point-evidence",
        "--workspace",
        workspace.toString,
        "--file",
        source.toString,
        "--line",
        "1",
        "--col",
        "1",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assertEquals(result.stdout, None)
    assert(result.stderr.exists(_.contains("expected .scala source file")))

  test("point-evidence ships a parseable schema with the exact public identifier"):
    val resource = Option(getClass.getClassLoader.getResourceAsStream(
      "semantic-scala/schemas/point-evidence-result.v1.schema.json"
    )).getOrElse(fail("Missing point-evidence result schema resource"))
    val text = try new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally resource.close()
    val schema = parse(text).fold(error => fail(error.message), identity)

    assertEquals(
      schema.hcursor.downField("$id").as[String].toOption,
      Some("urn:semantic-scala:schema:point-evidence-result:v1")
    )
    assertEquals(
      schema.hcursor.downField("properties").downField("schemaVersion").downField("const").as[String].toOption,
      Some(PointEvidenceReport.SchemaVersion)
    )

  test("symbols --semanticdb --json returns SemanticFileSummary JSON only"):
    val result = CliApp.run(
      List("symbols", "--semanticdb", "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb", "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[SemanticFileSummary](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(SemanticFileSummary.SchemaVersion))
    assertEquals(decoded.map(_.uri), Right("Main.scala"))
    assert(decoded.exists(_.symbols.nonEmpty))

  test("semanticdb-status --json returns SemanticdbStatusReport JSON only"):
    val result = CliApp.run(
      List("semanticdb-status", "--workspace", "modules/semanticdb-reader/src/test/resources", "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[SemanticdbStatusReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(SemanticdbStatusReport.SchemaVersion))
    assertEquals(decoded.map(_.status), Right(SemanticdbStatus.StatusAvailable))
    assert(decoded.exists(_.semanticdbFiles > 0))
    assert(decoded.exists(_.parseableFiles > 0))
    assert(decoded.exists(_.candidates.exists(_.semanticdb.endsWith("Main.scala.semanticdb"))))
    assert(parse(result.stdout.getOrElse("")).exists(_.hcursor.downField("artifactStatus").focus.isEmpty))

  test("semanticdb-status explicit v1 matches the default JSON behavior"):
    val defaultResult = CliApp.run(
      List("semanticdb-status", "--workspace", "modules/semanticdb-reader/src/test/resources", "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )
    val explicitResult = CliApp.run(
      List("semanticdb-status", "--schema-version", "v1", "--json", "--workspace", "modules/semanticdb-reader/src/test/resources"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(explicitResult.exitCode, 0)
    assertEquals(explicitResult.stderr, None)
    assertEquals(explicitResult.stdout, defaultResult.stdout)

  test("semanticdb-status v2 returns all-document artifact and duplicate metadata"):
    val result = CliApp.run(
      List("semanticdb-status", "--json", "--workspace", "modules/semanticdb-reader/src/test/resources", "--schema-version", "v2"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.exitCode, 0)
    assertEquals(result.stderr, None)
    val decoded = decode[SemanticdbStatusReportV2](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(SemanticdbStatusReportV2.SchemaVersion))
    assertEquals(decoded.map(_.artifactStatus), Right(SemanticdbStatus.StatusAvailable))
    assertEquals(decoded.map(_.coverageStatus), Right(SemanticdbStatusV2.CoverageNotAssessed))
    assertEquals(decoded.map(_.semanticdbFiles), Right(1))
    assertEquals(decoded.map(_.uniqueContentFiles), Right(1))
    assertEquals(decoded.map(_.duplicateFiles), Right(0))
    assertEquals(decoded.map(_.duplicateGroupCount), Right(0))
    assert(decoded.exists(_.candidates.forall(_.documentCount == 1)))
    assert(decoded.exists(_.candidates.forall(_.documentsParsed == 1)))
    assert(decoded.exists(_.candidates.forall(_.documentsIgnored == 0)))
    assert(decoded.exists(_.candidates.forall(_.sizeBytes.contains(73L))))
    assert(decoded.exists(_.candidates.forall(_.contentHash.exists(_.matches("sha256:[0-9a-f]{64}")))))

  test("semanticdb-status rejects an unsupported schema version"):
    val result = CliApp.run(
      List("semanticdb-status", "--workspace", ".", "--schema-version", "v3", "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assertEquals(result.stdout, None)
    assert(result.stderr.exists(_.contains("Unsupported schema version for semanticdb-status: v3")))

  test("semanticdb-status returns non-zero and stderr for invalid workspace"):
    val result = CliApp.run(
      List("semanticdb-status", "--workspace", "modules/semanticdb-reader/src/test/resources/missing-workspace", "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("Workspace does not exist")))

  test("semanticdb-coverage --json returns schema-versioned factual inventory coverage"):
    val workspace = Files.createTempDirectory("cli-semanticdb-coverage")
    val relativeSource = "src/main/scala/example/Main.scala"
    val source = workspace.resolve(relativeSource)
    val semanticdb = workspace.resolve("target/classes/Main.scala.semanticdb")
    Files.createDirectories(source.getParent)
    Files.createDirectories(semanticdb.getParent)
    Files.writeString(source, "package example\nobject Main\n")
    Files.write(
      semanticdb,
      TextDocuments(documents = Seq(TextDocument(uri = relativeSource))).toByteArray
    )

    val result = CliApp.run(
      List("semanticdb-coverage", "--json", "--workspace", workspace.toString),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[SemanticdbCoverageReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(SemanticdbCoverageReport.SchemaVersion))
    assertEquals(decoded.map(_.coverageStatus), Right(SemanticdbCoverage.StatusCompleteWithinInventory))
    assertEquals(decoded.map(_.inventoryBasis.kind), Right(SemanticdbCoverage.InventoryKind))
    assertEquals(decoded.map(_.inventoryBasis.extensions), Right(List(".scala", ".java")))
    assertEquals(decoded.map(_.sourceFiles), Right(1))
    assertEquals(decoded.map(_.coveredSourceFiles), Right(1))
    assertEquals(decoded.map(_.uniqueDocumentEvidence), Right(1))
    assertEquals(decoded.map(_.sources.head.matchKind), Right(Some(SemanticdbCoverage.MatchUriExact)))
    assert(parse(result.stdout.getOrElse("")).isRight)

  test("semanticdb-coverage human output states the inventory scope limitation"):
    val workspace = Files.createTempDirectory("cli-semanticdb-coverage-human")
    Files.writeString(workspace.resolve("Main.scala"), "object Main\n")

    val result = CliApp.run(
      List("semanticdb-coverage", "--workspace", workspace.toString),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.exitCode, 0)
    assertEquals(result.stderr, None)
    assert(result.stdout.exists(_.contains("freshness and build-target completeness are not assessed")))

  test("semanticdb-coverage returns non-zero and stderr for invalid workspace"):
    val workspace = Files.createTempDirectory("cli-semanticdb-coverage-invalid")
    val result = CliApp.run(
      List("semanticdb-coverage", "--workspace", workspace.resolve("missing").toString, "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("Workspace does not exist")))

  test("semanticdb-for-source --json returns a schema-versioned unique match"):
    val workspace = Files.createTempDirectory("cli-semanticdb-for-source")
    val relativeSource = "src/main/scala/example/Main.scala"
    val source = workspace.resolve(relativeSource)
    val semanticdb = workspace.resolve(s"target/classes/META-INF/semanticdb/$relativeSource.semanticdb")
    Files.createDirectories(source.getParent)
    Files.createDirectories(semanticdb.getParent)
    Files.writeString(source, "package example\nobject Main\n")
    Files.copy(
      Path.of("modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"),
      semanticdb
    )

    val result = CliApp.run(
      List("semanticdb-for-source", "--file", source.toString, "--workspace", workspace.toString, "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[SemanticdbForSourceReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(SemanticdbForSourceReport.SchemaVersion))
    assertEquals(decoded.map(_.status), Right(SemanticdbForSource.StatusUniqueMatch))
    assertEquals(decoded.map(_.matches.map(_.matchKind)), Right(List(SemanticdbForSource.MatchMetaInfSuffix)))

  test("semanticdb-for-source returns non-zero and stderr for invalid source file"):
    val workspace = Files.createTempDirectory("cli-semanticdb-for-source-invalid")
    val result = CliApp.run(
      List("semanticdb-for-source", "--file", workspace.resolve("Missing.scala").toString, "--workspace", workspace.toString, "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("Source file does not exist")))

  test("symbols returns non-zero and stderr for invalid SemanticDB file"):
    val result = CliApp.run(
      List("symbols", "--semanticdb", "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/missing.semanticdb", "--json"),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("does not exist")))

  test("symbol-at --json returns SymbolAtResult JSON only"):
    val result = CliApp.run(
      List(
        "symbol-at",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala",
        "--line",
        "6",
        "--col",
        "16",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[SymbolAtResult](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(SymbolAtResult.SchemaVersion))
    assert(decoded.exists(_.source.endsWith("Main.scala")))
    assert(decoded.exists(_.symbol.exists(_.nonEmpty)))

  test("symbol-at returns non-zero and stderr for invalid source file"):
    val result = CliApp.run(
      List(
        "symbol-at",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Missing.scala",
        "--line",
        "1",
        "--col",
        "1",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("does not exist")))

  test("infer-type --json returns a public resolved report without private context paths"):
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "8",
        "--col",
        "7",
        "--workspace",
        ".",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val stdout = result.stdout.getOrElse("")
    val decoded = decode[InferTypeReport](stdout)
    assertEquals(decoded.map(_.schemaVersion), Right(InferTypeReport.SchemaVersion))
    assertEquals(decoded.map(_.status), Right(InferTypeStatus.Resolved))
    assertEquals(decoded.map(_.renderingKind), Right(InferTypeRenderingKind.SymbolSignature))
    assertEquals(decoded.map(_.context.kind), Right(InferTypeContextKind.NarrowRuntime))
    assertEquals(decoded.map(_.context.classpathEntryCount), Right(0))
    assertEquals(decoded.map(_.context.workspaceProvided), Right(true))
    assert(decoded.exists(_.rendering.exists(_.contains("Option[String]"))))
    assert(!stdout.contains("rawCompilerRendering"))
    assert(!stdout.contains("classpathEntries"))
    assert(!stdout.contains("sbtProject"))
    assert(!stdout.contains("sbtConfiguration"))

  test("infer-type unresolved is a successful semantic result"):
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "4",
        "--col",
        "1",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[InferTypeReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.status), Right(InferTypeStatus.Unresolved))
    assertEquals(decoded.map(_.rendering), Right(None))
    assertEquals(decoded.map(_.renderingKind), Right(InferTypeRenderingKind.NoRendering))
    assert(decoded.exists(_.warnings.last.contains("cannot determine why")))

  test("infer-type human output is concise"):
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "4",
        "--col",
        "1"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    assert(result.stdout.exists(_.endsWith(":4:1: <unresolved>")))

  test("infer-type returns non-zero with empty stdout for invalid classpath"):
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "8",
        "--col",
        "7",
        "--classpath",
        "target/task067-missing-classpath",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("Classpath entry does not exist")))

  test("infer-type validates unsupported classpath files and workspace paths at the CLI boundary"):
    val unsupported = Files.createTempFile("task067-classpath", ".txt")
    val workspaceFile = Files.createTempFile("task067-workspace", ".txt")
    try
      val invalidClasspath = CliApp.run(
        List(
          "infer-type",
          "--file",
          "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
          "--line",
          "8",
          "--col",
          "7",
          "--classpath",
          unsupported.toString,
          "--json"
        ),
        FakeSbtRunner(),
        Path.of(".")
      )
      assertEquals(invalidClasspath.stdout, None)
      assertEquals(invalidClasspath.exitCode, 1)
      assert(invalidClasspath.stderr.exists(_.contains("not a JAR file or directory")))

      val invalidWorkspace = CliApp.run(
        List(
          "infer-type",
          "--file",
          "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
          "--line",
          "8",
          "--col",
          "7",
          "--workspace",
          workspaceFile.toString,
          "--json"
        ),
        FakeSbtRunner(),
        Path.of(".")
      )
      assertEquals(invalidWorkspace.stdout, None)
      assertEquals(invalidWorkspace.exitCode, 1)
      assert(invalidWorkspace.stderr.exists(_.contains("Workspace is not a directory")))
    finally
      Files.deleteIfExists(unsupported)
      Files.deleteIfExists(workspaceFile)

  test("infer-type public context count reflects normalized duplicate removal and accepts a JAR"):
    val scalaLibrary = Path.of(classOf[scala.Option[?]].getProtectionDomain.getCodeSource.getLocation.toURI)
    val directory = "modules/presentation-compiler/target"
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "8",
        "--col",
        "7",
        "--classpath",
        directory,
        "--classpath",
        scalaLibrary.toString,
        "--classpath",
        directory,
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[InferTypeReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.context.kind), Right(InferTypeContextKind.ExplicitClasspath))
    assertEquals(decoded.map(_.context.classpathEntryCount), Right(2))
    assert(
      decoded.exists(
        _.warnings.contains(
          "Beyond the built-in Scala runtime, only the supplied compiled classpath entries are available."
        )
      )
    )

  test("infer-type sbt context exposes safe provenance without acquired paths"):
    val projectId = project("app-2")
    val acquiredPath = Path.of("modules/presentation-compiler/target").toAbsolutePath.normalize()
    val acquirer = FakeSbtClasspathAcquirer(
      Right(
        SbtClasspathResult(
          projectId,
          SbtClasspathConfiguration.Compile,
          List(SbtClasspathEntry(acquiredPath, SbtClasspathEntryKind.Directory))
        )
      )
    )
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "8",
        "--col",
        "7",
        "--workspace",
        ".",
        "--sbt-project",
        "app-2",
        "--sbt-configuration",
        "Compile",
        "--json"
      ),
      FakeSbtRunner(),
      acquirer,
      Path.of(".")
    )

    assertEquals(result.exitCode, 0)
    assertEquals(result.stderr, None)
    assertEquals(acquirer.calls, 1)
    val stdout = result.stdout.getOrElse("")
    val decoded = decode[InferTypeReport](stdout)
    assertEquals(decoded.map(_.context.kind), Right(InferTypeContextKind.SbtClasspath))
    assertEquals(decoded.map(_.context.classpathEntryCount), Right(1))
    assertEquals(decoded.map(_.context.sbtProject), Right(Some("app-2")))
    assertEquals(decoded.map(_.context.sbtConfiguration), Right(Some("Compile")))
    assertEquals(
      decoded.map(_.context.acquisitionOrigin),
      Right(Some(InferTypeAcquisitionOrigin.FreshSbt))
    )
    assertEquals(
      decoded.map(_.context.freshnessAssessment),
      Right(Some(InferTypeFreshnessAssessment.FreshBySbtEvaluation))
    )
    assert(
      decoded.exists(
        _.warnings.exists(
          _.contains(
            "sbt evaluated project 'app-2' configuration 'Compile' for this invocation"
          )
        )
      )
    )
    assert(!stdout.contains(acquiredPath.toString))

  test("infer-type explicit reuse exposes honest cached provenance without launching acquirer"):
    val projectId = project("app-2")
    val acquiredPath = Path.of("modules/presentation-compiler/target").toAbsolutePath.normalize()
    val resultValue = SbtClasspathResult(
      projectId,
      SbtClasspathConfiguration.Compile,
      List(SbtClasspathEntry(acquiredPath, SbtClasspathEntryKind.Directory))
    )
    val acquirer = FakeSbtClasspathAcquirer(
      Left(SbtClasspathFailure.Process("must not launch"))
    )
    val cacheService = FakeSbtClasspathCacheService(
      Right(
        SbtClasspathCacheResolution(
          resultValue,
          SbtClasspathCacheResolutionOrigin.CachedExplicitReuse
        )
      )
    )
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
        "--line",
        "8",
        "--col",
        "7",
        "--workspace",
        ".",
        "--sbt-project",
        "app-2",
        "--sbt-configuration",
        "Compile",
        "--sbt-cache-mode",
        "reuse",
        "--json"
      ),
      FakeSbtRunner(),
      acquirer,
      cacheService,
      Path.of(".")
    )

    assertEquals(result.exitCode, 0)
    assertEquals(result.stderr, None)
    assertEquals(acquirer.calls, 0)
    assertEquals(cacheService.calls, 1)
    assertEquals(cacheService.modes, List(SbtClasspathCacheMode.Reuse))
    val stdout = result.stdout.getOrElse("")
    val decoded = decode[InferTypeReport](stdout)
    assertEquals(
      decoded.map(_.context.acquisitionOrigin),
      Right(Some(InferTypeAcquisitionOrigin.CachedExplicitReuse))
    )
    assertEquals(
      decoded.map(_.context.freshnessAssessment),
      Right(Some(InferTypeFreshnessAssessment.ReusedWithMatchingEvidence))
    )
    assert(
      decoded.exists(
        _.warnings.contains(
          "The compiled classpath was reused from an explicitly requested cache after matching bounded evidence; sbt did not run for this invocation."
        )
      )
    )
    assert(
      decoded.exists(
        _.warnings.contains(
          "Matching bounded cache evidence does not prove current sbt freshness or cover arbitrary build inputs."
        )
      )
    )
    assert(!stdout.contains(acquiredPath.toString))

  test("infer-type validates and forwards selected Java without exposing its path"):
    val javaHome = fakeJavaHome("task166-cli-infer-java-")
    val cacheService = FakeSbtClasspathCacheService(
      Left(SbtClasspathCacheFailure.Missing("selected cache deliberately absent"))
    )
    try
      val result = CliApp.run(
        List(
          "infer-type",
          "--file",
          "Main.scala",
          "--line",
          "1",
          "--col",
          "1",
          "--workspace",
          ".",
          "--sbt-project",
          "app-2",
          "--sbt-configuration",
          "Compile",
          "--sbt-java-home",
          javaHome.toString,
          "--json"
        ),
        FakeSbtRunner(),
        FakeSbtClasspathAcquirer(Left(SbtClasspathFailure.Process("must not launch directly"))),
        cacheService,
        Path.of(".")
      )

      assertEquals(result.exitCode, 1)
      assertEquals(cacheService.calls, 1)
      assertEquals(
        cacheService.requests.flatMap(_.targetJava).map(_.canonicalHome),
        List(javaHome.toRealPath())
      )
      assert(!result.stderr.getOrElse("").contains(javaHome.toString))
    finally deleteFakeJavaHome(javaHome)

  test("infer-type keeps acquisition failure separate from semantic unresolved"):
    val acquirer = FakeSbtClasspathAcquirer(
      Left(SbtClasspathFailure.Process("bounded acquisition failure"))
    )
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "Main.scala",
        "--line",
        "1",
        "--col",
        "1",
        "--workspace",
        ".",
        "--sbt-project",
        "app-2",
        "--sbt-configuration",
        "Compile",
        "--json"
      ),
      FakeSbtRunner(),
      acquirer,
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assertEquals(result.stdout, None)
    assertEquals(result.stderr, Some("bounded acquisition failure"))
    assertEquals(acquirer.calls, 1)

  test("infer-type parser validation failures do not launch sbt acquisition"):
    val acquirer = FakeSbtClasspathAcquirer(
      Left(SbtClasspathFailure.Process("must not launch"))
    )
    val result = CliApp.run(
      List(
        "infer-type",
        "--file",
        "Main.scala",
        "--line",
        "1",
        "--col",
        "1",
        "--workspace",
        ".",
        "--sbt-project",
        "app;test",
        "--sbt-configuration",
        "Compile",
        "--json"
      ),
      FakeSbtRunner(),
      acquirer,
      Path.of(".")
    )

    assertEquals(result.exitCode, 1)
    assertEquals(result.stdout, None)
    assertEquals(acquirer.calls, 0)

  test("old infer-type context JSON remains decodable without sbt provenance fields"):
    val oldJson =
      """{"schemaVersion":"semantic-scala.infer-type-result.v1","status":"Unresolved","rendering":null,"renderingKind":"NoRendering","source":"<WORKSPACE>/Main.scala","position":{"line":1,"column":1,"encoding":"UTF-16"},"range":null,"context":{"kind":"ExplicitClasspath","classpathEntryCount":2,"workspaceProvided":true},"warnings":[]}"""

    val decoded = decode[InferTypeReport](oldJson)
    assertEquals(decoded.map(_.context.kind), Right(InferTypeContextKind.ExplicitClasspath))
    assertEquals(decoded.map(_.context.sbtProject), Right(None))
    assertEquals(decoded.map(_.context.sbtConfiguration), Right(None))
    assertEquals(decoded.map(_.context.acquisitionOrigin), Right(None))
    assertEquals(decoded.map(_.context.freshnessAssessment), Right(None))

  test("infer-type-batch resolves one shared context and preserves ordered mixed outcomes"):
    val requestPath = Files.createTempFile(Path.of("target"), "task072-cli-batch-", ".json")
    val projectId = project("cli")
    val acquiredPath = Path.of("modules/presentation-compiler/target").toAbsolutePath.normalize()
    val resolution = SbtClasspathCacheResolution(
      SbtClasspathResult(
        projectId,
        SbtClasspathConfiguration.Compile,
        List(SbtClasspathEntry(acquiredPath, SbtClasspathEntryKind.Directory))
      ),
      SbtClasspathCacheResolutionOrigin.FreshSbt
    )
    val cacheService = FakeSbtClasspathCacheService(Right(resolution))
    val acquirer = FakeSbtClasspathAcquirer(Left(SbtClasspathFailure.Process("must not launch directly")))
    val request = InferTypeBatchRequest(
      requests = List(
        InferTypeBatchRequestItem(
          "resolved",
          "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
          8,
          7
        ),
        InferTypeBatchRequestItem("missing", "Missing.scala", 1, 1),
        InferTypeBatchRequestItem(
          "unresolved",
          "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
          4,
          1
        ),
        InferTypeBatchRequestItem(
          "resolved-after",
          "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala",
          8,
          7
        )
      )
    )
    try
      Files.writeString(requestPath, request.asJson.noSpaces)
      val result = CliApp.run(
        List(
          "infer-type-batch",
          "--requests",
          requestPath.toString,
          "--workspace",
          ".",
          "--sbt-project",
          "cli",
          "--sbt-configuration",
          "Compile",
          "--json"
        ),
        FakeSbtRunner(),
        acquirer,
        cacheService,
        Path.of(".")
      )

      assertEquals(result.exitCode, 0)
      assertEquals(result.stderr, None)
      assertEquals(cacheService.calls, 1)
      assertEquals(cacheService.modes, List(SbtClasspathCacheMode.Fresh))
      assertEquals(acquirer.calls, 0)
      val stdout = result.stdout.getOrElse("")
      val decoded = decode[InferTypeBatchReport](stdout)
      assertEquals(decoded.map(_.schemaVersion), Right(InferTypeBatchReport.SchemaVersion))
      assertEquals(decoded.map(_.requestCount), Right(4))
      assertEquals(decoded.map(_.results.map(_.id)), Right(request.requests.map(_.id)))
      assertEquals(
        decoded.map(_.results.map(_.status)),
        Right(
          List(
            InferTypeBatchItemStatus.Resolved,
            InferTypeBatchItemStatus.InvalidRequest,
            InferTypeBatchItemStatus.Unresolved,
            InferTypeBatchItemStatus.Resolved
          )
        )
      )
      assertEquals(decoded.map(_.context.kind), Right(InferTypeContextKind.SbtClasspath))
      assertEquals(
        decoded.map(_.context.acquisitionOrigin),
        Right(Some(InferTypeAcquisitionOrigin.FreshSbt))
      )
      assert(!stdout.contains(acquiredPath.toString))
      assert(!stdout.contains(System.getProperty("user.home")))
    finally Files.deleteIfExists(requestPath)

  test("infer-type-batch validates and forwards selected Java once"):
    val requestPath = Files.createTempFile(Path.of("target"), "task166-cli-batch-java-", ".json")
    val javaHome = fakeJavaHome("task166-cli-batch-java-home-")
    val cacheService = FakeSbtClasspathCacheService(
      Left(SbtClasspathCacheFailure.Missing("selected cache deliberately absent"))
    )
    try
      Files.writeString(
        requestPath,
        InferTypeBatchRequest(
          requests = List(InferTypeBatchRequestItem("one", "Main.scala", 1, 1))
        ).asJson.noSpaces
      )
      val result = CliApp.run(
        List(
          "infer-type-batch",
          "--requests",
          requestPath.toString,
          "--workspace",
          ".",
          "--sbt-project",
          "app-2",
          "--sbt-configuration",
          "Compile",
          "--sbt-java-home",
          javaHome.toString,
          "--json"
        ),
        FakeSbtRunner(),
        FakeSbtClasspathAcquirer(Left(SbtClasspathFailure.Process("must not launch directly"))),
        cacheService,
        Path.of(".")
      )

      assertEquals(result.exitCode, 1)
      assertEquals(cacheService.calls, 1)
      assertEquals(
        cacheService.requests.flatMap(_.targetJava).map(_.canonicalHome),
        List(javaHome.toRealPath())
      )
      assert(!result.stderr.getOrElse("").contains(javaHome.toString))
    finally
      Files.deleteIfExists(requestPath)
      deleteFakeJavaHome(javaHome)

  test("infer-type-batch structural failures happen before context acquisition with no JSON"):
    val requestPath = Files.createTempFile(Path.of("target"), "task072-cli-invalid-batch-", ".json")
    val cacheService = FakeSbtClasspathCacheService(
      Left(SbtClasspathCacheFailure.RefreshAcquisition(SbtClasspathFailure.Process("must not acquire")))
    )
    try
      Files.writeString(
        requestPath,
        """{"schemaVersion":"semantic-scala.infer-type-batch-request.v1","requests":[{"id":"duplicate","file":"Main.scala","line":1,"column":1},{"id":"duplicate","file":"Other.scala","line":1,"column":1}]}"""
      )
      val result = CliApp.run(
        List(
          "infer-type-batch",
          "--requests",
          requestPath.toString,
          "--workspace",
          ".",
          "--sbt-project",
          "cli",
          "--sbt-configuration",
          "Compile",
          "--json"
        ),
        FakeSbtRunner(),
        FakeSbtClasspathAcquirer(Left(SbtClasspathFailure.Process("must not launch"))),
        cacheService,
        Path.of(".")
      )
      assertEquals(result.exitCode, 1)
      assertEquals(result.stdout, None)
      assert(result.stderr.exists(_.contains("Duplicate")))
      assertEquals(cacheService.calls, 0)
    finally Files.deleteIfExists(requestPath)

  test("infer-type-batch shared acquisition failure remains batch-level"):
    val requestPath = Files.createTempFile(Path.of("target"), "task072-cli-acquisition-batch-", ".json")
    val cacheService = FakeSbtClasspathCacheService(
      Left(SbtClasspathCacheFailure.RefreshAcquisition(SbtClasspathFailure.Process("bounded batch acquisition failure")))
    )
    try
      Files.writeString(
        requestPath,
        InferTypeBatchRequest(
          requests = List(InferTypeBatchRequestItem("one", "Main.scala", 1, 1))
        ).asJson.noSpaces
      )
      val result = CliApp.run(
        List(
          "infer-type-batch",
          "--requests",
          requestPath.toString,
          "--workspace",
          ".",
          "--sbt-project",
          "cli",
          "--sbt-configuration",
          "Compile",
          "--sbt-cache-mode",
          "reuse",
          "--json"
        ),
        FakeSbtRunner(),
        FakeSbtClasspathAcquirer(Left(SbtClasspathFailure.Process("must not launch"))),
        cacheService,
        Path.of(".")
      )
      assertEquals(result.exitCode, 1)
      assertEquals(result.stdout, None)
      assert(result.stderr.exists(_.contains("bounded batch acquisition failure")))
      assertEquals(cacheService.calls, 1)
      assertEquals(cacheService.modes, List(SbtClasspathCacheMode.Reuse))
    finally Files.deleteIfExists(requestPath)

  test("infer-type-batch rejects an oversized request file before context acquisition"):
    val requestPath = Files.createTempFile(Path.of("target"), "task072-cli-oversized-batch-", ".json")
    val cacheService = FakeSbtClasspathCacheService(
      Left(SbtClasspathCacheFailure.RefreshAcquisition(SbtClasspathFailure.Process("must not acquire")))
    )
    try
      Files.write(
        requestPath,
        Array.fill((InferTypeBatchBounds.MaxRequestFileBytes + 1).toInt)('x'.toByte)
      )
      val result = CliApp.run(
        List(
          "infer-type-batch",
          "--requests",
          requestPath.toString,
          "--workspace",
          ".",
          "--sbt-project",
          "cli",
          "--sbt-configuration",
          "Compile",
          "--json"
        ),
        FakeSbtRunner(),
        FakeSbtClasspathAcquirer(Left(SbtClasspathFailure.Process("must not launch"))),
        cacheService,
        Path.of(".")
      )
      assertEquals(result.exitCode, 1)
      assertEquals(result.stdout, None)
      assert(result.stderr.exists(_.contains("exceeds")))
      assertEquals(cacheService.calls, 0)
    finally Files.deleteIfExists(requestPath)

  test("reconcile-symbol --json returns ReconciliationResult JSON only"):
    val result = CliApp.run(
      List(
        "reconcile-symbol",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala",
        "--line",
        "6",
        "--col",
        "16",
        "--semanticdb",
        "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[ReconciliationResult](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(ReconciliationResult.SchemaVersion))
    assert(decoded.exists(_.file.endsWith("Main.scala")))
    assert(decoded.exists(_.result.status != ReconciliationStatus.ExactMatch))

  test("reconcile-symbol valid no-match query exits zero"):
    val result = CliApp.run(
      List(
        "reconcile-symbol",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala",
        "--line",
        "6",
        "--col",
        "1",
        "--semanticdb",
        "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[ReconciliationResult](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(ReconciliationResult.SchemaVersion))
    assertEquals(decoded.map(_.result.status), Right(ReconciliationStatus.NoMatch))

  test("reconcile-symbol returns non-zero and stderr for invalid inputs"):
    val invalidSource = CliApp.run(
      List(
        "reconcile-symbol",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Missing.scala",
        "--line",
        "1",
        "--col",
        "1",
        "--semanticdb",
        "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(invalidSource.stdout, None)
    assertEquals(invalidSource.exitCode, 1)
    assert(invalidSource.stderr.exists(_.contains("does not exist")))

    val invalidSemanticdb = CliApp.run(
      List(
        "reconcile-symbol",
        "--file",
        "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala",
        "--line",
        "1",
        "--col",
        "1",
        "--semanticdb",
        "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/missing.semanticdb",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(invalidSemanticdb.stdout, None)
    assertEquals(invalidSemanticdb.exitCode, 1)
    assert(invalidSemanticdb.stderr.exists(_.contains("does not exist")))

  test("effect-summary --json returns EffectSummaryReport JSON only"):
    val result = CliApp.run(
      List(
        "effect-summary",
        "--file",
        "modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stderr, None)
    assertEquals(result.exitCode, 0)
    val decoded = decode[EffectSummaryReport](result.stdout.getOrElse(""))
    assertEquals(decoded.map(_.schemaVersion), Right(EffectSummaryReport.SchemaVersion))
    assert(decoded.exists(_.source.endsWith("UserRepo.scala")))
    assertEquals(decoded.map(_.methods.find(_.name == "find").map(_.effectCategory)), Right(Some("generic-effect")))
    assertEquals(decoded.map(_.methods.find(_.name == "find").flatMap(_.qualifiedName)), Right(Some("UserRepo.find")))
    assertEquals(decoded.map(_.methods.find(_.name == "find").flatMap(_.packageQualifiedName)), Right(Some("example.UserRepo.find")))
    assertEquals(decoded.map(_.methods.find(_.name == "find").flatMap(_.sourceFile).exists(_.endsWith("UserRepo.scala"))), Right(true))
    assertEquals(decoded.map(_.methods.find(_.name == "inferred").map(_.confidence)), Right(Some("unknown")))

  test("effect-summary returns non-zero and stderr for invalid source file"):
    val result = CliApp.run(
      List(
        "effect-summary",
        "--file",
        "modules/fp-analyzers/src/test/resources/effect-fixtures/simple/Missing.scala",
        "--json"
      ),
      FakeSbtRunner(),
      Path.of(".")
    )

    assertEquals(result.stdout, None)
    assertEquals(result.exitCode, 1)
    assert(result.stderr.exists(_.contains("does not exist")))

  private final case class FakeSbtRunner(
    compileResult: SbtRunResult = SbtRunResult("compile", 0, "", ""),
    testResult: SbtRunResult = SbtRunResult("test", 0, "", "")
  ) extends SbtRunner:
    var compileProjects = List.empty[Option[SbtProjectId]]
    var testProjects = List.empty[Option[SbtProjectId]]
    var compileJavaHomes = List.empty[Option[ValidatedSbtJavaHome]]
    var testJavaHomes = List.empty[Option[ValidatedSbtJavaHome]]

    override def compile(
        projectDir: Path,
        project: Option[SbtProjectId],
        targetJava: Option[ValidatedSbtJavaHome]
    ): SbtRunResult =
      compileProjects = compileProjects :+ project
      compileJavaHomes = compileJavaHomes :+ targetJava
      compileResult

    override def test(
        projectDir: Path,
        project: Option[SbtProjectId],
        targetJava: Option[ValidatedSbtJavaHome]
    ): SbtRunResult =
      testProjects = testProjects :+ project
      testJavaHomes = testJavaHomes :+ targetJava
      testResult

  private final case class FakeSbtClasspathAcquirer(
      result: Either[SbtClasspathFailure, SbtClasspathResult]
  ) extends SbtClasspathAcquirer:
    var calls = 0

    override def acquire(
        request: SbtClasspathRequest
    ): Either[SbtClasspathFailure, SbtClasspathResult] =
      calls += 1
      result

  private final case class FakeSbtClasspathCacheService(
      result: Either[SbtClasspathCacheFailure, SbtClasspathCacheResolution]
  ) extends SbtClasspathCacheService:
    var calls = 0
    var modes = List.empty[SbtClasspathCacheMode]
    var requests = List.empty[SbtClasspathRequest]

    override def resolve(
        request: SbtClasspathRequest,
        mode: SbtClasspathCacheMode
    ): Either[SbtClasspathCacheFailure, SbtClasspathCacheResolution] =
      calls += 1
      modes = modes :+ mode
      requests = requests :+ request
      result

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def fakeJavaHome(prefix: String): Path =
    val home = Files.createTempDirectory(prefix)
    val bin = Files.createDirectory(home.resolve("bin"))
    val launcher = bin.resolve("java")
    Files.writeString(launcher, "#!/bin/sh\nprintf 'fake-java 25\\n' >&2\n")
    Files.setPosixFilePermissions(
      launcher,
      EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
      )
    )
    Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25\"\n")
    home

  private def deleteFakeJavaHome(home: Path): Unit =
    Files.deleteIfExists(home.resolve("release"))
    Files.deleteIfExists(home.resolve("bin/java"))
    Files.deleteIfExists(home.resolve("bin"))
    Files.deleteIfExists(home)
