package typeclassfixture

trait Show[A]:
  def show(value: A): String

object Show:
  given intShow: Show[Int] with
    def show(value: Int): String = value.toString

  def render[A: Show](value: A): String =
    summon[Show[A]].show(value)

object syntax:
  extension [A](value: A)(using instance: Show[A])
    def rendered: String = instance.show(value)

trait Functor[F[_]]:
  def map[A, B](value: F[A])(f: A => B): F[B]

object Functor:
  given optionFunctor: Functor[Option] with
    def map[A, B](value: Option[A])(f: A => B): Option[B] = value.map(f)

object Imported:
  given importedShow: Show[String] with
    def show(value: String): String = value

trait Legacy[A]

object Legacy:
  implicit val legacyInt: Legacy[Int] = new Legacy[Int] {}

  def requireLegacy[A](value: A)(implicit instance: Legacy[A]): A = value

object Uses:
  import Show.given
  import Functor.given
  import Imported.given
  import Legacy.*
  import syntax.*

  val renderedInt: String = 1.rendered
  val summoned: Show[Int] = summon[Show[Int]]
  val mapped: Option[Int] = summon[Functor[Option]].map(Option(1))(_ + 1)
  val imported: String = "outer".rendered
  val legacy: Int = requireLegacy(1)

  def locallyShadowed: String =
    given localShow: Show[String] with
      def show(value: String): String = value.reverse
    "local".rendered

object EffectOverlap:
  def load[F[_]](using Functor[F]): F[Int] = ???

object InferTypeOverlap:
  val inferredExpression = Option(1).map(_ + 1)

object SourceOnly:
  val plain: Int = 1 + 1
