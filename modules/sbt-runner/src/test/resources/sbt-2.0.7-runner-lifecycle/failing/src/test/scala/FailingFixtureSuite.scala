class FailingFixtureSuite extends munit.FunSuite:
  test("one deterministic failure"):
    assertEquals(1, 2)
