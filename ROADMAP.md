# Roadmap

This roadmap is organized by public product outcomes. It is not an internal
execution ledger and does not promise delivery dates.

## Implemented foundation

- Scala 3 multi-module build with structured CLI reports.
- Compile, test, and diagnostic build-oracle commands.
- SemanticDB inventory, coverage, symbols, and bounded usages.
- Presentation Compiler symbol and rendered-type queries.
- Static/dynamic symbol reconciliation.
- A public semantic point-evidence composition that selects only one parsed
  source artifact and preserves non-selection reasons.
- Syntax-first FP effect summaries.
- A CLI-backed stdio MCP server with exactly eight tools.
- Public semantic-scala skill policy and repository wrappers.
- Examples, CI, benchmark fixtures, and methodology.

## Near-term priorities

1. Preserve exact `0.1.0-alpha.2` release coherence: the prepared source
   candidate reproduces all 32 immutable Maven Central primaries, while the
   deterministic two-application public URL channel remains pinned to those
   bytes. A Git tag or GitHub Release remains a separate authorization.
2. Preserve the qualified canonical-skill install route and corrected
   `symbol-at` versus `point-evidence` boundary. The independent frozen
   replication preserved no-tool restraint and composition selection but left
   a client-mediated narrow-symbol execution gap; invocation or stated intent
   alone is not adoption value.
3. Invite early technical feedback on the experimental source alpha and use
   real installation and project cases to guide maintenance.
4. Validate compatibility on a maintained, reproducible real Scala 2.13
   project before moving to harder legacy macro/plugin targets.
5. Admit new semantic capabilities only from concrete user, adoption,
   compatibility, or benchmark gaps that strong ordinary tooling does not
   already address adequately.

## Semantic evidence quality

- Keep facts owned by the narrowest appropriate compiler, build, test,
  Presentation Compiler, or SemanticDB surface.
- Preserve provenance, freshness, coverage, disagreement, and uncertainty in
  public results.
- Add capabilities only after a decision-relevant gap is demonstrated against
  strong ordinary tooling.
- Keep resource and output bounds explicit and tested.

## Composition and orchestration

- Maintain the admitted CLI plus thin-MCP point-evidence shape and measure its
  decision value against direct use of the same underlying operations.
- Continue comparing coherent operations with direct use of the same compiler,
  test, IDE/LSP, and MCP primitives.
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
- Next validate the capability classes separately on a maintained,
  reproducible real Scala 2.13 project. Treat older macro/plugin-heavy projects
  as later stress targets so dependency or build bitrot is not mistaken for a
  harness defect.
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
- Preserve the corrected selection boundary. In the independent replication,
  C stated the narrow `symbol-at` choice but did not complete its configured
  MCP call; B and C therefore both scored `2/3` on appropriate selection or
  non-selection. Require broader independent execution evidence before READY.
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
