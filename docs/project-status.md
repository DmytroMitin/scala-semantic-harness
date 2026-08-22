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
| Public alpha product readiness | `READY` | Apache-2.0, focused newcomer/release policies, a 244-file allowlist, an independently initialized one-commit candidate, current-tree and reachable-history scans, fresh-clone validation, and public-ref/object proof pass for an experimental source alpha. This dimension alone does not establish supported distribution. |
| External installation usability | `READY` | An anonymous public HTTPS clone was staged from the documented JDK 21/sbt/Python/Git source contract, then Claude Code 2.1.220 used an explicit session-local MCP configuration to discover exactly eight tools and return a bounded `semantic_effect_summary` result. The temporary configuration was removable and no supported binary, package-manager, marketplace, or conformant Agent Plugins client route is implied. |
| Supported distribution usability | `READY` | Two fresh outsider-like JDK 21 states consumed the actual public `main` and commit-pinned project channel URLs plus public Maven Central only. Exact `0.1.0-alpha.2` CLI version, no-override ordered eight-tool MCP, bounded read-only schema, retained-channel update, uninstall, anonymous access, and checkout/private-path independence all passed. This state is limited to that exact application route and does not imply discovery, adoption, embeddable-library stability, broad Scala compatibility, or other package formats. |
| Semantic primitive marginal utility | `EVIDENCE_PARTIAL` | Bounded evidence identifies useful symbol, provenance, usage, and reconciliation behavior, but no general advantage over a strong direct-tool baseline is established. |
| Composition/orchestration marginal utility | `READY` | Deterministic contract evidence plus independent real-project trials on `quasiquotes-scala3` and Cats Effect show that point evidence preserves direct discovery, live, reconciliation, selection, routing, and typed non-attempt facts while materially reducing caller merge and unsafe-selection risk. This does not establish live-agent decision-quality, latency, broad IDE/LSP superiority, primitive marginal utility, Scala 2 compatibility, or skill/adoption benefit. |
| Scala target compatibility | `EVIDENCE_PARTIAL` | One parallel JDK 21 matrix verifies compile/test/error delegation, SemanticDB discovery/symbol/usages, syntax-first effect summaries, and exact-eight MCP projection on Scala 2.13.18 and Scala 3.3.8. Shared-syntax Scala 2 symbol/type points also resolved and reconciled exactly, but through the pinned Scala 3.3 presentation compiler; broad Scala 2 dialect, macro, compiler-plugin, classpath, and real-project compatibility remain untested. |
| Agent skill adoption effectiveness | `EVIDENCE_PARTIAL` | Current `skills` CLI discovery exposes exactly one canonical `semantic-scala` candidate and installs self-contained policy for Codex and Claude Code. A maintained audit recomputes the historical policy-aligned score as A `2/3`, B `3/3`, C `2/3`: the source-visible wrapper case warranted no semantic call, leaving only the narrow-symbol regression. The canonical policy now distinguishes one-point `symbol-at` from artifact-routing `point-evidence`. In an independent Codex CLI 0.147.0 / `gpt-5.4`-low replication, C was correct on all three cases, preserved the no-tool case, and used point evidence for the composition case, but it stated rather than completed the required MCP symbol call; appropriate selection/non-selection remained B `2/3` to C `2/3`. This does not establish READY or generalize beyond the tested client/model/cases. |
| Benchmark reproducibility | `READY` | The admitted public subset contains portable prompts, test-coupled fixtures, a bounded aggregate, and deterministic validation; it passed manifest, link, full-test, staged CLI, wrapper, and MCP smoke gates in a history-free disposable tree. This does not reproduce private historical sessions or establish general effectiveness. |
| Publication safety | `READY` | The public product has an audited clean root followed only by reviewed public-product commits. The separate mixed-history archive and private control repository remain private; their refs and object graphs are not part of the public product. |

`0.1.0-alpha.1` is a published source-only prerelease. Its tag and GitHub
Release point to immutable commit
`e2c6eef57124b79c0062b25f48a719685c63905e`, tree
`281bb7600701d8c93fff2739a7fdb8d781ce8554`. Current source reports exact
`0.1.0-alpha.2` and is prepared for a separately authorized Git tag and GitHub
Release after reproducing all 32 immutable Maven Central primary files
byte-for-byte. No alpha-2 tag or Release exists yet. The deterministic
project-owned Coursier URL channel is public and
has passed independent live-main and commit-pinned fresh-cache installation,
CLI/MCP runtime, update, read-only, and uninstall checks under JDK 21.
General agent-skill adoption effectiveness, broader semantic superiority,
complete Scala 2 support, and 1.0 stability remain unproven.

## Next evidence milestone

The exact alpha-2 Maven/Coursier application route, source/Maven byte coherence,
and canonical-skill installability are now independently qualified. A Git tag
and GitHub Release require separate authorization. Feedback should focus on
real Scala project cases, installation and maintenance issues, missing
decision-relevant semantic evidence, and comparison with strong compiler,
IDE/LSP, and build/test tooling. Skill adoption and Scala 2 compatibility
remain partial rather than READY.
