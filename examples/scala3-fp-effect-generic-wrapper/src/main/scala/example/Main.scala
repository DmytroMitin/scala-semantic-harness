package example

trait MapK[F[_]]:
  extension [A](fa: F[A]) def mapK[B](f: A => B): F[B]

final case class Box[A](value: A)

object Box:
  given MapK[Box] with
    extension [A](fa: Box[A]) def mapK[B](f: A => B): Box[B] =
      Box(f(fa.value))

final case class UserId(value: String)
final case class User(name: String)

trait UserRepo[F[_]]:
  def find(id: UserId): F[Option[User]]

final class BoxUserRepo extends UserRepo[Box]:
  def find(id: UserId): Box[Option[User]] =
    if id.value.nonEmpty then Box(Some(User(id.value))) else Box(None)

object BoxUserRepo:
  def apply(): BoxUserRepo = new BoxUserRepo

object Main:
  def getName[F[_]](repo: UserRepo[F], id: UserId): Option[String] =
    repo.find(id).map(_.name)
