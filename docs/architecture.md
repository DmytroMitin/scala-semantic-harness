# Architecture

## Layers

```text
AI agent
  |
  | CLI, MCP, or generated Agent Plugin
  v
Scala Semantic Harness
  |
  +-- compiler / build / tests
  +-- SemanticDB
  +-- Presentation Compiler
  +-- bounded FP analyzers
```

## CLI-first boundary

The CLI owns public commands and versioned JSON contracts. The MCP server is a
thin stdio adapter that delegates to those CLI contracts and keeps transport
metadata outside domain payloads. The production MCP registry contains exactly
eight tools; CLI-only capabilities do not become MCP tools implicitly.

The product has no build, test, release, skill, MCP, or user-flow dependency on
private control repositories or controller history.

## Target-language compatibility boundary

The harness is implemented in Scala 3, while target compatibility is
capability-specific. Build/test commands delegate to the target's own build.
SemanticDB readers consume explicit version-appropriate artifacts. Syntax-first
analysis depends on accepted source syntax. Dynamic symbol/type operations use
the harness's pinned Scala 3.3 presentation compiler rather than a native Scala
2 compiler.

A bounded JDK 21 fixture matrix verified build/test/error, SemanticDB,
effect-summary, and exact-eight MCP projection on Scala 2.13.18 and Scala 3.3.8.
The common-syntax Scala 2 points also resolved dynamically and reconciled
exactly, but that narrow result does not establish general Scala 2 syntax,
classpath, macro, compiler-plugin, or project compatibility. Composition
inherits the limits of every evidence source it uses.

## Packaging boundary

The primary runtime candidate is an exact eight-module thin Maven dependency
graph plus two exact-version Coursier applications. Root and benchmark cannot
publish. The CLI and MCP applications remain distinct; the MCP server resolves
and delegates to an installed `semantic-scala` sibling through a bounded,
validated PATH contract. These modules are application implementation
artifacts, not a public embeddable-library API.

The Agent Plugins source templates and assembler package existing policy and
capability; they do not own semantic behavior. A generated plugin copies the
canonical skill byte-for-byte and the complete relocatable CLI/MCP stage trees.
Its MCP configuration still delegates every tool call to the bundled CLI and
does not widen the exact-eight registry.

The Maven/Coursier candidate is locally implemented, not externally published.
Generated distributions are ignored build outputs. Root `plugin.json`, root
`mcp.json`, `${PLUGIN_ROOT}` expansion, and `skills/*/SKILL.md` discovery are
the portable core. Repository `.agents` and `.claude` wrappers remain separate
compatibility surfaces. Client installation, permissions, and release channels
are outside this architecture.

## Evidence ownership

Use the narrowest stable owner for each fact:

- compiler, build, and test output own whole-invocation success and failure;
- Presentation Compiler queries provide bounded point-in-time renderings;
- SemanticDB provides canonical symbols and occurrences from explicit compiled
  artifacts;
- reconciliation compares one dynamic point result with one explicit static
  artifact without hiding disagreement;
- the public point-evidence composition retains the complete source-artifact
  discovery report, one live point result, and reconciliation only when exactly
  one parsed artifact is justified; and
- effect summaries describe declared syntax without claiming compiler-selected
  semantics.

Artifact availability is not complete source coverage. Rendered names are not
canonical symbol identity. A successful point query is not whole-project
compile proof. These limits remain visible in schemas and documentation.

## Stateless bounded operations

Public operations take explicit files, workspaces, manifests, positions, or
build contexts. Paths are validated and outputs are bounded. Commands avoid
hidden persistent state; any explicit cache mode must report its evidence and
must not silently refresh a requested reuse operation.

`PointEvidenceService` is an internal composition seam in
`semantic-reconciliation`. It does not define a CLI command, JSON schema, or
MCP tool. Ambiguous, partial, unparseable, unavailable, and unmatched artifact
states remain explicit and never trigger candidate selection. Live unresolved
and unavailable states remain distinct, and mismatch is not labeled as
freshness evidence.

The usages operation aggregates exact ordinary SemanticDB occurrences over an
explicit manifest. It keeps mapping, coverage, duplicate, freshness, and
resource evidence separate from occurrence results. Its cooperative deadline
is not a hard JVM wall-clock or memory guarantee.

## Modules

- `core`: shared data models and JSON codecs.
- `cli`: command parsing, orchestration, and JSON rendering.
- `sbt-runner`: compile/test subprocess integration.
- `semanticdb-reader`: artifact inventory, symbols, coverage, and usages.
- `presentation-compiler`: bounded point and rendered-type queries.
- `semantic-reconciliation`: dynamic/static symbol comparison.
- `fp-analyzers`: conservative syntax-first effect summaries.
- `mcp-server`: CLI-backed stdio transport.
- `benchmark`: reproducible case models and fixtures.
