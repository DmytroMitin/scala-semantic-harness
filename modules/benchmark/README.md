# benchmark

Benchmark harness v0 for `scala-semantic-harness`.

This module currently provides:

- `BenchmarkCase`
- `BenchmarkRun`
- Circe JSON codecs
- fixture loading from benchmark case JSON files
- basic descriptor validation
- deterministic test fixtures under `src/test/resources/benchmark-cases/`

The benchmark harness is an evaluation layer, not a new semantic feature. It is
intended to measure whether the existing CLI, SemanticDB, presentation compiler,
and reconciliation commands help agents repair small Scala tasks.

`BenchmarkRun` records both broad command history and semantic-specific
metadata. `semanticCommandsUsed` lists semantic commands actually used in a run,
currently `symbol-at`, `symbols`, or `reconcile-symbol`. `semanticAssessment` is
a JSON-friendly human interpretation such as `helpful`, `misleading`,
`irrelevant`, `mixed`, or `uncertain`. Detailed reasoning remains in `notes`.

## Current fixtures

```text
src/test/resources/benchmark-cases/compile-error-1.json
src/test/resources/benchmark-cases/ambiguous-symbol-1.json
src/test/resources/benchmark-cases/no-symbol-1.json
src/test/resources/benchmark-cases/reconciliation-uncertainty-1.json
src/test/resources/benchmark-cases/semantic-disambiguation-1.json
src/test/resources/benchmark-cases/semantic-misleading-1.json
src/test/resources/benchmark-cases/semantic-required-1.json
```

These fixtures describe manual or agent-run benchmark cases. They do not run an
agent, call an LLM API, or perform orchestration.

## Limits

- No CLI command yet.
- No LLM API integration.
- No multi-agent orchestration.
- No performance benchmarking.
- No FP analyzer, Metals/MCP, TASTy, graph, or vector functionality.
- Compile/test results remain the final oracle.
