package example

class MainSuite extends munit.FunSuite:
  test("getName preserves outer Box and inner Some"):
    assertEquals(Main.getName(BoxUserRepo(), UserId("Ada")), Box(Some("Ada")))

  test("getName preserves outer Box and inner None"):
    assertEquals(Main.getName(BoxUserRepo(), UserId("")), Box(None))
