package example

class MainSuite extends munit.FunSuite:
  test("userName preserves parsed user name"):
    assertEquals(Main.userName("Ada"), Right("Ada"))

  test("userName preserves parser failure channel"):
    assertEquals(Main.userName(""), Left("empty"))
