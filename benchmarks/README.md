# Public benchmark evidence

This directory is the public entry point for the repository's bounded benchmark
evidence. It contains a deliberately small reproducible subset, not the full
history of agent sessions used while developing the harness.

Compiler and test results are the final correctness oracle. Semantic output is
decision-support evidence whose scope, freshness, completeness, and uncertainty
must remain visible.

## Public subset

- [`results/v0-screening-summary.json`](results/v0-screening-summary.json) is a
  bounded aggregate of the early v0 screening inventory.
- [`public-evidence-manifest.tsv`](public-evidence-manifest.tsv) freezes the
  size and SHA-256 digest of each admitted public evidence file.
- [`validate-public-evidence.py`](validate-public-evidence.py) validates that
  manifest and rejects user-specific or private-control references.
- [`../scripts/benchmark/prompts/`](../scripts/benchmark/prompts/) contains the
  portable condition prompts.
- [`../scripts/benchmark/semantic-scala-packaged`](../scripts/benchmark/semantic-scala-packaged)
  resolves the staged CLI without a user-specific path.
- The test-coupled JSON schemas, grammar cases, and SemanticDB fixture remain at
  their historical product paths because repository tests load those exact
  files. They are listed explicitly in the public evidence manifest.

The executable case descriptors live under
[`../modules/benchmark/src/test/resources/benchmark-cases/`](../modules/benchmark/src/test/resources/benchmark-cases/),
and their small Scala workspaces live under [`../examples/`](../examples/).
Benchmark methodology is documented in
[`../docs/benchmark-experiment-protocol.md`](../docs/benchmark-experiment-protocol.md),
[`../docs/benchmark-design.md`](../docs/benchmark-design.md), and
[`../docs/semantic-benchmark-taxonomy.md`](../docs/semantic-benchmark-taxonomy.md).

## Conditions and interpretation

The retained prompts cover compile/test-only baselines, semantic-scala symbol
queries, effect-summary cases, and reconciliation uncertainty. A valid case
must start from its checked-in fixture, obey its allowed-command policy, produce
a bounded result, and finish with the case's compile/test oracle.

The aggregate records 52 early screening entries: 46 usable runs and 6 invalid
environment or capture runs. The sample is small, prompted, heterogeneous, and
qualitatively interpreted. It supports only the narrow observations recorded in
the JSON summary. It does not establish statistical significance, general agent
improvement, general superiority over compiler/test or IDE/LSP tooling, or
spontaneous adoption.

Raw transcripts, client/controller automation, environment captures, and
task-chronology reports are intentionally excluded from this public subset.
They are not required by the deterministic checks below.

## Deterministic reproduction

From a clean checkout with JDK 21, sbt, and Python 3:

```bash
python3 benchmarks/validate-public-evidence.py
sbt -batch test
sbt cli/stage
scripts/benchmark/semantic-scala-packaged version
scripts/mcp/smoke-mcp-tools.py
```

These commands validate admitted files, the full repository test suite, the
portable packaged-CLI wrapper, and the exact-eight-tool MCP example. They do not
launch a live model session and do not regenerate historical screening results.

The public skill policy and thin wrappers can be inspected at
[`../skills/semantic-scala/SKILL.md`](../skills/semantic-scala/SKILL.md),
[`../.agents/skills/semantic-scala/SKILL.md`](../.agents/skills/semantic-scala/SKILL.md),
and [`../.claude/skills/semantic-scala/SKILL.md`](../.claude/skills/semantic-scala/SKILL.md).
