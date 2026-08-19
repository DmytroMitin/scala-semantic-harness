package semantic.harness.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object Main:
  def main(args: Array[String]): Unit =
    CliPathResolver.resolve(args.toList) match
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
