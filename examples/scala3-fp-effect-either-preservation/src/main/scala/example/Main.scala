package example

final case class User(name: String)

object Parser:
  def parse(raw: String): Either[String, User] =
    if raw.nonEmpty then Right(User(raw)) else Left("empty")

object Main:
  def userName(raw: String): String =
    Parser.parse(raw).name
