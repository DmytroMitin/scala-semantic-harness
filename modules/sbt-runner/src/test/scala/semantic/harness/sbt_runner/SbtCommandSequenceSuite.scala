package semantic.harness.sbt_runner

class SbtCommandSequenceSuite extends munit.FunSuite:
  test("build-oracle commands preserve root behavior and select one scoped task"):
    val app = project("app-2")

    assertEquals(SbtCommandSequence.build(None, SbtFixedTask.Compile), "compile")
    assertEquals(
      SbtCommandSequence.build(Some(app), SbtFixedTask.Compile),
      "project app-2; Compile / compile"
    )
    assertEquals(SbtCommandSequence.build(None, SbtFixedTask.Test), "test")
    assertEquals(
      SbtCommandSequence.build(Some(app), SbtFixedTask.Test),
      "project app-2; Test / test"
    )

  test("classpath and TASTy commands are one selected fixed-task sequence"):
    val app = project("app")

    assertEquals(
      SbtCommandSequence.selected(app, SbtFixedTask.CompileClasspath),
      "project app; semanticScalaInternalExportCompileClasspath"
    )
    assertEquals(
      SbtCommandSequence.selected(app, SbtFixedTask.TestClasspath),
      "project app; semanticScalaInternalExportTestClasspath"
    )
    assertEquals(
      SbtCommandSequence.selected(app, SbtFixedTask.TastyCompileReceipt),
      "project app; semanticScalaInternalTastyCompileReceipt"
    )

  test("project validation cannot add an sbt command or alter sequencing"):
    val unsafe = List(
      "app; test",
      "app compile",
      "app/Compile",
      "app\nreload",
      "-app"
    )

    unsafe.foreach(value => assert(SbtProjectId.parse(value).isLeft, clue(value)))
    val longestFixedEvidence =
      SbtCommandSequence.selected(project("app"), SbtFixedTask.CompileClasspath)
    assertEquals(longestFixedEvidence.count(_ == ';'), 1)
    assert(!longestFixedEvidence.contains('\n'))
    assert(!longestFixedEvidence.contains("sbt"))
    assert(longestFixedEvidence.length < 128)

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)
