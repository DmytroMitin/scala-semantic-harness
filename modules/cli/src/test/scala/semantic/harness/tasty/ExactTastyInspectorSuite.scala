package semantic.harness.tasty

import java.nio.file.Path

class ExactTastyInspectorSuite extends munit.FunSuite:
  test("exact target Scala worker inspects a real TASTy artifact in a child JVM"):
    val workspace = Path.of(sys.props("user.dir")).toRealPath()
    val source = workspace.resolve(
      "modules/cli/src/test/scala/semantic/harness/tasty/SimpleTastyFixture.scala"
    )
    val tasty = workspace.resolve(
      "modules/cli/target/scala-3.3.3/test-classes/semantic/harness/tasty/SimpleTastyFixture.tasty"
    )
    val candidate = TastyArtifactCandidate(tasty, "SimpleTastyFixture.tasty", java.nio.file.Files.size(tasty), "a" * 64)

    val result = ExactTastyInspector.default.inspect(
      targetScalaVersion = "3.3.3",
      workspace = workspace,
      source = source,
      line = 4,
      column = 25,
      candidates = List(candidate)
    )

    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))
    val evidence = result.toOption.get
    assertEquals(evidence.scalaVersion, "3.3.3")
    assertEquals(evidence.inspectedCount, 1)
    assert(evidence.trees.nonEmpty)
    assert(evidence.trees.head.tree.renderedType.exists(_.endsWith("String")))
    assertEquals(evidence.provenance.targetCompilerOptionsReplayed, false)
    assertEquals(evidence.provenance.targetPluginsReplayed, false)

  test("unstable and non-Scala-3 target versions fail closed before inspection"):
    val invalid = ExactTastyInspector.default.inspect(
      "3.8.4-RC1",
      Path.of("."),
      Path.of("ignored.scala"),
      1,
      1,
      Nil
    )
    assertEquals(invalid, Left(ExactTastyInspectorFailure.UnsupportedTargetScala))

  test("worker boundary has fixed memory, environment, and no target compiler option replay"):
    val source = java.nio.file.Files.readString(
      Path.of("modules/cli/src/main/scala/semantic/harness/tasty/ExactTastyInspector.scala")
    )
    val worker = java.nio.file.Files.readString(
      Path.of("modules/cli/src/main/resources/semantic/harness/tasty/ExactScalaTastyInspectorChild.scala")
    )
    assert(source.contains("-Xmx512m"))
    assert(source.contains("environment.clear()"))
    assert(!worker.contains("scalacOptions"))
    assert(!worker.contains("-Xplugin"))
    assert(!worker.contains("-P:"))
