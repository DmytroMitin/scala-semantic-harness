# Portable benchmark inputs

This directory contains only the client-neutral inputs admitted to the public
benchmark subset.

The files under [`prompts/`](prompts/) describe compile/test-only,
semantic-scala, effect-summary, and reconciliation conditions. They use the
`SEMANTIC_SCALA_CMD` environment variable instead of a checkout-specific
launcher path. The [`semantic-scala-packaged`](semantic-scala-packaged) wrapper
resolves the staged CLI relative to this repository:

```bash
sbt cli/stage
export SEMANTIC_SCALA_CMD="$PWD/scripts/benchmark/semantic-scala-packaged"
"$SEMANTIC_SCALA_CMD" version
```

The prompts are protocol inputs, not an automated agent runner. Public
reproduction validates the prompt bytes, their referenced repository fixtures,
the packaged CLI, and the compile/test oracle without launching a model client
or recording a transcript. See [`../../benchmarks/README.md`](../../benchmarks/README.md)
for the evidence manifest, aggregate results, method, and limitations.

Client-specific orchestration, environment capture, and transcript collection
are outside this public subset. Nothing here requires credentials, hidden
state, an absolute user path, or a specific client executable.
