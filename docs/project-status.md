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

A potential future role is a Scala-specific **semantic evidence broker /
decision layer** for agents: compose bounded compiler, build/target, test,
Presentation Compiler, SemanticDB, TASTy/classfile, and separately admitted
IDE/LSP evidence into one packet with explicit target/toolchain provenance,
freshness, completeness, uncertainty, disagreement, acquisition effects,
resource bounds, and typed safe non-action reasons. This is a hypothesis for
controlled comparison, not a completed architecture, semantic-superiority
claim, or authority for a new backend, persistent IDE integration,
orchestration framework, or MCP tool.

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
- opt-in alpha-3 build-target-aware SemanticDB source mapping v4 with a
  validated Scala-axis selector and non-compiling root-only receipt, alongside
  target-aware point-evidence v4 with a non-compiling partial existing-output
  receipt, v5 optional existing internal outputs, and strict v6 freshness
  gating for those internal outputs;
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

Current alpha-3 SNAPSHOT target-aware source mapping v4 preserves the same
workspace discovery and no-option v2 ambiguity, then uses an authoritative
sbt-reported SemanticDB root to select only one canonically owned target
artifact. Its root-only receipt supports an optional validated Scala axis and
does not request target compilation or `Compile / fullClasspath`; build/plugin
loading, resolution, and metadata/cache effects remain possible. Target-aware
point-evidence v4 likewise avoids compilation and uses only an existing selected
class directory plus target external dependencies. Its context remains
`PartialExistingOutputs`; it is not a complete arbitrary multi-project
classpath and does not replay target compiler flags or plugins. Frozen Cats
Effect JVM/JS prepared and missing-output controls and a disposable two-project
fixture qualify this bounded behavior. This is not general target discovery,
release readiness, primitive uniqueness, or IDE/LSP superiority.
An explicit target-aware v5 opt-in can also incorporate already-present
same-axis internal Compile dependency outputs through a settings-only,
fail-closed graph receipt. It keeps missing outputs absent, records deterministic
provenance, and remains `PartialExistingCompileOutputs`; it does not compile,
model arbitrary configuration algebra, or replay compiler options/plugins.
Frozen cats-tagless 2.13/2.12 and missing-output controls qualify this bounded
addition while the Cats Effect default v4 rows remain unchanged.
The second explicit v6 opt-in reads existing same-axis Zinc compile analysis
and permits only internal outputs that pass bounded source/product inventory,
relation, and content checks to contribute. On the same frozen cats-tagless
target, clean v6 retained the annotation symbol, while the one-line producer
rename, missing selected-axis analysis, wrong-axis-only analysis, and missing
class directory all excluded the affected support without regenerating it.
The reader now runs as one cache-first, on-demand bounded JDK 21 worker, keeping
its Zinc graph off normal CLI/MCP process classpaths. Cold offline worker
unavailability is Unverifiable rather than a build fallback. This is a bounded
stale-support and runtime-placement result, not whole-target/build freshness,
release readiness, or a total-disk-use reduction claim.

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
| Composition/orchestration marginal utility | `READY` | This state is scoped to controlled coherent composition. Deterministic contract evidence plus independent real-project trials on `quasiquotes-scala3` and Cats Effect show that point evidence preserves direct discovery, live, reconciliation, selection, routing, and typed non-attempt facts while materially reducing caller merge and unsafe-selection risk. On one frozen public Cats Effect target, Codex CLI 0.149.1 with `gpt-5.4` received fact-equivalent primitive inputs in two low-reasoning C/D pairs and one preregistered medium-reasoning C/D pair. Coherent `point-evidence` preserved exact typed non-attempt attribution materially better in both configurations; low reasoning also showed fewer final classification/merge mistakes. Both arms made zero unsafe artifact selections and zero false static/live agreement claims. The TASTy lane further illustrates normalized selected-build ownership, point mapping, source/artifact identity, freshness, uncertainty, and no-replay provenance even where ordinary tools can recover the individual facts. Later natural-surface competitive evidence is tracked separately below and does not erase or broaden this controlled result. These bounded live-agent results maintain rather than upgrade `READY` and do not establish general client/model/provider/repository superiority, latency advantage, broad IDE/LSP superiority, primitive marginal utility, Scala 2 compatibility, or skill/adoption benefit. |
| Natural competitive semantic-tool value | `EVIDENCE_PARTIAL` | Released Metals MCP 1.6.8 was mechanically qualified with its exact 17-tool surface and reproducible isolated writable initialization on two frozen targets. On the fully valid generated-fact comparison, ordinary, Metals, and semantic-scala arms scored 8/8 while the combined arm scored 7/8 with no veto; the one-point loss was non-material. On the shared-source comparison, valid ordinary, Metals, and combined arms scored 6/6, and the combined arm's selected semantic CLI evidence sharpened provenance and typed ambiguity without changing the decision. The semantic-only condition exceeded its operation cap twice, so its confirmatory comparisons are unavailable. Direct calibration established MCP callability, but no treatment selected an MCP call. These results distinguish available capability, natural selection, and material gain; they do not establish general redundancy, statistical performance, or a negative broker verdict. Automatic reruns of these two cases are closed. |
| Scala target compatibility | `EVIDENCE_PARTIAL` | Two maintained Scala 2.13.18 projects at frozen upstream commits yield the bounded qualifier `EVIDENCE_PARTIAL_TWO_REAL_PROJECTS`. `scala/scala-java8-compat` passed its unchanged root build, all 116 tests, syntax-first, bounded dynamic, degraded point-evidence, and exact-eight MCP lanes but produced no SemanticDB. A bounded `scalacenter/scalafix` production row produced target-owned SemanticDB and passed static symbol discovery, bounded dynamic lookup, exact reconciliation, complete point evidence, and exact-eight MCP. Current alpha-3 SNAPSHOT development adds optional validated project and installed target-JDK selectors without changing immutable alpha-2. Disposable sbt 2.0.6 and 2.0.7 fixtures pass selected build-oracle, structured-test, classpath, and TASTy rows, with sbt 1.12.15 preservation on a frozen plugin project. Frozen Chimney on sbt 2.0.7 / Scala 3.8.4 also passes selected compile/errors/test and repeated Test-classpath acquisition; its 1,151 passing and 4 ignored MUnit tests are preserved as 1,155 total, 1,151 passed, and 0 failed in the existing schema. Selected versions/JDKs remain bounded target-build facts, not general harness-runtime or ecosystem support. These projects and selected rows do not establish broad Scala 2 dialect, macro, compiler-plugin, classpath, build-system, ecosystem, or whole-workspace compatibility. |
| Target-JDK orchestration | `READY` | Current alpha-3 SNAPSHOT development validates and applies an explicitly selected installed JDK only to the target sbt child for the admitted sbt-backed operations. It does not discover or install JDKs, change the JDK 21 harness baseline, or extend immutable alpha-2. |
| Compiler-plugin PC-context evidence | `EVIDENCE_PARTIAL` | Alpha-3 SNAPSHOT implements a CLI-only same-request selected-Compile receipt and exact stable Scala 3 bounded child inspector. Frozen Macro-Paradise on sbt 1.12.15 / Scala 3.8.4 / selected JDK 25 still resolves C/M/F/S. An independent frozen scala-newtype-compat plugin and exact plugin-dependent consumer on sbt 2.0.6 / Scala 3.7.4 now resolves D/C/O/A after byte-identical plugin reproduction. Both cases retain exact source/artifact/worker/toolchain evidence and `targetCompilerOptionsReplayed=false` / `targetPluginsReplayed=false`. Their classpath-only PC controls keep explicit non-causal provenance rather than claiming plugin replay. The bounded qualifier is `EVIDENCE_PARTIAL_TWO_INDEPENDENT_PLUGIN_CASES`; it does not establish READY, whole-workspace atomicity, Scala 2/nightly/custom compiler support, a security sandbox, or general compiler-plugin semantics. |
| Agent skill adoption effectiveness | `EVIDENCE_PARTIAL` | Current `skills` CLI discovery exposes exactly one canonical `semantic-scala` candidate and installs self-contained policy for Codex and Claude Code. The policy distinguishes one-point `symbol-at` from artifact-routing `point-evidence` and reserves CLI-only TASTy for authoritative generated-fact questions. In a bounded generated-fact, narrow-symbol, and source-sufficient comparison, skill guidance improved restraint and cleanliness in some cells but did not cause selection of the required generated-fact route; both arms scored `2/3` on primary routing. In later natural-surface treatments, no arm selected MCP; one combined arm selected semantic CLI evidence and improved provenance without changing the primary decision, while the semantic-only condition exceeded its operation cap twice. One repetition relocated a repository-only thin wrapper outside its documented supported role, so that routing failure is an experimental deviation rather than evidence of canonical-skill install fragility or intrinsic complexity. No material routing gain was established, so neither immediate automatic skill retuning nor MCP-surface expansion is admitted. This does not establish READY or generalize beyond the tested client, model, and cases. |
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

The planned Metals intake and competitive sequence is substantially executed,
without adding a Metals integration. The generated-fact comparison is a valid
bounded null result: ordinary evidence was sufficient and neither specialized
surface produced a material primary advantage. The shared-source comparison
confirms ordinary/Metals/combined parity in its valid arms, but the repeated
semantic-only operation-cap deviation keeps that independent comparison
unavailable. No automatic rerun of either case is planned.

The broker / decision-layer hypothesis remains open but was not negatively
tested: the completed cases gave the combined arm no distinct primary fact or
criterion beyond the strongest single arm. Any future combined comparison must
start from an opportunity-qualified real decision where independently useful
sources contribute distinct facts and a preregistered combined result can
improve correctness, safety, or materially reduce orchestration friction. This
does not authorize a Metals implementation or a benchmark designed to force a
combined-arm win.

The next gap source shifts toward feedback from real Scala projects, users, and
the community, reported with the bounded comparison and reproducibility context
in [`early-feedback.md`](early-feedback.md). Before and through the planned
early-September community announcement, fresh Scala/product development and
bounded real-project validation remain open; feedback emphasis is not a stop on
all development.

Immediate TASTy-primitive differentiation, ninth-MCP-tool exposure, and
automatic skill-retuning loops are closed. Skill adoption, Scala target
compatibility, and compiler-plugin semantics remain evidence-partial. The two
independent compiler-plugin cases preserve their bounded qualifier, while the
strongest frozen ordinary-tool comparison found fact-equivalent point evidence
and therefore no demonstrated primitive fact advantage. The supported package
remains alpha-2; alpha-3 SNAPSHOT behavior on `main` remains unreleased source
development. Alpha-3 is intentionally not cut immediately after the current
implementation or the first comparison; release consideration is deferred
toward qualification nearer the planned announcement and requires separate
authority.
