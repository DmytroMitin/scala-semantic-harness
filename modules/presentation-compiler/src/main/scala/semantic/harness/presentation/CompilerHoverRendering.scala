package semantic.harness.presentation

private[presentation] object CompilerHoverRendering:
  private val ExpressionLabel = "**Expression type**:\n"
  private val ScalaFence = "```scala\n"
  private val FenceEnd = "\n```"

  final case class Extracted(value: String, kind: InferTypeRenderingKind)

  def select(rawMarkup: Option[String], symbolSignature: Option[String]): Option[Extracted] =
    rawMarkup.flatMap(expressionType).map(Extracted(_, InferTypeRenderingKind.ExpressionType))
      .orElse(symbolSignature.filter(_.nonEmpty).map(Extracted(_, InferTypeRenderingKind.SymbolSignature)))
      .orElse(rawMarkup.flatMap(leadingScalaCode).map(Extracted(_, InferTypeRenderingKind.HoverCode)))

  private def expressionType(markup: String): Option[String] =
    if !markup.startsWith(ExpressionLabel + ScalaFence) then None
    else fencedValue(markup, ExpressionLabel.length)

  private def leadingScalaCode(markup: String): Option[String] =
    if !markup.startsWith(ScalaFence) then None
    else fencedValue(markup, 0)

  private def fencedValue(markup: String, prefixLength: Int): Option[String] =
    val valueStart = prefixLength + ScalaFence.length
    val valueEnd = markup.indexOf(FenceEnd, valueStart)
    Option.when(valueEnd >= valueStart)(markup.substring(valueStart, valueEnd)).filter(_.nonEmpty)
