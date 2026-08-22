# scala-semantic-harness

Experimental semantic tooling for Scala and functional-programming projects
used by coding agents.

The harness is a bounded semantic evidence layer, not a replacement for the
Scala compiler, sbt, tests, Metals, or other IDE/LSP tooling. Compiler, build,
and test results remain the final correctness oracle. See
[`docs/project-status.md`](docs/project-status.md) for current evidence and
readiness limits and
[`docs/semantic-tooling-positioning.md`](docs/semantic-tooling-positioning.md)
for the product boundary.

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

## What is included

- structured compile, test, and diagnostic reports;
- SemanticDB inventory, coverage, symbol, and exact-symbol usage evidence;
- bounded Presentation Compiler symbol and type queries;
- reconciliation of dynamic compiler evidence with an explicit SemanticDB
  artifact;
- a public point-evidence composition that preserves source-artifact discovery,
  safe selection, live symbol evidence, and conditional reconciliation;
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
./semantic-scala test --json
./semantic-scala errors --json
```

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
./semantic-scala symbols --semanticdb path/to/Main.scala.semanticdb --json
./semantic-scala usages --workspace . --manifest semantic-usages.json --symbol 'example/Foo#bar().' --json
./semantic-scala symbol-at --file path/to/Main.scala --line 6 --col 16 --json
./semantic-scala infer-type --file path/to/Main.scala --line 6 --col 16 --json
./semantic-scala infer-type-batch --requests batch-request.json --workspace . --sbt-project core --sbt-configuration Compile --json
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
checkout path for project-scoped client configuration. See
[`docs/mcp-client-validation.md`](docs/mcp-client-validation.md) for the public
configuration and protocol checks.

## Agent skill

The canonical client-neutral policy is
[`skills/semantic-scala/SKILL.md`](skills/semantic-scala/SKILL.md). Thin
repository wrappers live at:

- [`.agents/skills/semantic-scala/SKILL.md`](.agents/skills/semantic-scala/SKILL.md)
- [`.claude/skills/semantic-scala/SKILL.md`](.claude/skills/semantic-scala/SKILL.md)

The skill is policy and documentation. It does not add commands, background
services, or automatic invocation. Packaging and maintenance guidance is in
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
- Point evidence is bounded to one contained source position and existing
  artifacts; it does not assess freshness or replace compilation and tests.
- SemanticDB commands inspect existing artifacts; they do not establish that
  every source is covered or fresh.
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

## Project policies

- [Contributing](CONTRIBUTING.md)
- [Security reporting](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Release and versioning](RELEASE.md)
- [Apache-2.0 license](LICENSE)
