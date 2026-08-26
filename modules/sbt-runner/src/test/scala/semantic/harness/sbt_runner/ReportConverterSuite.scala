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

  test("test success uses structured counts including an implicit skipped remainder"):
    val result = SbtRunResult(
      "test",
      0,
      "human console wording is not an oracle",
      "",
      Some(
        SbtStructuredTestResult(
          success = true,
          SbtTestCounts(total = 4, passed = 3, failures = 0, errors = 0, skipped = 1)
        )
      )
    )
    val report = ReportConverter.testReport(result)

    assertEquals(report.success, true)
    assertEquals(report.total, 4)
    assertEquals(report.passed, 3)
    assertEquals(report.failed, 0)

  test("test failure combines structured framework failures and errors"):
    val result = SbtRunResult(
      "test",
      0,
      "",
      "[error] suite failed",
      Some(
        SbtStructuredTestResult(
          success = false,
          SbtTestCounts(total = 3, passed = 1, failures = 1, errors = 1, skipped = 0)
        )
      )
    )

    val report = ReportConverter.testReport(result)

    assertEquals(report.success, false)
    assertEquals(report.total, 3)
    assertEquals(report.passed, 1)
    assertEquals(report.failed, 2)
    assert(report.failures.nonEmpty)

  test("test failure uses zero counts when counts are unavailable"):
    val output = "[error] expected true but was false"
    val report = ReportConverter.testReport(SbtRunResult("test", 1, output, ""))

    assertEquals(report.success, false)
    assertEquals(report.total, 0)
    assertEquals(report.passed, 0)
    assertEquals(report.failed, 0)
    assertEquals(report.failures.nonEmpty, true)
