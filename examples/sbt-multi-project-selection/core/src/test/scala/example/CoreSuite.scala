package example

class CoreSuite extends munit.FunSuite:
  test("bounded selected tests pass"):
    assertEquals(Core.answer, 42)
