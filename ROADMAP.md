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

1. Close the exact Maven dependency license/NOTICE human-review gate, then
   separately authorize and validate external publication of the implemented
   CLI/MCP Coursier route. The current verified source-build route remains
   available, and the local candidate is not packaged-distribution readiness.
2. Improve skill and plugin discoverability, then test whether agents select
   and use the installed harness appropriately. Invocation alone is not
   adoption value.
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
- Test appropriate spontaneous selection, non-selection, and resistance to
  misuse or overuse once the runtime is cleanly installable and a documented
  client integration route is available.
- Investigate current cross-agent skill directories and current Codex/OpenAI
  and Claude packaging mechanisms while keeping one canonical skill with thin
  integration wrappers.
- Separate capability value from the incremental effect of skill guidance.

## Installation and packaging

- Maintain the exact-eight modular Maven graph, current Central publishing
  architecture, exact-version two-application Coursier source, and disposable
  install/update/uninstall/reproducibility proof.
- Resolve every flagged multiple-license, EPL-family, missing/ambiguous
  license, and possible NOTICE/attribution item through human prepublication
  review before an externally authorized release.
- Keep deterministic assembly, official-schema checks, canonical-skill byte
  identity, and relocated CLI-backed MCP smoke reproducible.
- Maintain a clean external source-installation walkthrough through a
  documented project-local or session-local MCP client route.
- Establish a separate conformant Agent Plugins package-load route when an
  installed client documents safe local or disposable loading.
- Add the separately authorized public Maven/Coursier release channel without
  turning the application into a promised embeddable library API.
- Validate CLI, MCP, and skill activation from a clean environment.
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

Public-alpha readiness, external installation, semantic utility, composition
utility, target compatibility, skill adoption, benchmark reproducibility, and
publication safety are independent gates. See
[`docs/project-status.md`](docs/project-status.md).
