package semantic.harness.benchmark

class BenchmarkCaseLoaderSuite extends munit.FunSuite:
  test("loads benchmark case fixtures deterministically"):
    val cases = BenchmarkCaseLoader
      .loadResourceDirectory("benchmark-cases")
      .fold(message => fail(message), identity)

    assertEquals(
      cases.map(_.id),
      List(
        "ambiguous-symbol-1",
        "compile-error-1",
        "fp-effect-either-preservation-1",
        "fp-effect-generic-wrapper-1",
        "fp-effect-misleading-1",
        "no-symbol-1",
        "reconciliation-uncertainty-1",
        "semantic-disambiguation-1",
        "semantic-misleading-1",
        "semantic-required-1"
      )
    )
    assert(cases.forall(BenchmarkValidation.valid))

  test("validates required fields and modes"):
    val invalid = BenchmarkCase(
      id = "",
      title = "Invalid",
      description = "",
      mode = "unknown-mode",
      initialProject = "",
      successCommand = "",
      allowedCommands = Nil,
      expectedSignals = Nil
    )

    val errors = BenchmarkValidation.validate(invalid)
    assert(errors.exists(_.contains("id must not be empty")))
    assert(errors.exists(_.contains("description must not be empty")))
    assert(errors.exists(_.contains("mode must be one of")))
    assert(errors.exists(_.contains("allowedCommands must not be empty")))
