# Roadmap

This roadmap is organized by public product outcomes. It is not an internal
execution ledger and does not promise delivery dates.

## Implemented foundation

- Scala 3 multi-module build with structured CLI reports.
- Compile, test, and diagnostic build-oracle commands.
- Explicit installed target-JDK selection for the six sbt-backed CLI forms,
  with JDK 21 retained as the harness runtime and no automatic acquisition.
- SemanticDB inventory, coverage, symbols, and bounded usages.
- Presentation Compiler symbol and rendered-type queries.
- Static/dynamic symbol reconciliation.
- A public semantic point-evidence composition that selects only one parsed
  source artifact and preserves non-selection reasons.
- Opt-in alpha-3 target-aware SemanticDB source mapping v4 with a validated
  optional Scala axis and a non-compiling root-only receipt, plus target-aware
  point-evidence v4 with a non-compiling partial existing-output receipt and an
  explicit v5 opt-in for bounded same-axis existing internal Compile outputs.
- A CLI-only bounded post-compile TASTy point-evidence operation with a
  same-request selected-Compile receipt and exact stable Scala 3 child worker.
- Bounded fixed-task sbt subprocess compatibility proven on sbt 1.12.15 and
  2.0.6 for selected build oracles, PC classpath acquisition, and the private
  TASTy receipt.
- Stable non-causal target-context warnings on sbt-backed dynamic type evidence.
- Syntax-first FP effect summaries.
- A CLI-backed stdio MCP server with exactly eight tools.
- Public semantic-scala skill policy and repository wrappers.
- Agent-first alpha-2 installation, MCP, and immutable-skill recipes with a
  client qualification matrix and explicit CLI/MCP surface asymmetry.
- Examples, CI, benchmark fixtures, and methodology.

## Near-term priorities

1. Preserve exact `0.1.0-alpha.2` release coherence: its lightweight tag and
   GitHub prerelease identify the source that reproduces all 32 immutable Maven
   Central primaries, while the deterministic two-application public URL
   channel remains pinned to alpha-2. Mutable `main` is
   `0.1.0-alpha.3-SNAPSHOT`; no alpha-3 artifact or release is implied.
2. Continue fresh Scala/product development and bounded real-project validation
   before the planned early-September community announcement, while making
   real-project, user, and community feedback a primary source for the next
   decision-relevant gaps. Alpha-2 remains
   the supported package; alpha-3 remains SNAPSHOT development and is not cut
   merely to align packaging with mutable `main`. Alpha-3 consideration is
   intentionally nearer that announcement and remains subject to explicit
   qualification and release authority. Evaluate concrete Scala decisions
   against the compiler,
   build/test, IDE/LSP, and standard artifact tools already available to the
   user; retain the bounded context described in
   [`docs/early-feedback.md`](docs/early-feedback.md). This is evidence
   sequencing, not a delivery-date commitment.
3. Retain Amazon Q Developer as a future, post-announcement client/provider
   generalization candidate, not an immediate task or release gate. A future
   preregistered experiment should first reverify current vendor and product
   capabilities, prefer the CLI as an independent coding-agent/MCP client, use
   the supported public semantic-scala distribution current at that time, and
   compare a strong ordinary baseline with semantic-scala MCP on public Scala
   decisions. Measure decision correctness, unsafe edits or selections,
   evidence attribution, redundant calls, approvals/intervention, and wall
   time; do not treat fewer calls alone as value. Keep client/provider
   generalization separate from model generalization unless model identity and
   version can be mechanically frozen and held comparable. This candidate does
   not imply current qualification, support, or successful integration.
4. Keep post-compile TASTy evidence CLI-only and the MCP registry at exactly
   eight tools. On the strongest frozen ordinary-tool comparison, exact-version
   Scala TASTy output, source-offset mapping, and classfile inspection recovered
   fact-equivalent point evidence. That scoped result demonstrates no primitive
   fact advantage, but does not make TASTy generally redundant or erase its
   composition, robustness, and provenance value. Immediate TASTy hardening,
   ninth-tool exposure, and primitive-differentiation work are closed; revisit
   only for a materially different target or tool condition with a
   decision-relevant fact gap.
5. Preserve the qualified canonical-skill install route and corrected
   `symbol-at` versus `point-evidence` boundary. Controlled selection evidence
   found cleaner behavior in some cases but no material generated-fact routing
   gain. A later natural-surface comparison made zero MCP calls, selected the
   semantic CLI in one combined condition, and gained sharper provenance without
   changing the decision. Adoption effectiveness therefore remains partial and
   immediate automatic skill retuning is not admitted.
6. Maintain the agent-first alpha-2 onboarding recipes and exact per-client
   qualification statuses without claiming MCP/CLI command-count parity.
7. Preserve the maintained real-project matrices and the narrow alpha-3
   SNAPSHOT project and target-JDK selectors admitted from their evidence.
   Continue to treat a selected row/runtime as bounded build evidence, not
   whole-workspace success, automatic project discovery, or general alternate
   JDK support.
8. Admit new semantic capabilities only from concrete user, adoption,
   compatibility, or benchmark gaps that strong ordinary tooling does not
   already address adequately.

## Competitive evidence and next gate

The planned baseline-intake and competitive sequence is now substantially
executed. It qualified released Metals MCP 1.6.8 with its exact 17-tool surface
and reproducible isolated writable initialization on both frozen targets, then used
the same client, model, reasoning level, task wording, and repository state
where mechanically possible across these arms:

- **A:** source/shell plus compiler/test baseline;
- **B:** current verified Metals MCP or supported headless Metals surface;
- **C:** `semantic-scala`; and
- **D:** Metals plus `semantic-scala`.

On the generated-fact decision, all four arms were protocol-valid: A/B/C scored
8/8 and D scored 7/8 with no veto. The one-point loss was non-material, so the
ordinary baseline was sufficient for that exact decision and neither
specialized surface showed a material primary advantage. This is not a general
redundancy claim.

On the shared-source target decision, repeated valid A/B observations scored
6/6; a later valid D observation also scored 6/6 and used `semantic-scala` CLI
evidence to sharpen provenance and typed ambiguity without changing the primary
decision. The independent C condition exceeded its frozen operation budget in
both attempts, so C comparisons remain unavailable. Its second run also used a
relocated repository wrapper outside its documented source-tree role; that is a
bounded experimental deviation, not evidence of supported-install fragility or
intrinsic semantic complexity.

Direct calibration established that the configured MCP surfaces were callable,
but no treatment selected an MCP call. Capability availability, natural tool
selection, and material decision improvement therefore remain separate. The
automatic S/G comparative rerun sequence is closed; do not rerun it merely to
force a complete or favorable matrix.

The evidence-broker / decision-layer hypothesis remains open but opportunity
gated. The completed cases gave the combined arm no distinct primary fact or
criterion unavailable to the strongest single arm, so their null results are
not negative broker evidence. Any future combined comparison must begin with a
real decision where two independently useful sources contribute distinct facts
and the combined packet has preregistered room to improve correctness, safety,
or materially reduce orchestration friction. Do not implement a Metals backend
or invent a benchmark criterion merely to manufacture that opportunity.

The next gap source shifts toward bounded real-project, user, and community
feedback against strong ordinary tooling. That emphasis does not stop fresh
product development or compatibility validation; it governs which new semantic
capabilities are admitted.

## Semantic evidence quality

- Keep facts owned by the narrowest appropriate compiler, build, test,
  Presentation Compiler, or SemanticDB surface.
- Preserve provenance, freshness, coverage, disagreement, and uncertainty in
  public results.
- Keep source-paired SemanticDB discovery, point evidence, and reconciliation
  snapshot-consistent: content identity owns freshness, stale artifacts cannot
  complete reconciliation, and unverifiable evidence remains qualified.
- Preserve the target-aware boundary: source mapping v4 requests root/JDK/axis
  attribution without target compilation, while point evidence v4 requests only
  an existing selected class directory plus target external dependencies and
  stays explicitly partial; neither is target compiler-option/plugin replay.
- Add capabilities only after a decision-relevant gap is demonstrated against
  strong ordinary tooling.
- Keep resource and output bounds explicit and tested.

Validation depth follows the semantic boundary being changed: ordinary focused
implementation receives focused unit/schema/integration tests; a semantic
boundary change also receives bounded real-project acceptance; a release
candidate requires full regression, package/install/publication-shape, and
privacy qualification. These tiers do not authorize release actions.

## Composition and orchestration

- Maintain the admitted CLI plus thin-MCP point-evidence shape and the current
  `READY` composition/orchestration state. Deterministic composition evidence
  now has a bounded live-agent qualifier on one frozen public Cats Effect case:
  Codex CLI 0.149.1 with `gpt-5.4` compared fact-equivalent direct primitives
  with coherent `point-evidence` in two low-reasoning pairs and one
  medium-reasoning pair. Coherent results preserved typed non-attempt
  attribution materially better in both configurations; at low reasoning they
  also avoided more final merge/classification mistakes. Both arms retained
  safe artifact selection and did not falsely claim static/live agreement.
- Treat that result as a replicated, configuration-bounded interpretation and
  attribution qualifier, not evidence of general client, model, provider,
  repository, or reasoning-level superiority. Further replication on the same
  client and target is not an immediate priority unless a materially different
  condition is preregistered.
- Keep natural-surface evidence separate from those controlled composition
  results. In two source/build-solvable decisions, valid specialized arms did
  not materially improve the primary outcome; one combined arm selected
  semantic CLI evidence and improved provenance wording without changing the
  decision, while no arm selected MCP. This narrows where `READY` applies but
  does not erase the controlled positive evidence or establish general
  redundancy.
- Continue comparing coherent operations with direct use of the same compiler,
  test, IDE/LSP, and MCP primitives.
- Preserve the frozen current Cats Effect JVM/JS shared-source qualification:
  explicit 3.3.7 target-aware point-evidence v4 reproduced the prepared JVM/JS
  targets with unchanged selected outputs, while missing-output and
  multi-project controls remained explicitly partial and non-compiling. This
  is not general Scala.js, complete classpath, primitive-information, or
  IDE/LSP evidence.
- Preserve the bounded cats-tagless v5 qualification: the explicit opt-in
  follows only `thisProject.dependencies`, admits a small fail-closed
  Compile-to-Compile mapping subset, includes only already-present same-axis
  internal class directories, and remains `PartialExistingCompileOutputs`.
  The exact annotation-class recovery, missing-output omission, and cross-axis
  controls are not arbitrary classpath completeness, option/plugin replay, or
  a claim that every sbt configuration is modeled.
- Treat the post-compile TASTy lane as a current illustration of composition
  and provenance value: it normalizes selected-build ownership, point mapping,
  source/artifact identity, freshness, uncertainty, and no-replay boundaries.
  The frozen comparison does not by itself strengthen the current `READY`
  composition/orchestration state.
- Measure correctness, incorrect edits, tool turns, redundant calls, evidence
  attribution, and human intervention.
- Do not treat fewer commands or tool invocation alone as product value.

## Target-language compatibility

- Preserve the command-class boundary established by the Scala 2.13.18 / Scala
  3.3.8 JDK 21 fixture matrix.
- Keep build/test delegation, SemanticDB, syntax-first, Presentation Compiler,
  reconciliation/composition, and MCP compatibility claims separate.
- Treat common-syntax Scala 2 success through the pinned Scala 3.3 presentation
  compiler as bounded evidence, not general Scala 2 compiler support.
- Preserve the first real-project Stage-A matrix on
  `scala/scala-java8-compat` at exact commit
  `a95da8c799baf6a9aea1ef539de8120ee0fbbbed`: its unchanged Scala 2.13.18 root
  passed build-oracle, syntax-first, bounded dynamic, degraded point-evidence,
  and exact-eight MCP lanes, while its checked-in build produced no SemanticDB.
- Preserve the complementary Stage-A matrix on `scalacenter/scalafix` at exact
  commit `240c72018b0d29311c127f24d2d39c093ec018fc`: its bounded Scala 2.13.18
  `core2_13` production row produced target-owned SemanticDB and passed static
  symbols, syntax-first analysis, bounded dynamic lookup, exact reconciliation,
  complete point evidence, and exact-eight MCP projection. Its direct row
  compile passed. Current alpha-3 SNAPSHOT development can now select that row
  through the CLI and exact-eight MCP adapter; immutable alpha-2 cannot.
- Treat both projects as bounded partial evidence. The concrete routing gap is
  closed in current development, but older macro/plugin-heavy stress targets
  still need separate evidence so build shape or ecosystem bitrot is not
  mistaken for semantic compatibility.
- Preserve the bounded sbt-generation matrix: disposable sbt 2.0.6 and 2.0.7
  multi-project fixtures pass selected compile/errors/test, structured counts,
  fail-closed unknown-project routing, sbt-backed classpath acquisition, and
  exact-version TASTy; frozen Macro-Paradise sbt 1.12.15,
  scala-newtype-compat sbt 2.0.6, and Chimney sbt 2.0.7 rows preserve their
  admitted evidence. Every request owns its foreground sbt lifecycle. This
  qualifies those rows only and does not establish general sbt 2,
  build-plugin, compiler-plugin, or target-PC compatibility.
- Admit broader compatibility only from concrete version-specific fixtures or
  real projects; do not infer it from the harness implementation language.

## Agent skill and adoption

- Maintain the generated Agent Plugins 1.0 package for the canonical skill and
  exact-eight MCP server without making client wrappers canonical.
- Preserve the verified one-candidate public shorthand/direct-path install into
  Codex and Claude project skill directories while keeping thin native wrappers
  non-canonical.
- The historical wrapper case was source-sufficient under the policy, so its
  former mandatory effect-summary oracle is not maintained as a miss. The
  historical regression is the broader point-evidence choice on the narrow
  symbol case.
- Preserve the corrected selection boundary. In a controlled generated-fact,
  narrow-symbol, and source-sufficient comparison, skill-guided runs were
  cleaner in some cells but did not select the required CLI-only TASTy route;
  both arms scored `2/3` on primary routing. This is no material routing gain,
  not a general skill-effectiveness result.
- Keep adoption effectiveness `EVIDENCE_PARTIAL`. Do not automatically retune
  the skill from this result; require a materially different evaluation
  condition and broader independent execution evidence before READY.
- Preserve the natural-selection qualifier: direct calibration established MCP
  callability, but treatments made no MCP calls, while one combined condition
  selected the semantic CLI and
  gained clearer provenance without a primary-score gain. The independent
  semantic-only condition exceeded its operation budget twice. One repetition
  relocated a repository-only thin wrapper contrary to the documented canonical
  install route, so do not label that routing failure intrinsic skill or product
  complexity.
- Separate capability value from the incremental effect of skill guidance.

## Installation and packaging

- Maintain the exact-eight modular Maven graph, current Central publishing
  architecture, final group `com.github.dmytromitin`, exact-version
  two-application Coursier source, explicit legacy-group rejection, and
  disposable install/update/uninstall/reproducibility proof.
- Preserve the technical dispositions for all 16 unique flagged runtime rows,
  including the owner-selected Apache-2.0 path for JNA 5.14.0; keep external
  publication, tag, and Release actions under separate explicit authority.
- Keep deterministic assembly, official-schema checks, canonical-skill byte
  identity, and relocated CLI-backed MCP smoke reproducible.
- Maintain a clean external source-installation walkthrough through a
  documented project-local or session-local MCP client route.
- Establish a separate conformant Agent Plugins package-load route when an
  installed client documents safe local or disposable loading.
- Keep the public Maven/Coursier route independently reproducible without
  turning the application into a promised embeddable library API.
- Preserve clean-environment CLI and generic stdio MCP validation; skill
  activation remains a separate adoption dimension.
- Keep the product independent of private control or orchestration material.

## Reproducible benchmarking

- Maintain the standalone allowlist of public protocols, fixtures, validators,
  and bounded results under `benchmarks/`.
- Keep raw client/controller evidence separate from claim-supporting public
  data.
- Re-run the deterministic evidence gate and full test suite when the allowlist
  changes.
- Require a separately designed controlled comparison before making stronger
  effectiveness or superiority claims.

## Public-alpha readiness

- Keep the current product tree and external agent contract free of private
  controller chronology and machine-specific workflow dependencies.
- Maintain the selected Apache-2.0 license and the focused contribution,
  security, changelog, and release/versioning policies.
- Re-run current-tree and clean-history privacy review whenever candidate bytes
  change.
- Preserve the original mixed history privately and keep only audited clean
  refs in the public product repository.
- Reuse the verified clean-history validation gates for future public source
  changes; never attach the mixed historical object graph to public refs.
- Keep exact source-release identity reproducible against the immutable Maven
  primary manifest before any separately authorized tag or GitHub Release.

Public-alpha readiness, external installation, semantic utility, composition
utility, target compatibility, skill adoption, benchmark reproducibility, and
publication safety are independent gates. See
[`docs/project-status.md`](docs/project-status.md).
