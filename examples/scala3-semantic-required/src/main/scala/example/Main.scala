package example

object Helpers:
  def normalize(x: String): String = x.trim

object Main:
  import Helpers.*

  def process(x: String): String =
    normalizeValue(x)
