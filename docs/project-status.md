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
- explicit target-JDK selection for existing sbt-backed operations while the
  harness stays on its supported JDK 21 runtime;
- SemanticDB inventory, coverage, symbol, and exact-symbol usage evidence;
- bounded Presentation Compiler type and symbol queries;
- dynamic/static symbol reconciliation;
- a public semantic point-evidence composition with typed discovery,
  snapshot-consistent content freshness, live-point, selection, and
  reconciliation outcomes, including a stale-artifact completion gate;
- opt-in alpha-3 build-target-aware SemanticDB mapping and point evidence with
  one fixed Compile receipt and closed v3 contracts;
- a CLI-only same-request post-compile TASTy point-evidence operation and
  strengthened sbt-backed Presentation Compiler context warnings;
- bounded sbt 1.12.15 / 2.0.6 compatibility for the existing selected
  build-oracle, PC-classpath, and TASTy-receipt subprocess families;
- conservative FP effect summaries;
- an MCP server with exactly eight public tools;
- agent-first alpha-2 CLI/MCP onboarding with explicit cross-client
  qualification statuses and an intentional surface-asymmetry matrix;
- examples, fixtures, CI, and benchmark infrastructure; and
- a client-neutral agent skill with thin repository wrappers.

Compiler, build, and tests remain the final correctness oracle. Semantic
results preserve their stated scope and uncertainty.

The current alpha-3 SNAPSHOT source-paired SemanticDB v2 correction has one
bounded real-project acceptance replay on frozen Cats Effect / Scala 3.3.7.
Clean captured source/artifact identities produced `Fresh` and
`CompletedFresh`; two independent post-compile source edits retained the same
artifact bytes, produced `Stale`, and were structurally gated as
`NotAttempted(StaleArtifact)` in composed and direct reconciliation. This is a
focused provenance qualification, not a release, whole-project build, broad
ecosystem, or general freshness claim.

Current alpha-3 SNAPSHOT target-aware v3 preserves the same workspace discovery
and no-option v2 ambiguity, then uses an authoritative sbt-reported SemanticDB
root to select only one canonically owned target artifact. The same receipt
supplies live classpath and redacted JDK attribution. Receipt acquisition can
execute checked-in build/plugin code, populate caches, and compile transitively
while evaluating `Compile / fullClasspath`;
the live compiler does not replay target flags or plugins. The initial request
has no cross-Scala-version selector and cannot inherit an earlier sbt `++`
selection. Frozen Cats Effect 3.3.7 acceptance therefore remains unqualified:
a fresh project-only receipt selected that build's default 2.13.18 axis. This
is not general target discovery, Scala.js equivalence, release readiness,
primitive uniqueness, or IDE/LSP superiority.

## Evidence and readiness

These dimensions are independent and are not averaged into one readiness
score.

| Dimension | State | Evidence and remaining gate |
| --- | --- | --- |
| Public alpha product readiness | `READY` | Apache-2.0, focused newcomer/release policies, a 245-file allowlist, an independently initialized one-commit candidate, current-tree and reachable-history scans, fresh-clone validation, and public-ref/object proof pass for an experimental source alpha. This dimension alone does not establish supported distribution. |
| External installation usability | `READY` | An anonymous public HTTPS clone was staged from the documented JDK 21/sbt/Python/Git source contract, then Claude Code 2.1.220 used an explicit session-local MCP configuration to discover exactly eight tools and return a bounded `semantic_effect_summary` result. The temporary configuration was removable and no supported binary, package-manager, marketplace, or conformant Agent Plugins client route is implied. |
| Supported distribution usability | `READY` | Fresh outsider-like JDK 21 states consumed the actual public channel plus Maven Central only. Exact `0.1.0-alpha.2` CLI version, no-override ordered eight-tool MCP, bounded read-only schema, retained-channel update, uninstall, anonymous access, and checkout/private-path independence passed. A fresh Coursier 2.1.25-M26 install/update replay and Codex CLI 0.149.0 client call now also pass. This state is limited to that exact application route and does not imply adoption, embeddable-library stability, broad Scala compatibility, or other package formats. |
| Skill discoverability/installability | `READY` | The immutable alpha-2 canonical skill was installed byte-for-byte in disposable project-local locations and discovered by Codex CLI 0.149.0 and Claude Code 2.1.220. Codex explicitly selected it and completed one bounded MCP call. READY is narrowly scoped to those two project-local discovery/install paths; Cursor remains an official unqualified recipe and VS Code/Copilot was unavailable. It does not establish autonomous adoption effectiveness. |
| Semantic primitive marginal utility | `EVIDENCE_PARTIAL` | Bounded evidence identifies useful symbol, provenance, usage, and reconciliation behavior, but no general advantage over a strong direct-tool baseline is established. On one frozen Macro-Paradise target, fresh Compile plus standard exact-version Scala `-print-tasty`, source-offset mapping, and `javap` recovered fact-equivalent point evidence. That scoped comparison demonstrated no primitive-level fact advantage for the implemented post-compile TASTy lane; it does not establish general redundancy across projects, plugins, compiler versions, or ordinary-tool conditions. |
| Composition/orchestration marginal utility | `READY` | Deterministic contract evidence plus independent real-project trials on `quasiquotes-scala3` and Cats Effect show that point evidence preserves direct discovery, live, reconciliation, selection, routing, and typed non-attempt facts while materially reducing caller merge and unsafe-selection risk. On one frozen public Cats Effect target, Codex CLI 0.149.1 with `gpt-5.4` received fact-equivalent primitive inputs in two low-reasoning C/D pairs and one preregistered medium-reasoning C/D pair. Coherent `point-evidence` preserved exact typed non-attempt attribution materially better in both configurations; low reasoning also showed fewer final classification/merge mistakes. Both arms made zero unsafe artifact selections and zero false static/live agreement claims. The TASTy lane further illustrates normalized selected-build ownership, point mapping, source/artifact identity, freshness, uncertainty, and no-replay provenance even where ordinary tools can recover the individual facts. These bounded live-agent results maintain rather than upgrade `READY` and do not establish general client/model/provider/repository superiority, latency advantage, broad IDE/LSP superiority, primitive marginal utility, Scala 2 compatibility, or skill/adoption benefit. |
| Scala target compatibility | `EVIDENCE_PARTIAL` | Two maintained Scala 2.13.18 projects at frozen upstream commits yield the bounded qualifier `EVIDENCE_PARTIAL_TWO_REAL_PROJECTS`. `scala/scala-java8-compat` passed its unchanged root build, all 116 tests, syntax-first, bounded dynamic, degraded point-evidence, and exact-eight MCP lanes but produced no SemanticDB. A bounded `scalacenter/scalafix` production row produced target-owned SemanticDB and passed static symbol discovery, bounded dynamic lookup, exact reconciliation, complete point evidence, and exact-eight MCP. Current alpha-3 SNAPSHOT development adds optional validated project and installed target-JDK selectors without changing immutable alpha-2. Disposable sbt 2.0.6 and 2.0.7 fixtures pass selected build-oracle, structured-test, classpath, and TASTy rows, with sbt 1.12.15 preservation on a frozen plugin project. Frozen Chimney on sbt 2.0.7 / Scala 3.8.4 also passes selected compile/errors/test and repeated Test-classpath acquisition; its 1,151 passing and 4 ignored MUnit tests are preserved as 1,155 total, 1,151 passed, and 0 failed in the existing schema. Selected versions/JDKs remain bounded target-build facts, not general harness-runtime or ecosystem support. These projects and selected rows do not establish broad Scala 2 dialect, macro, compiler-plugin, classpath, build-system, ecosystem, or whole-workspace compatibility. |
| Target-JDK orchestration | `READY` | Current alpha-3 SNAPSHOT development validates and applies an explicitly selected installed JDK only to the target sbt child for the admitted sbt-backed operations. It does not discover or install JDKs, change the JDK 21 harness baseline, or extend immutable alpha-2. |
| Compiler-plugin PC-context evidence | `EVIDENCE_PARTIAL` | Alpha-3 SNAPSHOT implements a CLI-only same-request selected-Compile receipt and exact stable Scala 3 bounded child inspector. Frozen Macro-Paradise on sbt 1.12.15 / Scala 3.8.4 / selected JDK 25 still resolves C/M/F/S. An independent frozen scala-newtype-compat plugin and exact plugin-dependent consumer on sbt 2.0.6 / Scala 3.7.4 now resolves D/C/O/A after byte-identical plugin reproduction. Both cases retain exact source/artifact/worker/toolchain evidence and `targetCompilerOptionsReplayed=false` / `targetPluginsReplayed=false`. Their classpath-only PC controls keep explicit non-causal provenance rather than claiming plugin replay. The bounded qualifier is `EVIDENCE_PARTIAL_TWO_INDEPENDENT_PLUGIN_CASES`; it does not establish READY, whole-workspace atomicity, Scala 2/nightly/custom compiler support, a security sandbox, or general compiler-plugin semantics. |
| Agent skill adoption effectiveness | `EVIDENCE_PARTIAL` | Current `skills` CLI discovery exposes exactly one canonical `semantic-scala` candidate and installs self-contained policy for Codex and Claude Code. The policy distinguishes one-point `symbol-at` from artifact-routing `point-evidence` and reserves CLI-only TASTy for authoritative generated-fact questions. In a bounded generated-fact, narrow-symbol, and source-sufficient comparison, skill guidance improved restraint and cleanliness in some cells but did not cause selection of the required generated-fact route; both arms scored `2/3` on primary routing. No material routing gain was established, so neither immediate automatic skill retuning nor MCP-surface expansion is admitted. This does not establish READY or generalize beyond the tested client, model, and cases. |
| Benchmark reproducibility | `READY` | The admitted public subset contains portable prompts, test-coupled fixtures, a bounded aggregate, and deterministic validation; it passed manifest, link, full-test, staged CLI, wrapper, and MCP smoke gates in a history-free disposable tree. This does not reproduce private historical sessions or establish general effectiveness. |
| Publication safety | `READY` | The public product has an audited clean root followed only by reviewed public-product commits. The separate mixed-history archive and private control repository remain private; their refs and object graphs are not part of the public product. |

`0.1.0-alpha.1` remains a published source-only prerelease at immutable commit
`e2c6eef57124b79c0062b25f48a719685c63905e`, tree
`281bb7600701d8c93fff2739a7fdb8d781ce8554`. The lightweight
`0.1.0-alpha.2` tag and GitHub prerelease point to immutable commit
`4a384cce0553815bf33d5d72fc0379c4d18e0d59`, tree
`2c260f1f07c938435b7a76b085bd2b8f3c4dbbb8`, whose source reproduces all 32
immutable Maven Central primary files byte-for-byte. Mutable `main` now reports
`0.1.0-alpha.3-SNAPSHOT`; no alpha-3 artifact, channel, tag, or Release exists.
The deterministic project-owned Coursier URL channel remains pinned to alpha-2
and has passed independent live-main and commit-pinned fresh-cache installation,
CLI/MCP runtime, update, read-only, and uninstall checks under JDK 21. General
agent-skill adoption effectiveness, broader semantic superiority, complete
Scala 2 support, and 1.0 stability remain unproven.

## Next evidence milestone

The evidence sequence is not a product delivery promise. Before the planned
community announcement, fresh Scala/product development and bounded
real-project validation remain open. After the announcement, external feedback
from real Scala projects and concrete decisions becomes a primary source for
the next semantic and product gaps, reported with the bounded comparison and
reproducibility context in [`early-feedback.md`](early-feedback.md). The most
useful cases show either a missing decision fact or materially better
composition of facts that strong compiler, build/test, IDE/LSP, or standard
artifact tooling can already provide. Installation/client friction and exact
Scala/sbt/JDK/project compatibility failures are also in scope.

Immediate TASTy-primitive differentiation, ninth-MCP-tool exposure, and
automatic skill-retuning loops are closed. Skill adoption, Scala target
compatibility, and compiler-plugin semantics remain evidence-partial. The two
independent compiler-plugin cases preserve their bounded qualifier, while the
strongest frozen ordinary-tool comparison found fact-equivalent point evidence
and therefore no demonstrated primitive fact advantage. The supported package
remains alpha-2; alpha-3 SNAPSHOT behavior on `main` remains unreleased source
development.
