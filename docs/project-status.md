# Project status

`scala-semantic-harness` is a Scala and functional-programming semantic
evidence layer for coding agents. It makes compiler, build, test, Presentation
Compiler, and SemanticDB evidence easier to use without replacing mature
IDE/LSP tooling.

## Product hypothesis

The harness can create marginal value in two independently testable ways:

1. a bounded Scala/compiler/FP result answers a decision-relevant question
   that strong ordinary tooling does not answer adequately; or
2. a coherent operation combines existing primitives with better context,
   provenance, freshness, completeness, disagreement, ordering, or resource
   bounds and improves the agent decision.

Fewer commands alone is not evidence of composition value. Both modes require
comparison with a strong baseline that can use compiler, tests, and appropriate
IDE/LSP/MCP tooling directly.

## Implemented foundation

- Scala 3 CLI with structured compile, test, and diagnostic results;
- SemanticDB inventory, coverage, symbol, and exact-symbol usage evidence;
- bounded Presentation Compiler type and symbol queries;
- dynamic/static symbol reconciliation;
- a public semantic point-evidence composition with typed discovery,
  live-point, selection, and reconciliation outcomes;
- conservative FP effect summaries;
- an MCP server with exactly eight public tools;
- examples, fixtures, CI, and benchmark infrastructure; and
- a client-neutral agent skill with thin repository wrappers.

Compiler, build, and tests remain the final correctness oracle. Semantic
results preserve their stated scope and uncertainty.

## Evidence and readiness

These dimensions are independent and are not averaged into one readiness
score.

| Dimension | State | Evidence and remaining gate |
| --- | --- | --- |
| Public alpha product readiness | `READY` | Apache-2.0, focused newcomer/release policies, a 244-file allowlist, an independently initialized one-commit candidate, current-tree and reachable-history scans, fresh-clone validation, and public-ref/object proof pass for an experimental source alpha. This is not a supported binary/package release. |
| External installation usability | `EVIDENCE_PARTIAL` | A deterministic generated Agent Plugins 1.0.0 directory bundles the canonical skill plus complete CLI/MCP stage trees. Relocated runtime validation exposes exactly eight tools and exercises the CLI-backed point-evidence composition without checkout fallback. No supported release channel, installed conformant-client load, or independent first-use proof exists. |
| Semantic primitive marginal utility | `EVIDENCE_PARTIAL` | Bounded evidence identifies useful symbol, provenance, usage, and reconciliation behavior, but no general advantage over a strong direct-tool baseline is established. |
| Composition/orchestration marginal utility | `EVIDENCE_PARTIAL` | A bounded public CLI plus thin-MCP point-evidence composition preserves the direct discovery and reconciliation facts while removing caller-controlled candidate selection and route branching for exact, mismatch, no-match, ambiguous, partial/unparseable, and live-unavailable cases. Live-agent decision-quality benefit remains unestablished. |
| Agent skill adoption effectiveness | `NOT_ASSESSED` | The canonical skill, wrappers, and portable package source exist. No installed client documented a safe nonpersistent Agent Plugins 1.0 package-load route, so no valid spontaneous selection/non-selection case ran; misuse resistance and incremental decision value remain unproven. |
| Benchmark reproducibility | `READY` | The admitted public subset contains portable prompts, test-coupled fixtures, a bounded aggregate, and deterministic validation; it passed manifest, link, full-test, staged CLI, wrapper, and MCP smoke gates in a history-free disposable tree. This does not reproduce private historical sessions or establish general effectiveness. |
| Publication safety | `READY` | A clean candidate and exact publication sequence are cleared for a separate explicit source-repository publication action. The existing mixed repository and its historical object graph remain private and are not cleared for visibility change, in-place rewrite, or force-push. |

The project is not presented as public-release-ready, generally superior to
generic semantic tooling, or proven to improve every Scala task.

## Next public-readiness milestone

If the user separately authorizes publication, preserve or rename the mixed
historical repository privately and publish only a freshly reverified copy of
the independent clean candidate in a separate public repository. Verify the
GitHub name and redirect behavior immediately before that action; redirects are
not a privacy control.
