package semantic.harness.mcp

import io.circe.Json
import io.circe.Encoder

final case class McpToolResult(
  ok: Boolean,
  command: List[String],
  workspace: String,
  exitCode: Option[Int],
  schemaVersion: Option[String],
  payload: Option[Json],
  stderr: String,
  error: Option[String]
)

object McpToolResult:
  given Encoder[McpToolResult] =
    Encoder.forProduct8(
      "ok",
      "command",
      "workspace",
      "exitCode",
      "schemaVersion",
      "payload",
      "stderr",
      "error"
    )(result =>
      (
        result.ok,
        result.command,
        result.workspace,
        result.exitCode,
        result.schemaVersion,
        result.payload,
        result.stderr,
        result.error
      )
    )
