package semantic.harness.sbt_runner

final case class SbtScalaVersion private (value: String)

object SbtScalaVersion:
  private val Admitted =
    raw"^[0-9]+(\.[0-9]+){1,2}(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$$".r

  def parse(value: String): Either[String, SbtScalaVersion] =
    value match
      case Admitted(_*) => Right(SbtScalaVersion(value))
      case _ =>
        Left(
          "Scala version must match " +
            "[0-9]+(\\.[0-9]+){1,2}(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?"
        )
