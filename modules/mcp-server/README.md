# mcp-server

Minimal MCP stdio server for `semantic-scala`.

## Current Status

This module currently exposes exactly eight MCP tools:

```text
semantic_compile
semantic_errors
semantic_test
semantic_effect_summary
semantic_symbol_at
semantic_symbols
semantic_reconcile_symbol
semantic_point_evidence
```

The server uses a small in-repo JSON-RPC stdio transport rather than an external
MCP library. It supports initialization, `tools/list`, and `tools/call` for the
eight tools. It does not expose the rest of the CLI command suite.

## Setup

Build the packaged CLI before using the default local path:

```bash
sbt cli/stage
```

Default local CLI path:

```text
modules/cli/target/stage/bin/semantic-scala
```

Run the server during local development:

```bash
sbt --error "mcpServer/run"
```

For MCP clients, prefer the packaged launcher so sbt does not sit between the
client and the server stdio stream:

```bash
sbt mcpServer/stage
modules/mcp-server/target/stage/bin/semantic-scala-mcp
```

Configure the packaged CLI path with either:

```bash
SEMANTIC_SCALA_CLI=/path/to/semantic-scala modules/mcp-server/target/stage/bin/semantic-scala-mcp
```

or:

```bash
modules/mcp-server/target/stage/bin/semantic-scala-mcp --cli /path/to/semantic-scala
```

The server reads newline-delimited JSON-RPC messages from stdin and writes only
JSON-RPC messages to stdout. It may write startup argument errors to stderr
before the transport starts.

The input reader only parses, classifies, handles notifications, and admits
requests. Up to eight admitted requests execute on a fixed worker pool. The
three build-oracle tools share one fair build permit; other tools may complete
out of order. Output is serialized as complete UTF-8 JSON lines.

Claude Code project-scoped config for this checkout is recorded in the root
`.mcp.json`. External-client validation notes live in
`docs/mcp-client-validation.md`, including real `semantic_compile` calls from
Claude Code and Codex plus Codex calls for `semantic_symbols` and
`semantic_reconcile_symbol`, and `semantic_point_evidence`.

## semantic_compile

Tool input:

```json
{
  "workspace": "/path/to/project"
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- calls `SemanticScalaCli.semanticCompile`;
- validates that `workspace` exists and is a directory in the adapter;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly `semantic-scala compile --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.compile-result.v1`.

## semantic_errors

Tool input:

```json
{
  "workspace": "/path/to/project"
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- calls `SemanticScalaCli.semanticErrors`;
- validates that `workspace` exists and is a directory in the adapter;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly `semantic-scala errors --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.errors-result.v1`.

## semantic_test

Tool input:

```json
{
  "workspace": "/path/to/project"
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- calls `SemanticScalaCli.semanticTest`;
- validates that `workspace` exists and is a directory in the adapter;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly `semantic-scala test --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.test-result.v1`.

## semantic_effect_summary

Tool input:

```json
{
  "workspace": "/path/to/project",
  "file": "src/main/scala/example/UserRepo.scala"
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- validates that `file` is present as a non-empty string at the transport
  layer;
- calls `SemanticScalaCli.semanticEffectSummary`;
- validates that `workspace` exists and is a directory in the adapter;
- requires `file` to be a relative `.scala` path;
- normalizes `workspace.resolve(file)` and rejects paths that escape the
  workspace;
- requires the resolved file to exist and be a regular file;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly `semantic-scala effect-summary --file <file> --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.effect-summary.v1`.

## semantic_symbol_at

Tool input:

```json
{
  "workspace": "/path/to/project",
  "file": "src/main/scala/example/Main.scala",
  "line": 6,
  "col": 16
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- validates that `file` is present as a non-empty string at the transport
  layer;
- validates that `line` and `col` are present as positive JSON integers at the
  transport layer;
- calls `SemanticScalaCli.semanticSymbolAt`;
- validates that `workspace` exists and is a directory in the adapter;
- requires `file` to be a relative `.scala` path;
- normalizes `workspace.resolve(file)` and rejects paths that escape the
  workspace;
- requires the resolved file to exist and be a regular file;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly
  `semantic-scala symbol-at --file <file> --line <line> --col <col> --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.symbol-at-result.v1`.

## semantic_symbols

Tool input:

```json
{
  "workspace": "/path/to/project",
  "semanticdb": "target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb"
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- validates that `semanticdb` is present as a non-empty string at the transport
  layer;
- calls `SemanticScalaCli.semanticSymbols`;
- validates that `workspace` exists and is a directory in the adapter;
- requires `semanticdb` to be a relative `.semanticdb` path;
- normalizes `workspace.resolve(semanticdb)` and rejects paths that escape the
  workspace;
- requires the resolved SemanticDB file to exist and be a regular file;
- passes the original relative `semanticdb` argument to the CLI after
  validation;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly `semantic-scala symbols --semanticdb <semanticdb> --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.symbols-result.v1`.

## semantic_reconcile_symbol

Tool input:

```json
{
  "workspace": "/path/to/project",
  "file": "src/main/scala/example/Main.scala",
  "line": 6,
  "col": 16,
  "semanticdb": "target/scala-3.3.3/classes/META-INF/semanticdb/src/main/scala/example/Main.scala.semanticdb"
}
```

Behavior:

- validates that `workspace` is present as a string at the transport layer;
- validates that `file` is present as a non-empty string at the transport
  layer;
- validates that `line` and `col` are present as positive JSON integers at the
  transport layer;
- validates that `semanticdb` is present as a non-empty string at the transport
  layer;
- calls `SemanticScalaCli.semanticReconcileSymbol`;
- validates that `workspace` exists and is a directory in the adapter;
- requires `file` to be a relative `.scala` path;
- requires `semanticdb` to be a relative `.semanticdb` path;
- normalizes `workspace.resolve(file)` and `workspace.resolve(semanticdb)` and
  rejects paths that escape the workspace;
- requires the resolved source and SemanticDB paths to exist and be regular
  files;
- passes the original relative `file` and `semanticdb` arguments to the CLI
  after validation;
- runs the configured CLI as argv, not through a shell;
- uses `workspace` as the process working directory;
- runs exactly
  `semantic-scala reconcile-symbol --file <file> --line <line> --col <col> --semanticdb <semanticdb> --json`;
- captures stdout, stderr, and exit code;
- parses stdout as a JSON object when the process exits `0`;
- requires `schemaVersion = semantic-scala.reconcile-symbol-result.v1`.

## semantic_point_evidence

Tool input:

```json
{
  "workspace": "/path/to/project",
  "file": "src/main/scala/example/Main.scala",
  "line": 6,
  "col": 16
}
```

The adapter requires an existing workspace, a relative contained regular
`.scala` file, and positive one-based UTF-16 coordinates. It runs exactly
`semantic-scala point-evidence --workspace . --file <file> --line <line> --col
<col> --json` in that workspace and accepts only
`semantic-scala.point-evidence-result.v1`.

## MCP Methods

Supported request methods:

- `initialize`
- `ping`
- `tools/list`
- `tools/call`

Supported notification:

- `notifications/initialized`
- `notifications/cancelled`

There is no `Mcp-Session-Id`, client-info store, connection-scoped workspace,
project, cache mode, or approval state. Initialize does not gate tool listing
or calls. All notifications are fire-and-forget and never receive a response.
Cancellation uses `params.requestId` with a string or number ID. Unknown,
malformed, duplicate, and late cancellations are ignored. If cancellation wins
the terminal race, owned work is cleaned and no response for that ID is sent.

`tools/list` returns `semantic_compile`, `semantic_errors`, `semantic_test`,
`semantic_effect_summary`, `semantic_symbol_at`, `semantic_symbols`, and
`semantic_reconcile_symbol`.
The build-oracle tools require `workspace`; `semantic_effect_summary` requires
`workspace` and `file`; `semantic_symbol_at` requires `workspace`, `file`,
`line`, and `col`; `semantic_symbols` requires `workspace` and `semanticdb`;
`semantic_reconcile_symbol` requires `workspace`, `file`, `line`, `col`, and
`semanticdb`; `semantic_point_evidence` requires `workspace`, `file`, `line`,
and `col`. `tools/call` accepts only those eight tools.

## Response Wrapper

The MCP `tools/call` result includes both:

- `structuredContent`: the JSON wrapper result;
- `content`: a text block containing the same wrapper JSON string for client
  compatibility.

Successful-domain wrapper shape:

```json
{
  "ok": true,
  "command": ["semantic-scala", "errors", "--json"],
  "workspace": "/path/to/project",
  "exitCode": 0,
  "schemaVersion": "semantic-scala.errors-result.v1",
  "payload": {
    "schemaVersion": "semantic-scala.errors-result.v1",
    "success": false,
    "diagnostics": []
  },
  "stderr": "",
  "error": null
}
```

`payload` is the parsed CLI JSON object. MCP wrapper metadata is not inserted
into the payload. A compile, errors, or test report with `"success": false` is
still `ok: true` when the CLI exits `0` and emits the expected schema.

Non-zero CLI/runtime exits return wrapper `ok: false`, preserve a known exit
code, and do not invent a payload. Failure wrappers replace `command` with the
logical executable/operation, replace `workspace` with `"<workspace>"`, clear
`stderr`, and return a stable bounded error category. They do not expose
absolute machine paths, full argv, raw process output, environment values, or
exception text. In MCP `tools/call`, wrapper `ok: false` is returned with
`isError: true`.

Malformed JSON, missing `schemaVersion`, or a schema mismatch are adapter
failures with wrapper `ok: false`.

## Protocol and Conformance Readiness

This module has no MCP SDK dependency. It directly implements MCP
`2025-06-18` using Circe and newline-delimited stdio. The tool registry is
deterministic across initialize/restart, and controlled concurrent core calls
keep separate IDs, workspaces, payloads, and stderr.

The fixed asynchronous request runtime provides:

- eight active entries and eight workers;
- canonical string/numeric IDs;
- duplicate code `-32001` and capacity code `-32000`;
- response-overflow code `-32002`;
- a ten-minute monotonic request deadline;
- one cancellation-aware fair permit for compile/errors/test;
- owned direct-child/observed-descendant termination and reap;
- 32 MiB stdout, 4 MiB stderr, and 36 MiB aggregate capture limits;
- strict UTF-8 decoding;
- a 68 MiB encoded response-line limit;
- synchronized complete-line output;
- bounded EOF and writer-failure shutdown.

Cancellation before the build permit or subprocess prevents process launch.
Cancellation after launch stops owned work. A cancellation-winning request has
no response. Numerically equal IDs such as `1` and `1.0` are the same active
ID, while `"1"` is distinct. An active duplicate receives a deterministic
error without replacing the original entry; the ID is reusable after cleanup.

The final MCP `2026-07-28` revision was assessed at
`5f5440bb26a62e2cf3440b92da5a667efa03b267`. Its lifecycle removes
initialize, carries version/client capabilities on each request, requires
`server/discover`, and uses final result/cache/subscription contracts. This
module does not implement or advertise that revision.

Stable Go/TypeScript final-era SDKs exist, but released official conformance
`v0.1.16` has no final-revision coverage and official Java SDK `v2.0.0`
targets `2025-11-25`. Exact Codex/Claude final negotiation remains
undocumented. Final-revision support is therefore deferred behind explicit
released-conformance and client gates rather than adding a final-only or
dual-era implementation now. No dispatcher or era selector exists; the
production server remains `2025-06-18`.

`scripts/mcp/stdio-http-conformance-adapter.py` is a loopback-only test bridge
for the official runner's HTTP-only server mode. It is not a production
transport and makes no authentication claim. At pinned conformance revision
`a865118206d4d8cc8dbc5f5201607839281d0c3b`, initialize, ping, and tool-list
produced four asserted current-revision passes. The draft stateless scenario
produced 25 failures and does not establish support.

The remaining lifecycle limitation is OS-level rather than hidden: portable
JVM `ProcessHandle` cleanup covers the direct child and descendants observed by
the JVM, but does not claim to contain a process that deliberately detaches and
reparents before observation. Real external sbt concurrency is also not
claimed; this server deliberately starts only one build-capable tool process at
a time.

## Reconciliation Status Semantics

`semantic_reconcile_symbol` returns reconciliation status inside the CLI
payload. These statuses are domain evidence, not MCP transport status.

- `ExactMatch`: strong evidence that Presentation Compiler and SemanticDB
  symbol strings agreed.
- `RangeMatchOnly`: weaker evidence where range or one side of the evidence
  matched without exact symbol identity.
- `SymbolMismatch`: conflicting symbol evidence.
- `NoMatch`: no useful reconciliation match.

When the CLI exits `0` and emits
`semantic-scala.reconcile-symbol-result.v1`, all of these statuses return
wrapper `ok: true` and MCP `isError: false`. CLI/runtime/schema failures return
wrapper `ok: false` and MCP `isError: true`.

## Safety Notes

`semantic_compile`, `semantic_errors`, and `semantic_test` are not read-only
queries. They run the target project's build/test through the CLI and may
execute build code or write build outputs under the workspace.

`semantic_effect_summary` is file-scoped and syntax-first, but it still invokes
the packaged CLI process in the requested workspace. It accepts only a relative
Scala source path inside that workspace and does not provide arbitrary CLI
arguments.

`semantic_symbol_at` is a file-scoped Presentation Compiler point query exposed
through the CLI. It is semantic evidence, not a final build/test oracle.

`semantic_symbols` reads one explicit SemanticDB file through the CLI and
returns file-scoped symbols and occurrences evidence. It does not generate
SemanticDB files, discover SemanticDB directories, or replace compiler/LSP
tooling.

`semantic_reconcile_symbol` combines Presentation Compiler point-query evidence
with SemanticDB occurrence evidence through the CLI. It complements compiler,
Metals/LSP, SemanticDB, and Presentation Compiler tooling; it does not replace
them.

`semantic_point_evidence` delegates to the CLI-owned composition. It discovers
existing artifacts, selects only one unique parsed match, preserves live
resolved/unresolved/unavailable evidence, and either reconciles or reports a
typed not-attempted reason. It does not run a build, generate SemanticDB, or
assess freshness.

The server does not add arbitrary extra args, generic command execution,
caching, broad workspace-root policy, direct module calls, or MCP tools beyond
`semantic_compile`, `semantic_errors`, `semantic_test`,
`semantic_effect_summary`, `semantic_symbol_at`, `semantic_symbols`,
`semantic_reconcile_symbol`, and `semantic_point_evidence`. It does not add
SemanticDB generation.

## Smoke Validation

Validate the staged server against real example workspaces:

```bash
sbt cli/stage
sbt mcpServer/stage
scripts/mcp/smoke-mcp-tools.py
```

The smoke script launches `semantic-scala-mcp` as a subprocess, sends
newline-delimited JSON-RPC messages over stdin, reads JSON-RPC responses from
stdout, and checks:

- `initialize`;
- `notifications/initialized`;
- `tools/list`;
- `semantic_compile` on `examples/scala3-compile-success`;
- `semantic_compile` on `examples/scala3-compile-failure`;
- `semantic_errors` on `examples/scala3-compile-success`;
- `semantic_errors` on `examples/scala3-compile-failure`;
- `semantic_test` on `examples/scala3-compile-success`;
- `semantic_test` on `examples/scala3-test-failure`;
- `semantic_effect_summary` on
  `modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala`;
- `semantic_symbol_at` on
  `modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala`;
- `semantic_symbols` on
  `modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb`;
- `semantic_reconcile_symbol` on the presentation compiler fixture paired with
  `modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb`;
- `semantic_point_evidence` on the presentation compiler fixture;
- invalid workspace;
- missing/non-string `workspace`;
- missing/non-string `file`;
- missing/non-string `semanticdb`;
- missing/non-integer/non-positive `line` and `col`;
- invalid effect-summary and symbol-at file paths;
- invalid symbols `.semanticdb` paths;
- invalid reconcile source and SemanticDB file paths;
- invalid symbol-at and reconcile positions;
- unknown tool.

The script accepts optional overrides:

```bash
SEMANTIC_SCALA_MCP=/path/to/semantic-scala-mcp \
SEMANTIC_SCALA_CLI=/path/to/semantic-scala \
scripts/mcp/smoke-mcp-tools.py
```

The compile, errors, and test calls run sbt in the example workspaces through
the packaged CLI, so those smoke checks are not read-only.
