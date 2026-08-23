# MCP client configuration and validation

The packaged stdio server delegates to the packaged `semantic-scala` CLI and
exposes exactly eight tools.

For installed `0.1.0-alpha.2`, configure the command
`semantic-scala-mcp` directly. Copy-ready Codex, Claude Code, Cursor, and VS
Code/Copilot recipes, exact MCP and skill qualification statuses, and the
CLI/MCP surface classification are in
[`agent-onboarding.md`](agent-onboarding.md).

The current local matrix is bounded: Codex CLI `0.149.0` is
`QUALIFIED_LOCAL`; Codex Desktop, Claude Code, and Cursor are
`OFFICIAL_RECIPE_NOT_LOCALLY_QUALIFIED`; VS Code/Copilot is
`CLIENT_NOT_AVAILABLE`. Claude's official recipe reached client initialization
but an expired login blocked tool discovery and invocation. These statuses do
not weaken the independent installed-server protocol smoke.

## Source-development route

The baseline for a source checkout is JDK 21, sbt, Git, and Python 3. This
walkthrough is for developing the server rather than ordinary alpha-2 use.

### Build the launchers

```bash
sbt cli/stage
sbt mcpServer/stage
```

The resulting repository-relative launchers are:

```text
modules/cli/target/stage/bin/semantic-scala
modules/mcp-server/target/stage/bin/semantic-scala-mcp
```

Start the server with:

```bash
modules/mcp-server/target/stage/bin/semantic-scala-mcp \
  --cli modules/cli/target/stage/bin/semantic-scala
```

For project-scoped client configuration, copy [`.mcp.example.json`](../.mcp.example.json)
and replace `/path/to/scala-semantic-harness` with the absolute path to your
checkout. Client approval and trust behavior varies; review the command before
approving build or test execution.

### Bounded Claude Code first use

Claude Code 2.1.220 provides a session-only `--mcp-config` route. To avoid a
persisted project-server approval while evaluating the source alpha, copy
`.mcp.example.json` to a temporary path outside the checkout, replace both
placeholder checkout paths with the absolute result of `pwd -P`, and run:

```bash
claude -p \
  "List the semantic-scala MCP tools, then call only semantic_effect_summary with workspace . and file examples/scala3-fp-effect-generic-wrapper/src/main/scala/example/Main.scala." \
  --mcp-config /tmp/semantic-scala.mcp.json \
  --strict-mcp-config \
  --setting-sources project \
  --no-session-persistence \
  --permission-mode dontAsk \
  --allowedTools mcp__semantic-scala__semantic_effect_summary
```

The repository-local Claude wrapper makes the `semantic-scala` skill
discoverable. It is a thin source-tree wrapper, not a standalone external
skill. Skill discovery is not proof of autonomous selection or effectiveness.
When client authentication is usable, the expected call has adapter `ok: true`, schema
`semantic-scala.effect-summary.v1`, and syntax-first method summaries. It does
not compile the fixture or prove inferred effect semantics.

Delete `/tmp/semantic-scala.mcp.json` after the session. For ordinary
project-scoped use instead, place the edited copy at `.mcp.json`; Claude Code
will show that shared project server as pending until the user explicitly
approves it. Do not treat MCP discovery as approval to run the build-executing
compile, errors, or test tools.

## Expected registry

`tools/list` must return exactly these tools in this order:

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

The server does not expose a generic command runner, arbitrary extra CLI
arguments, SemanticDB generation, direct module APIs, or CLI-only type and
artifact-discovery commands.

## Protocol smoke test

Run the deterministic smoke entry point after staging:

```bash
scripts/mcp/smoke-mcp-tools.py
```

It checks initialization, exact registry order, successful and failing build
domains, file-based semantic tools, invalid input, and unknown-tool behavior.
The normal Scala test suite separately asserts the exact eight-tool registry.

## Result interpretation

Keep three layers separate:

1. transport and process execution;
2. adapter parsing and schema validation; and
3. the domain result.

A compile or test payload with `success: false` is valid domain evidence when
the adapter invocation succeeds. Likewise `RangeMatchOnly`, `SymbolMismatch`,
and `NoMatch` are successful reconciliation outcomes, not MCP transport errors.
Only `ExactMatch` reports identical dynamic and static symbol strings.

## Safety and compatibility

- `semantic_compile`, `semantic_errors`, and `semantic_test` may execute sbt,
  project build logic, plugins, and tests in the selected workspace.
- File arguments must be relative to and contained by the validated workspace.
- Use the staged server rather than `sbt mcpServer/run` so stdout remains
  reserved for newline-delimited JSON-RPC.
- The server implements the documented production protocol boundary; do not
  infer support for later protocol revisions from client connectivity alone.
- Wrapper failures redact absolute paths, complete argument vectors, raw
  stderr, environment values, and exception text.
