package typeclassfixture

trait Label[A]:
  def apply(value: A): String

object AmbiguousInstance:
  given first: Label[Int] with
    def apply(value: Int): String = "first"

  given second: Label[Int] with
    def apply(value: Int): String = "second"

  val result: String = summon[Label[Int]].apply(1)
