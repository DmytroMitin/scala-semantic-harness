package example

object Domain:
  final case class UserId(value: String)
  final case class OrderId(value: String)

object Main:
  import Domain.*

  def renderUser(id: UserId): String = id.value
  def renderOrder(id: OrderId): String = id.value

  val id: OrderId = OrderId("o-1")
  val result: String = renderUser(id)
