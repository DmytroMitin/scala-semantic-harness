package typeclassfixture

trait Decode[A]:
  def apply(value: String): A

object MissingInstance:
  val result: Decode[Int] = summon[Decode[Int]]
