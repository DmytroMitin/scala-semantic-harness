package semantic.harness.benchmark

object BenchmarkValidation:
  val ValidModes: Set[String] =
    Set("compile-test-only", "semantic-harness", "effect-summary-harness")

  def validate(value: BenchmarkCase): List[String] =
    List(
      required("id", value.id),
      required("title", value.title),
      required("description", value.description),
      required("mode", value.mode),
      required("initialProject", value.initialProject),
      required("successCommand", value.successCommand),
      Option.when(value.allowedCommands.isEmpty)("allowedCommands must not be empty"),
      Option.when(!ValidModes.contains(value.mode))(s"mode must be one of: ${ValidModes.toList.sorted.mkString(", ")}")
    ).flatten

  def valid(value: BenchmarkCase): Boolean =
    validate(value).isEmpty

  private def required(field: String, value: String): Option[String] =
    Option.when(value.trim.isEmpty)(s"$field must not be empty")
