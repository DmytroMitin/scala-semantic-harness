package example

final case class User(name: String)

object Parser:
  def parseOption(raw: String): Option[User] =
    Option.when(raw.nonEmpty)(User(raw))

  def parseEither(raw: String): Either[String, User] =
    if raw.nonEmpty then Right(User(raw)) else Left("empty")

object Main:
  def userName(raw: String): Either[String, String] =
    Parser.parseOption(raw).map(_.name)
