package semantic.harness.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.charset.StandardCharsets

object Main:
  def main(args: Array[String]): Unit =
    cliPath(args.toList) match
      case Left(message) =>
        System.err.println(message)
        sys.exit(2)
      case Right(path) =>
        val server = SemanticScalaMcpServer(SemanticScalaCli(path))
        val writer =
          BoundedLineWriter(
            System.out,
            McpRuntimeConfig().maxResponseBytes
          )
        val runtime = McpStdioRuntime(server, writer)
        val reader =
          BufferedReader(InputStreamReader(System.in, StandardCharsets.UTF_8))
        val report = runtime.run(reader)
        if !report.clean then
          System.err.println("MCP shutdown did not complete cleanly")

  private def cliPath(args: List[String]): Either[String, Path] =
    args match
      case Nil =>
        Right(
          sys.env
            .get("SEMANTIC_SCALA_CLI")
            .map(Path.of(_))
            .getOrElse(SemanticScalaCli.DefaultCliPath)
        )
      case "--cli" :: path :: Nil =>
        Right(Path.of(path))
      case other =>
        Left(s"Invalid arguments for semantic-harness-mcp-server: ${other.mkString(" ")}")
