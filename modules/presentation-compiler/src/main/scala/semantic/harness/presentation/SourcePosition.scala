package semantic.harness.presentation

private[presentation] object SourcePosition:
  def offset(source: String, line: Int, column: Int): Either[String, Int] =
    val starts = lineStarts(source)
    val targetLine = line - 1
    val targetColumn = column - 1

    if targetLine >= starts.length then Left(s"Line $line is outside the source file")
    else
      val start = starts(targetLine)
      val end = lineContentEnd(source, starts, targetLine)
      val length = end - start
      if targetColumn > length then Left(s"Column $column is outside line $line")
      else Right(start + targetColumn)

  private def lineStarts(source: String): Vector[Int] =
    val builder = Vector.newBuilder[Int]
    builder += 0
    var index = 0
    while index < source.length do
      source.charAt(index) match
        case '\r' if index + 1 < source.length && source.charAt(index + 1) == '\n' =>
          index += 2
          builder += index
        case '\r' | '\n' =>
          index += 1
          builder += index
        case _ => index += 1
    builder.result()

  private def lineContentEnd(source: String, starts: Vector[Int], lineIndex: Int): Int =
    if lineIndex + 1 >= starts.length then source.length
    else
      val nextStart = starts(lineIndex + 1)
      if nextStart >= 2 && source.charAt(nextStart - 2) == '\r' && source.charAt(nextStart - 1) == '\n' then
        nextStart - 2
      else nextStart - 1
