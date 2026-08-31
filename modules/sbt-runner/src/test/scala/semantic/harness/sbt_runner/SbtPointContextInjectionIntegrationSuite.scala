package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.concurrent.duration.DurationInt

class SbtPointContextInjectionIntegrationSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds

  test("v5 injected settings execute admitted graph traversal and continue after unavailable class directory"):
    val workspace = Files.createTempDirectory("point-context-v5-real-sbt-")
    try
      Files.createDirectories(workspace.resolve("project"))
      Files.writeString(workspace.resolve("project/build.properties"), "sbt.version=1.12.14\n")
      Files.writeString(workspace.resolve("project/UnavailableClassDirectory.scala"), unavailableClassDirectory)
      Files.writeString(workspace.resolve("build.sbt"), buildDefinition)
      Files.createDirectories(workspace.resolve("outputs/app"))
      List("macros", "core", "laws", "explicit", "leaf").foreach(name =>
        Files.createDirectories(workspace.resolve(s"outputs/$name"))
      )
      val invalidSource = workspace.resolve("app/src/main/scala/DoesNotCompile.scala")
      Files.createDirectories(invalidSource.getParent)
      Files.writeString(invalidSource, "this is intentionally invalid Scala\n")

      val request = SbtPointContextRequest(
        workspace,
        project("app"),
        Some(scalaVersion("3.3.3")),
        includeExistingInternalOutputs = true
      )
      val receipt = ProcessSbtPointContextAcquirer(
        120.seconds,
        SbtPointContextProcess.default
      ).acquire(request).fold(failure => fail(failure.toString), identity)

      assertEquals(
        receipt.internalDependencies.map(value => (value.project.value, value.role, value.compileMapping)),
        List(
          ("macros", SbtInternalDependencyRole.Direct, SbtCompileDependencyMapping.DefaultCompileToCompile),
          ("core", SbtInternalDependencyRole.Transitive, SbtCompileDependencyMapping.DefaultCompileToCompile),
          ("laws", SbtInternalDependencyRole.Direct, SbtCompileDependencyMapping.DefaultCompileToCompile),
          ("explicit", SbtInternalDependencyRole.Direct, SbtCompileDependencyMapping.ExplicitCompileToCompile),
          ("leaf", SbtInternalDependencyRole.Transitive, SbtCompileDependencyMapping.DefaultCompileToCompile)
        )
      )
      assert(receipt.internalDependencies.forall(_.classDirectoryPresent))
      assertEquals(
        receipt.internalDependencyExclusions.map(value =>
          (value.project.value, value.role, value.compileMapping, value.reason)
        ),
        List(
          (
            "core",
            SbtInternalDependencyRole.Direct,
            SbtCompileDependencyMapping.ExcludedNoCompileToCompile,
            SbtInternalDependencyExclusionReason.NoCompileToCompileMapping
          ),
          (
            "broken",
            SbtInternalDependencyRole.Direct,
            SbtCompileDependencyMapping.DefaultCompileToCompile,
            SbtInternalDependencyExclusionReason.ClassDirectorySettingUnavailable
          ),
          (
            "testKit",
            SbtInternalDependencyRole.Direct,
            SbtCompileDependencyMapping.ExcludedNoCompileToCompile,
            SbtInternalDependencyExclusionReason.NoCompileToCompileMapping
          ),
          (
            "ambiguous",
            SbtInternalDependencyRole.Direct,
            SbtCompileDependencyMapping.UnsupportedOrAmbiguous,
            SbtInternalDependencyExclusionReason.UnsupportedOrAmbiguousMapping
          )
        )
      )
      assert(!receipt.internalDependencies.exists(_.project.value == "aggregateOnly"))
      assert(!receipt.internalDependencyExclusions.exists(_.project.value == "aggregateOnly"))
      assert(Files.exists(invalidSource))
    finally deleteRecursively(workspace)

  private val buildDefinition =
    """ThisBuild / scalaVersion := "3.3.3"
      |ThisBuild / autoScalaLibrary := false
      |ThisBuild / managedScalaInstance := false
      |ThisBuild / Compile / externalDependencyClasspath := Seq.empty
      |ThisBuild / Compile / semanticdbTargetRoot := file("semanticdb")
      |
      |lazy val core = project.in(file("core")).settings(Compile / classDirectory := file("outputs/core"))
      |lazy val macros = project.in(file("macros"))
      |  .settings(Compile / classDirectory := file("outputs/macros"))
      |  .dependsOn(core)
      |lazy val laws = project.in(file("laws"))
      |  .settings(Compile / classDirectory := file("outputs/laws"))
      |  .dependsOn(core % "compile->compile")
      |lazy val explicit = project.in(file("explicit"))
      |  .settings(Compile / classDirectory := file("outputs/explicit"))
      |lazy val leaf = project.in(file("leaf")).settings(Compile / classDirectory := file("outputs/leaf"))
      |lazy val broken = project.in(file("broken"))
      |  .settings(Compile / classDirectory := UnavailableClassDirectory.value)
      |  .dependsOn(leaf)
      |lazy val testKit = project.in(file("test-kit"))
      |lazy val ambiguous = project.in(file("ambiguous"))
      |lazy val aggregateOnly = project.in(file("aggregate-only"))
      |lazy val app = project.in(file("app"))
      |  .settings(Compile / classDirectory := file("outputs/app"))
      |  .dependsOn(
      |    core % "test->test",
      |    macros,
      |    laws,
      |    explicit % "compile->compile",
      |    broken,
      |    testKit % "test->test",
      |    ambiguous % "compile->compile,test"
      |  )
      |  .aggregate(aggregateOnly)
      |""".stripMargin

  private val unavailableClassDirectory =
    """object UnavailableClassDirectory {
      |  val value: java.io.File = new java.io.File("unrepresentable-class-directory") {
      |    override def getCanonicalFile: java.io.File = sys.error("unavailable class directory")
      |  }
      |}
      |""".stripMargin

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(fail(_), identity)

  private def scalaVersion(value: String): SbtScalaVersion =
    SbtScalaVersion.parse(value).fold(fail(_), identity)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
