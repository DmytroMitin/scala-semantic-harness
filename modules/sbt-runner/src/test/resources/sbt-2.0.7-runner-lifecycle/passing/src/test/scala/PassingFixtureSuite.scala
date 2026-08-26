class PassingFixtureSuite extends munit.FunSuite:
  test("first passing test"):
    assertEquals(FixtureValue.answer, 42)

  test("second passing test"):
    assert(FixtureValue.answer > 0)

  test("one ignored test".ignore):
    fail("ignored test must not execute")
