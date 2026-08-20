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
| External installation usability | `READY` | An anonymous public HTTPS clone was staged from the documented JDK 21/sbt/Python/Git source contract, then Claude Code 2.1.220 used an explicit session-local MCP configuration to discover exactly eight tools and return a bounded `semantic_effect_summary` result. The temporary configuration was removable and no supported binary, package-manager, marketplace, or conformant Agent Plugins client route is implied. |
| Supported distribution usability | `NOT_ASSESSED` | The exact-eight modular Maven graph uses final group `com.github.dmytromitin`, explicitly rejects the provisional group, and has a locally frozen exact `0.1.0-alpha.2` candidate backed by two-build and disposable JDK 21 install/update/uninstall proof. All 16 formerly flagged license/NOTICE rows are technically dispositioned, including the owner-selected Apache-2.0 path for JNA 5.14.0. Nothing is externally published and the intended public route has no independent install; source-build usability stays separate. |
| Semantic primitive marginal utility | `EVIDENCE_PARTIAL` | Bounded evidence identifies useful symbol, provenance, usage, and reconciliation behavior, but no general advantage over a strong direct-tool baseline is established. |
| Composition/orchestration marginal utility | `READY` | Deterministic contract evidence plus independent real-project trials on `quasiquotes-scala3` and Cats Effect show that point evidence preserves direct discovery, live, reconciliation, selection, routing, and typed non-attempt facts while materially reducing caller merge and unsafe-selection risk. This does not establish live-agent decision-quality, latency, broad IDE/LSP superiority, primitive marginal utility, Scala 2 compatibility, or skill/adoption benefit. |
| Scala target compatibility | `EVIDENCE_PARTIAL` | One parallel JDK 21 matrix verifies compile/test/error delegation, SemanticDB discovery/symbol/usages, syntax-first effect summaries, and exact-eight MCP projection on Scala 2.13.18 and Scala 3.3.8. Shared-syntax Scala 2 symbol/type points also resolved and reconciled exactly, but through the pinned Scala 3.3 presentation compiler; broad Scala 2 dialect, macro, compiler-plugin, classpath, and real-project compatibility remain untested. |
| Agent skill adoption effectiveness | `NOT_ASSESSED` | The canonical skill, wrappers, and portable package source exist. No installed client documented a safe nonpersistent Agent Plugins 1.0 package-load route, so no valid spontaneous selection/non-selection case ran; misuse resistance and incremental decision value remain unproven. |
| Benchmark reproducibility | `READY` | The admitted public subset contains portable prompts, test-coupled fixtures, a bounded aggregate, and deterministic validation; it passed manifest, link, full-test, staged CLI, wrapper, and MCP smoke gates in a history-free disposable tree. This does not reproduce private historical sessions or establish general effectiveness. |
| Publication safety | `READY` | The public product has an audited clean root followed only by reviewed public-product commits. The separate mixed-history archive and private control repository remain private; their refs and object graphs are not part of the public product. |

`0.1.0-alpha.1` is a published source-only prerelease. Its tag and GitHub
Release point to immutable commit
`e2c6eef57124b79c0062b25f48a719685c63905e`, tree
`281bb7600701d8c93fff2739a7fdb8d781ce8554`. Current `main` is post-release
development and reports `0.1.0-alpha.2-SNAPSHOT`; that development identity is
not a commitment to publish `0.1.0-alpha.2`. A project-built Maven/Coursier
candidate is locally validated but no public binary, package, or client
distribution is established. Agent Plugin adoption effectiveness,
broader semantic superiority, complete Scala 2 support, and 1.0 stability
remain unproven.

## Next evidence milestone

The reviewed exact candidate has reached `VALIDATED` in a `USER_MANAGED`
Central deployment and remains unpublished. Publication and independent public-
install validation are the next separate, explicitly authorized gates.
Packaged distribution must remain separate from the already verified source-
build lane.

After that foundation, adoption work should test appropriate agent selection
with and without the canonical skill, compatibility work should exercise a
maintained real Scala 2.13 project before harder legacy targets, and new
semantic capabilities should originate from concrete user, adoption,
compatibility, or benchmark gaps rather than generic feature exploration.
