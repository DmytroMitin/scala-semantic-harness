package semantic.harness.cli

object Main:
  def main(args: Array[String]): Unit =
    val result = CliApp.run(args.toList)

    result.stdout.foreach(println)
    result.stderr.foreach(Console.err.println)

    if result.exitCode != 0 then sys.exit(result.exitCode)
