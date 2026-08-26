package semantic.harness.sbt_runner

import java.nio.file.Files

class SbtTestResultSourceSuite extends munit.FunSuite:
  test("structured sbt suite results aggregate passing failing error and skipped events"):
    val root = Files.createTempDirectory("sbt-test-results-")
    val completion = root.resolve("completion.protocol")
    try
      Files.writeString(
        completion,
        SbtTestResultSource.CompletionFormat +
          "\nsuccess\tfalse\ntotal\t6\npassed\t3\nfailures\t1\nerrors\t1\nskipped\t1\n"
      )

      val result = SbtTestResultSource.read(completion)

      assertEquals(
        result,
        Right(
          SbtStructuredTestResult(
            success = false,
            SbtTestCounts(total = 6, passed = 3, failures = 1, errors = 1, skipped = 1)
          )
        )
      )
    finally deleteRecursively(root)

  test("a completed run with no suite reports proves literal zero tests"):
    val root = Files.createTempDirectory("sbt-test-results-empty-")
    val completion = root.resolve("completion.protocol")
    try
      Files.writeString(
        completion,
        SbtTestResultSource.CompletionFormat +
          "\nsuccess\ttrue\ntotal\t0\npassed\t0\nfailures\t0\nerrors\t0\nskipped\t0\n"
      )

      assertEquals(
        SbtTestResultSource.read(completion),
        Right(SbtStructuredTestResult(success = true, SbtTestCounts.Zero))
      )
    finally deleteRecursively(root)

  test("malformed or internally inconsistent reports fail closed"):
    val root = Files.createTempDirectory("sbt-test-results-invalid-")
    val completion = root.resolve("completion.protocol")
    try
      Files.writeString(
        completion,
        SbtTestResultSource.CompletionFormat +
          "\nsuccess\ttrue\ntotal\t1\npassed\t0\nfailures\t1\nerrors\t1\nskipped\t0\n"
      )

      assert(SbtTestResultSource.read(completion).isLeft)
    finally deleteRecursively(root)

  test("injected test task aggregates sbt suite results and captures domain failure"):
    val settings = SbtTestResultSource.GlobalSettings

    assert(settings.contains("(Test / executeTests).value"), clue(settings))
    assert(settings.contains("testOutput.events.values"), clue(settings))
    assert(settings.contains("passedCount"), clue(settings))
    assert(settings.contains("ignoredCount"), clue(settings))
    assert(settings.contains("canceledCount"), clue(settings))
    assert(settings.contains("pendingCount"), clue(settings))
    assert(settings.contains("TestResult.Failed"), clue(settings))
    assert(settings.contains("TestResult.Error"), clue(settings))
    assert(settings.contains(SbtTestResultSource.CompletionFormat), clue(settings))
    assert(!settings.contains("JUnitXmlTestsListener"), clue(settings))
    assert(!settings.contains("Total\\s+"), clue(settings))

  private def deleteRecursively(root: java.nio.file.Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally paths.close()
