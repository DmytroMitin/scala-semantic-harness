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
    assertEquals(
      SbtCommandSequence.selected(app, SbtFixedTask.TargetContextReceipt),
      "project app; semanticScalaInternalTargetContextReceipt"
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

  test("validated Scala axes precede the selected project and fixed root-only task"):
    val axis = scalaVersion("3.3.7-RC1")
    val command = SbtCommandSequence.selected(
      project("kernelJVM"),
      SbtFixedTask.SourceMappingRootReceipt,
      Some(axis)
    )

    assertEquals(
      command,
      "++ 3.3.7-RC1; project kernelJVM; semanticScalaInternalSourceMappingRootReceipt"
    )
    assertEquals(command.count(_ == ';'), 2)
    assert(!command.contains("++ 3.3.7-RC1!"))

  test("Scala axis validation admits versions and rejects command and path shapes"):
    List("2.13", "2.13.18", "3.3.7", "3.8.0-RC1", "3.9.0-M2", "3.3.7-bin-20260101.abc")
      .foreach(value => assertEquals(SbtScalaVersion.parse(value).map(_.value), Right(value)))

    List(
      "",
      "3",
      " 3.3.7",
      "3.3.7 ",
      "3.3.7;compile",
      "3.3.7'",
      "3.3.7`",
      "3.3.7\nreload",
      "../3.3.7",
      "3:3.7",
      "++3.3.7"
    ).foreach(value => assert(SbtScalaVersion.parse(value).isLeft, clue(value)))

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def scalaVersion(value: String): SbtScalaVersion =
    SbtScalaVersion.parse(value).fold(message => fail(message), identity)
