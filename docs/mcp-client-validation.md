# MCP client configuration and validation

The packaged stdio server delegates to the packaged `semantic-scala` CLI and
exposes exactly eight tools.

## Build the launchers

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
