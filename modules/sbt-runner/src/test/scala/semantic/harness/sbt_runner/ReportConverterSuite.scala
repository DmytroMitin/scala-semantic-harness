package semantic.harness.sbt_runner

class ReportConverterSuite extends munit.FunSuite:
  test("compile success converts to empty successful CompileReport"):
    val report = ReportConverter.compileReport(SbtRunResult("compile", 0, "[success] Total time: 1 s", ""))

    assertEquals(report.success, true)
    assertEquals(report.diagnostics, Nil)

  test("compile failure produces a diagnostic and extracts position when available"):
    val output =
      """[error] -- [E006] Not Found Error: /tmp/example/src/main/scala/Main.scala:3:10
        |[error] 3 |  println(missingValue)
        |[error]   |          ^^^^^^^^^^^^
        |[error] one error found
        |""".stripMargin

    val report = ReportConverter.compileReport(SbtRunResult("compile", 1, output, ""))

    assertEquals(report.success, false)
    assertEquals(report.diagnostics.nonEmpty, true)
    assertEquals(report.diagnostics.head.position.map(_.line), Some(3))

  test("test success parses munit-style counts when available"):
    val output = "[info] Passed: Total 3, Failed 0, Errors 0, Passed 3"
    val report = ReportConverter.testReport(SbtRunResult("test", 0, output, ""))

    assertEquals(report.success, true)
    assertEquals(report.total, 3)
    assertEquals(report.passed, 3)
    assertEquals(report.failed, 0)

  test("test failure uses zero counts when counts are unavailable"):
    val output = "[error] expected true but was false"
    val report = ReportConverter.testReport(SbtRunResult("test", 1, output, ""))

    assertEquals(report.success, false)
    assertEquals(report.total, 0)
    assertEquals(report.passed, 0)
    assertEquals(report.failed, 0)
    assertEquals(report.failures.nonEmpty, true)
