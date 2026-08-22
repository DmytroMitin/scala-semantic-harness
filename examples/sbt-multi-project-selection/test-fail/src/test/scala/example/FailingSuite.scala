package example

class FailingSuite extends munit.FunSuite:
  test("one selected test passes"):
    assertEquals(TestSubject.value, 1)

  test("one selected test fails"):
    assertEquals(TestSubject.value, 2)
