package example

class CalculatorSuite extends munit.FunSuite:
  test("add"):
    assertEquals(Calculator.add(1, 1), 3)
