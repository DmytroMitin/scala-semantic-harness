# Changelog

Notable public changes will be recorded in this file.

The project is an experimental alpha. Source availability alone does not imply
a supported binary, package-manager, marketplace, or client-installation
release.

## Unreleased

No user-facing changes are recorded after the alpha-3 release candidate.

## 0.1.0-alpha.3 (release candidate)

The exact eight-module candidate is published on Maven Central and selected by
the checked-in Coursier channel. Its local fresh-cache channel-to-Central proof
passed, but actual public raw-URL qualification, a Git tag, and a GitHub Release
remain pending. Exact `0.1.0-alpha.2` remains the supported packaged route.

- **Additive:** add validated `--sbt-project` selection to compile, test,
  errors, target-aware SemanticDB/source mapping, point evidence, and
  sbt-backed type/TASTy operations. Add `--sbt-java-home` to the eight
  sbt-backed forms and `--sbt-scala-version` to target-aware source mapping and
  point evidence. These selectors remain bounded target-build facts.
- **Explicit prerelease compatibility change:** the existing no-target
  `semanticdb-for-source`, `reconcile-symbol`, and `point-evidence` JSON/MCP
  results move from schema v1 to v2. The v2 payloads add source freshness and
  typed reconciliation outcomes, so consumers must update accepted schema IDs
  and decode the v2 shapes; alpha-3 does not emit the v1 shapes for these
  routes. The CLI commands and corresponding members of the exact-eight MCP
  registry remain available.
- **Additive:** add target-aware source mapping v3/v4 and point-evidence v4 on
  top of the new snapshot-consistent source-content freshness model.
- **Additive:** add point-evidence v5 as an explicit
  `--include-existing-internal-outputs` opt-in for already-present same-axis
  internal Compile outputs. Add strict v6 behind the further
  `--require-fresh-internal-outputs` opt-in; only Fresh internal outputs may
  contribute, while Stale and Unverifiable outputs are typed and excluded
  without compiling them.
- **Additive:** add CLI-only `tasty-point-evidence`, which owns one selected
  Compile and inspects receipt-bound TASTy using the exact stable target Scala
  3 line. It is intentionally absent from MCP, whose ordered registry remains
  exactly eight tools.
- **Compatible behavior correction:** add sbt 2-compatible virtual-file
  materialization, selected-row command sequencing, foreground server
  lifecycle handling, structured test-count preservation, and the isolated
  sbt global-base fix. Maintained sbt 1 behavior remains covered.
- **Explicit prerelease compatibility change:** move the harness build and
  linked Presentation Compiler host from Scala 3.3.3 to Scala 3.9.0. Dynamic
  point renderings remain version-dependent bounded evidence; this is not a
  blanket target-language compatibility promise.
- **Internal-only:** move strict-v6 Zinc 1.12.1 reading into one on-demand
  bounded JDK 21 worker per request. The worker JAR and its frozen 42-artifact
  dependency inventory are embedded/acquired by the existing applications;
  the worker is not a ninth Maven module. First uncached v6 use may contact
  Maven Central, and cold offline failure remains fail-closed.
- **Internal-only:** semantic-reconciliation now declares the existing
  sbt-runner module plus Coursier interface `1.0.18`; the CLI continues to
  declare Coursier interface `1.0.28`, while the resolved Scala-3.9/PC runtime
  graph selects `1.0.29-M4`. These are packaging/runtime dependency changes,
  not new public GAVs.
- **Internal-only:** retain exactly the existing eight publishable application
  implementation modules under `com.github.dmytromitin`; root, benchmark,
  Zinc worker, and generated Agent Plugin packages remain unpublished.
- **Documentation-only:** add agent onboarding and early-feedback workflows,
  synchronize bounded evidence/readiness wording, and document the new target,
  freshness, TASTy, Scala 3.9, and sbt lifecycle boundaries without upgrading
  partial semantic-value or compatibility evidence.

## 0.1.0-alpha.2

- Publish the exact eight application implementation modules under final group
  `com.github.dmytromitin` on Maven Central with POM, main, sources,
  documentation, signature, and checksum files; retain explicit rejection of
  the provisional group and the reviewed Apache-2.0 path for JNA 5.14.0.
- Add and independently qualify the exact-version project-owned Coursier URL
  route under JDK 21 for CLI and generic stdio MCP installation, runtime,
  update, and uninstall.
- Publish lightweight Git tag and non-draft GitHub prerelease
  `0.1.0-alpha.2` at the exact source identity that reproduced all 32 public
  Maven Central primary files byte-for-byte. The Release contains normal
  GitHub-generated source archives and zero uploaded project assets.

## 0.1.0-alpha.1

This is the first experimental, source-only alpha. It does not publish
project-built binaries, package-manager coordinates, containers, marketplace
artifacts, or a generated Agent Plugin bundle.

- Add structured compile, test, and compiler-diagnostic commands.
- Add bounded SemanticDB discovery, coverage, symbol, usage, and source lookup
  evidence.
- Add Presentation Compiler point/type evidence, dynamic/static symbol
  reconciliation, and coherent `point-evidence` composition.
- Add conservative syntax-first effect summaries.
- Provide exactly eight CLI-backed MCP tools while keeping additional
  inspection and utility commands CLI-only.
- Provide the canonical semantic-scala skill, thin client wrappers, and a
  deterministic, relocatable Agent Plugin package source/validator without
  claiming client adoption or publishing the generated bundle.
- Document the JDK 21 source-build and first-use route, the capability-specific
  Scala 2/Scala 3 boundary, and the clean-history Apache-2.0 public source
  publication.
