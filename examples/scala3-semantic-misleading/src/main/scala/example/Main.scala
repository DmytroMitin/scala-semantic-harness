package example

object A:
  def convert(x: Int): String = x.toString

object B:
  def convert(x: Int): Int = x + 1

object Main:
  import A.*

  val result: Int = convert(1)
