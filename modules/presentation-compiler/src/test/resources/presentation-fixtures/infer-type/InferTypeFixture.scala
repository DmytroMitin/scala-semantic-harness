package example

object InferTypeFixture:

  // no type comment marker
  val explicit: Int = 42
  val inferred = List(1, 2, 3)
  val optional = Option("value")

  def add(x: Int, y: Int): Int = x + y

  val callResult = add(explicit, 1)
  val mapped = optional.map(_.length)
  val function = (x: Int) => x.toString
  val unicodeBefore = "😀"; val unicodeOptional = optional
  val λ = optional
