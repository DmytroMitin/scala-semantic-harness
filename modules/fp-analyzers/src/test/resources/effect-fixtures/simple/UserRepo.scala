package example

trait UserRepo[F[_]]:
  def find(id: UserId): F[Option[User]]
  def save(user: User): F[Unit]
  def maybe(id: UserId): Option[User]
  def parse(raw: String): Either[String, User]
  def cached(id: UserId): Future[User]
  def flush: IO[Unit]
  def load(id: UserId): ZIO[Any, Throwable, User]
  def task(id: UserId): Task[User]
  def unit: UIO[Unit]
  def name(user: User): String
  def inferred = 42

final case class UserId(value: String)
final case class User(name: String)
