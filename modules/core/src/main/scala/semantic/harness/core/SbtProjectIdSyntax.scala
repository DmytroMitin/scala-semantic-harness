package semantic.harness.core

object SbtProjectIdSyntax:
  val Pattern: String = "^[A-Za-z][A-Za-z0-9_-]*$"
  val ErrorMessage: String =
    "sbt project ID must start with a letter and contain only letters, digits, '-' or '_'"

  private val SafeProjectId = Pattern.r

  def validate(value: String): Either[String, String] =
    value match
      case SafeProjectId() => Right(value)
      case _               => Left(ErrorMessage)
