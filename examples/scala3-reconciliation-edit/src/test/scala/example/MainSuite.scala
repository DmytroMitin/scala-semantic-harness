package example

class MainSuite extends munit.FunSuite:
  test("result keeps the expected value"):
    assertEquals(Main.result, 43)
