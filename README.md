# scala-semantic-harness

Experimental semantic tooling for Scala and functional-programming projects
used by coding agents.

The harness is a bounded semantic evidence layer, not a replacement for the
Scala compiler, sbt, tests, Metals, or other IDE/LSP tooling. Compiler, build,
and test results remain the final correctness oracle. See
[`docs/project-status.md`](docs/project-status.md) for current evidence and
readiness limits and
[`docs/semantic-tooling-positioning.md`](docs/semantic-tooling-positioning.md)
for the product boundary. Technical evaluators can use
[`docs/early-feedback.md`](docs/early-feedback.md) to report a concrete
real-project comparison.

The current tree is the standalone experimental public-alpha source product
under the [Apache-2.0 license](LICENSE). It was published from an independently
constructed, audited clean root followed only by reviewed public-product
commits. The earlier mixed development history is retained separately in a
private archive and is not part of this public repository.

Mutable `main` reports post-release development version
`0.1.0-alpha.3-SNAPSHOT`. The immutable `0.1.0-alpha.2` lightweight Git tag and
GitHub prerelease identify the source that reproduced all 32 primary files
already published on Maven Central byte-for-byte. The supported packaged route
remains the exact alpha-2 Maven/Coursier application route; no alpha-3 artifact,
channel, tag, or GitHub Release exists.

## Agent quick start

The supported packaged route is exact `0.1.0-alpha.2` on JDK 21. Install the
CLI and generic stdio MCP server first:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json \
  semantic-scala semantic-scala-mcp
semantic-scala version
```

Then choose the integration that the agent client supports: the complete CLI,
the curated exact-eight MCP projection, and/or the immutable alpha-2 agent
skill. Copying this repository's thin skill wrappers into another project is
not supported; install the canonical skill from the `0.1.0-alpha.2` tag.

[`docs/agent-onboarding.md`](docs/agent-onboarding.md) gives copy-ready Codex,
Claude Code, Cursor, and VS Code/Copilot recipes, exact local qualification
statuses, skill installation, the CLI/MCP surface matrix, and troubleshooting.
Alpha-3 SNAPSHOT-only project and target-JDK selectors are explicitly excluded
from the alpha-2 packaged contract.

## What is included

- structured compile, test, and diagnostic reports;
- SemanticDB inventory, coverage, symbol, and exact-symbol usage evidence;
- bounded Presentation Compiler symbol and type queries;
- reconciliation of dynamic compiler evidence with an explicit SemanticDB
  artifact;
- a public point-evidence composition that preserves source-artifact discovery,
  safe selection, live symbol evidence, and conditional reconciliation;
- an alpha-3 SNAPSHOT CLI-only, same-request post-compile TASTy point-evidence
  operation with exact stable Scala 3 child-inspector provenance;
- bounded alpha-3 SNAPSHOT sbt-backed command, classpath, and TASTy-receipt
  compatibility proven on sbt 1.12.15 and 2.0.6 fixtures;
- conservative syntax-first FP effect summaries;
- a stdio MCP server exposing exactly eight public tools;
- small external example projects and benchmark infrastructure; and
- a client-neutral `semantic-scala` agent skill with thin Codex and Claude Code
  wrappers;
- source templates and a deterministic assembler for a self-contained Agent
  Plugins 1.0 package containing that skill and the exact-eight MCP server; and
- a supported, independently qualified exact-eight Maven/Coursier application
  route for exact version `0.1.0-alpha.2`.

## Modules

- `modules/core`: shared JSON models and codecs.
- `modules/cli`: the `semantic-scala` command entry point.
- `modules/sbt-runner`: sbt compile/test subprocess integration.
- `modules/semanticdb-reader`: SemanticDB inventory and usage evidence.
- `modules/presentation-compiler`: bounded dynamic semantic queries.
- `modules/semantic-reconciliation`: static/dynamic symbol comparison and the
  point-evidence composition and reconciliation contracts.
- `modules/fp-analyzers`: syntax-first effect summaries.
- `modules/mcp-server`: CLI-backed MCP stdio adapter.
- `modules/benchmark`: benchmark models and fixtures.

## Build and test

The project uses Scala 3 and sbt. A fresh source setup requires JDK 21, sbt,
Git, and Python 3; CI uses Temurin JDK 21. A newer local JDK may work, but it is
not the documented baseline.

Scala 3 describes the harness implementation, not a blanket target-language
promise. A bounded JDK 21 matrix has verified build/test/error delegation,
SemanticDB discovery/symbol/usages, and syntax-first effect summaries on Scala
2.13.18 and Scala 3.3.8 fixtures. Presentation Compiler operations still use
the pinned Scala 3.3 compiler: they resolved the matrix's shared-syntax Scala 2
points, but this is not general Scala 2 dialect or compiler support.
Reconciliation and point evidence inherit that dynamic-source limitation. See
[`docs/project-status.md`](docs/project-status.md) and
[`docs/semantic-api.md`](docs/semantic-api.md) for the exact boundary.

Two maintained real-project Stage-A checks now cover frozen Scala 2.13.18
revisions without source or build changes. `scala/scala-java8-compat` produced
no SemanticDB, so its otherwise-passing alpha-2 matrix preserved truthful
degraded point evidence. A bounded production row of `scalacenter/scalafix`
produced target-owned SemanticDB; static symbol discovery, bounded dynamic
lookup, exact static/dynamic reconciliation, complete point evidence, and the
ordered exact-eight MCP projection passed. Scalafix's aggregated sbt build also
exposed that the alpha-2 build oracle cannot select one project row. Current
alpha-3 SNAPSHOT development closes that routing gap with an optional validated
project selector; the immutable alpha-2 distribution remains unchanged. These
two projects are complementary bounded evidence, not general Scala 2 support or
semantic superiority.

The alpha-3 SNAPSHOT sbt subprocess boundary now sends project selection plus
one product-owned task as a single fixed command sequence. Its injected
classpath/receipt adapters use sbt's `fileConverter` for sbt 2 virtual
references and preserve sbt 1 file-backed entries. Readable extensionless sbt 2
CAS JARs are copied directly, without cache scanning, into an owner-only
content-addressed area under the selected workspace's generated `target` tree.
A disposable sbt 2.0.6 fixture, a disposable sbt 2.0.7 multi-project fixture,
frozen sbt 1.12.15 and sbt 2.0.6 plugin projects, and a frozen Chimney sbt
2.0.7 / Scala 3.8.4 selected row pass their bounded gates. The shared runner
uses a request-owned foreground sbt server lifecycle, and structured sbt suite
counters preserve ignored/skipped tests in the existing Test JSON fields. This
is version-specific evidence, not universal sbt 2 or compiler-plugin
compatibility. Chimney's macro-heavy PC points remain neutrally unresolved
because target compiler options and plugins are not replayed.

```bash
sbt -batch test
sbt cli/stage
sbt mcpServer/stage
```

The source-checkout wrapper runs the CLI through sbt:

```bash
./semantic-scala --help
./semantic-scala version
./semantic-scala compile --json
./semantic-scala compile --sbt-project core2_13 --json
./semantic-scala compile --sbt-project plugin --sbt-java-home /absolute/path/to/installed-jdk --json
./semantic-scala test --json
./semantic-scala errors --json
```

`compile`, `errors`, and `test` accept an optional `--sbt-project <id>` where
the ID matches `[A-Za-z][A-Za-z0-9_-]*`. Without it they preserve ordinary root
behavior. With it, compile/errors run that project's fixed `Compile` scope and
test runs its fixed `Test` scope. The selector is not arbitrary sbt syntax, and
a successful selected invocation proves only that bounded project operation,
not whole-workspace correctness.

All six sbt-backed forms (`compile`, `errors`, `test`, sbt-backed
`infer-type`, `infer-type-batch`, and `tasty-point-evidence`) also accept an
optional `--sbt-java-home <absolute-directory>`. The harness itself remains on the
supported JDK 21 runtime; only the target sbt child receives the selected
canonical `JAVA_HOME` and a matching `PATH` prefix. The home must already be
installed and pass bounded validation and a fixed version probe. The harness
does not discover, download, install, or globally select JDKs. Omitting the
flag preserves inherited-Java behavior. Selected-JDK classpath acquisition is
isolated from no-selector cache reuse, and public result schemas do not expose
the home or probe evidence.

For repeated use, prefer the staged launcher at
`modules/cli/target/stage/bin/semantic-scala`.

## Maven/Coursier distribution

The source-build route above remains supported and externally verified. The
exact eight-module `0.1.0-alpha.2` runtime is also published under final group
`com.github.dmytromitin` on Maven Central, and its complete public repository
shape has been verified against the reviewed bytes. A fresh outsider-like JDK
21 environment independently installed both applications from the actual
project-owned raw GitHub URL, exercised the CLI and generic stdio MCP runtime,
updated through retained public-channel metadata, and uninstalled cleanly.
That exact Maven Central plus Coursier URL route is therefore supported.

The Central publication contains exactly the eight implementation modules,
never the root aggregate or benchmark, and the public channel uses
exact-version descriptors for the
distinct `semantic-scala` CLI and `semantic-scala-mcp` server applications.
JDK 21 and Coursier are runtime/install prerequisites. Target-workspace sbt is
needed by build-oracle commands such as `compile`, `errors`, and `test`; it is
not required merely to install the applications or for every read-only
semantic command. The Maven modules are application implementation artifacts,
not a supported embeddable-library API or binary-compatibility promise.

Install only the CLI:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json \
  semantic-scala
```

Or install the CLI and stdio MCP server together:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json \
  semantic-scala semantic-scala-mcp
```

Use `semantic-scala-mcp` as the generic stdio MCP command with the target
workspace as its working directory. See
[`docs/distribution.md`](docs/distribution.md) for Coursier setup, updates,
uninstall, commit-pinned channel reproduction, and the current qualification
boundary.

## Semantic commands

```bash
./semantic-scala semanticdb-status --workspace . --json
./semantic-scala semanticdb-coverage --workspace . --json
./semantic-scala semanticdb-for-source --file src/main/scala/example/Main.scala --workspace . --json
./semantic-scala point-evidence --file src/main/scala/example/Main.scala --workspace . --line 6 --col 16 --json
./semantic-scala tasty-point-evidence --workspace . --sbt-project app --file src/main/scala/example/Main.scala --line 6 --col 16 [--sbt-java-home /absolute/path/to/installed-jdk] --json
./semantic-scala symbols --semanticdb path/to/Main.scala.semanticdb --json
./semantic-scala usages --workspace . --manifest semantic-usages.json --symbol 'example/Foo#bar().' --json
./semantic-scala symbol-at --file path/to/Main.scala --line 6 --col 16 --json
./semantic-scala infer-type --file path/to/Main.scala --line 6 --col 16 --json
./semantic-scala infer-type-batch --requests batch-request.json --workspace . --sbt-project core --sbt-configuration Compile [--sbt-java-home /absolute/path/to/installed-jdk] --json
./semantic-scala reconcile-symbol --file path/to/Main.scala --line 6 --col 16 --semanticdb path/to/Main.scala.semanticdb --json
./semantic-scala effect-summary --file path/to/UserRepo.scala --json
```

All machine-facing commands have JSON output. Build-oracle command exit code
`0` means the CLI operation completed; inspect the JSON `success` field for the
compile or test domain result. Semantic results preserve their scope and
uncertainty: rendered hover text is not canonical identity, artifact presence
is not complete source coverage, and only `ExactMatch` is exact reconciliation.

Detailed contracts:

- [`docs/semantic-api.md`](docs/semantic-api.md)
- [`docs/usages.md`](docs/usages.md)
- [`docs/point-evidence.md`](docs/point-evidence.md)
- [`docs/tasty-point-evidence.md`](docs/tasty-point-evidence.md)
- [`docs/architecture.md`](docs/architecture.md)

## MCP server

The stdio server exposes exactly these tools:

```text
semantic_compile
semantic_errors
semantic_test
semantic_effect_summary
semantic_symbol_at
semantic_symbols
semantic_reconcile_symbol
semantic_point_evidence
```

Build and validate it with:

```bash
sbt cli/stage
sbt mcpServer/stage
scripts/mcp/smoke-mcp-tools.py
```

Copy [`.mcp.example.json`](.mcp.example.json) and replace its placeholder
checkout path for source-development client configuration. Installed alpha-2
users should configure `semantic-scala-mcp` directly. See
[`docs/mcp-client-validation.md`](docs/mcp-client-validation.md) for the public
configuration and protocol checks and
[`docs/agent-onboarding.md`](docs/agent-onboarding.md) for client recipes.

## Agent skill

The canonical client-neutral policy is
[`skills/semantic-scala/SKILL.md`](skills/semantic-scala/SKILL.md). Thin
repository wrappers live at:

- [`.agents/skills/semantic-scala/SKILL.md`](.agents/skills/semantic-scala/SKILL.md)
- [`.claude/skills/semantic-scala/SKILL.md`](.claude/skills/semantic-scala/SKILL.md)

These files are source-tree wrappers, not standalone external installations.
The skill is policy and documentation. It does not add commands, background
services, or automatic invocation. External alpha-2 installation plus client
qualification is in
[`docs/agent-onboarding.md`](docs/agent-onboarding.md); packaging and
maintenance guidance is in
[`docs/agent-skill-semantic-scala.md`](docs/agent-skill-semantic-scala.md).

## Agent Plugin package

After staging the CLI and MCP server, generate a fresh relocatable package:

```bash
sbt cli/stage mcpServer/stage
python3 scripts/package-agent-plugin.py assemble \
  --output target/agent-plugin/semantic-scala
python3 scripts/package-agent-plugin.py validate \
  --plugin-root target/agent-plugin/semantic-scala
```

The output is ignored build material, not a checked-in binary distribution or
release. It targets the Agent Plugins 1.0.0 working draft and bundles the
canonical skill, complete staged CLI, and complete staged exact-eight MCP
server. See [`docs/agent-plugin.md`](docs/agent-plugin.md) for the package
contract, validation level, relocation smoke, and current client-support
limits.

## Examples and benchmarks

Projects under `examples/` are external CLI fixtures rather than members of
the root sbt build. The normal test suite exercises the compile-success and
compile-failure examples through the CLI.

The repository contains a standalone public benchmark subset with methodology,
portable prompts, test-coupled fixtures, deterministic validation, and a
bounded aggregate. Start at [`benchmarks/README.md`](benchmarks/README.md).
Historical raw transcripts and controller automation are not part of that
subset. The admitted evidence does not establish broad superiority or general
benchmark reproducibility beyond its stated small-sample gate.

## Current limitations

- A generated self-contained Agent Plugins package has bounded structural,
  official-schema, determinism, and relocated-runtime evidence, but no
  supported release channel or conformant installed-client adoption proof.
- The MCP surface remains the documented eight-tool stdio adapter.
- Source-paired `semanticdb-for-source`, `point-evidence`, and
  `reconcile-symbol` requests now report snapshot-consistent content freshness.
  Fresh means the captured source content agrees with the captured SemanticDB
  document; it does not mean a build ran or that the whole project compiles.
- Stale SemanticDB remains visible but cannot produce completed reconciliation.
  Unverifiable evidence stays explicit and can complete only as qualified
  evidence. SemanticDB inventory and coverage still do not establish that every
  source is covered or fresh.
- Presentation Compiler renderings are bounded evidence, not whole-project
  compile proof.
- The canonical skill selects `symbol-at` for one exact Presentation Compiler
  declaration question and reserves `point-evidence` for questions where
  artifact discovery/selection and reconciliation are themselves relevant.
  Source-sufficient questions still require no semantic query.
- Public-alpha source readiness is separate from binary distribution,
  installation usability, semantic utility, and skill-adoption evidence.
- The exact `0.1.0-alpha.2` Maven/Coursier application route is independently
  qualified from the actual project-owned public channel URL and Maven
  Central under JDK 21. This does not establish Coursier contrib, MCP Registry,
  MCPB, native/container/npm/PyPI packaging, a stable embeddable-library API,
  broad Scala compatibility, skill adoption, semantic superiority, or 1.0
  stability. The exact alpha-2 source identity is published as a lightweight
  tag and GitHub prerelease with normal generated source archives and zero
  uploaded project assets.
  All 16 formerly flagged license/NOTICE rows are technically dispositioned;
  the owner selected Apache-2.0 for resolver-fetched JNA 5.14.0. This is not
  legal advice or authority for another publication action.
- The public repository contains only the audited clean source history. The
  separate mixed development history remains private and is not a release or
  installation channel.

See [`ROADMAP.md`](ROADMAP.md) for product-oriented next steps.

Current development is validation-first: test real Scala projects, preserve
compatibility boundaries, and admit features only from concrete gaps. Real
project reports are welcome using the bounded comparison packet in
[`docs/early-feedback.md`](docs/early-feedback.md), especially missing
decision-relevant evidence or materially useful composition of compiler,
build/test, IDE/LSP, and artifact facts. Alpha-2 remains the supported packaged
release while `main` is alpha-3 SNAPSHOT development; no alpha-3 date or
publication is promised.

## Project policies

- [Contributing](CONTRIBUTING.md)
- [Security reporting](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Release and versioning](RELEASE.md)
- [Apache-2.0 license](LICENSE)
