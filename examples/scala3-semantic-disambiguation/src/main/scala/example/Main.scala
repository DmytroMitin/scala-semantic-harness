package example

object Domain:
  final case class Id(value: String)
  final case class Id2(value: String)

object Main:
  import Domain.*

  def render(id: Id): String = id.value

  val id = Id2("x")
  val result = render(id)
