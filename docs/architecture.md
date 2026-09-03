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
analysis depends on accepted source syntax. The harness is compiled with Scala
3.9.0, and dynamic symbol/type operations use its linked Scala 3.9.0
Presentation Compiler rather than the target compiler or a native Scala 2
compiler. Build/test delegation still uses the selected target compiler.
Static SemanticDB evidence reads target artifacts. The separate CLI-only
post-compile TASTy lane first owns one target `Compile` request, then compiles
and runs a product-owned inspector against the acquired exact stable Scala 3
line in bounded child JVMs. Neither dynamic point queries nor the inspector
replay target compiler options or plugins.

A bounded JDK 21 fixture matrix verified build/test/error, SemanticDB,
effect-summary, and exact-eight MCP projection on Scala 2.13.18 and Scala 3.3.8.
The common-syntax Scala 2 points also resolved dynamically and reconciled
exactly, but that narrow result does not establish general Scala 2 syntax,
classpath, macro, compiler-plugin, or project compatibility. Composition
inherits the limits of every evidence source it uses.

The harness runtime remains supported on JDK 21. Its six sbt-backed
CLI forms can explicitly select one already-installed target Java home for the
child sbt process. The shared selector validates and probes that home, sets
only child `JAVA_HOME`/`PATH`, and never discovers or installs a JDK. The three
process owners are build-oracle execution, classpath acquisition, and the
private same-request TASTy receipt task. No-selector
classpath acquisition remains v1-compatible, while explicit-Java acquisition
uses an isolated strict v2 cache/protocol context.

All three sbt subprocess owners use one closed product-owned command grammar:
optional validated project selection followed by exactly one fixed task in a
single sbt command argument. Every request launches one foreground
`sbt --server --batch` process with a fresh global base and a short
request-owned runtime/socket directory; it cannot attach to ambient server
state. Timeouts terminate only the owned process tree, and request settings,
protocols and sockets are removed after completion.

The generated classpath and receipt tasks unwrap
sbt 1 `Attributed[File]` entries directly and convert sbt 2 virtual references
with the build's supported `fileConverter`. If sbt 2 materializes a readable
extensionless CAS JAR inside the request's temporary global base, the harness
copies those exact bytes to an owner-only SHA-256-named JAR under
`target/semantic-scala/sbt-materialized-classpath/v1` before deleting the
temporary base. It does not infer paths from virtual IDs or scan dependency or
build caches. Existing classpath bounds, evidence, cache identity, and public
schemas remain authoritative; ordinary workspace build cleanup owns the
generated materialization area.

The build-oracle Test task consumes the common `Test / executeTests` output and
aggregates sbt's structured `SuiteResult` counters into a bounded request-owned
completion record. Public `total` includes skipped, ignored, canceled, and
pending cases; `passed` includes only successful cases; and `failed` combines
framework failures and errors. Skipped-like cases remain the difference
between those fields. Human console summaries and JUnit XML are not count
sources. This is version-bounded evidence on sbt 1.12.15, 2.0.6, and 2.0.7,
not general sbt compatibility.

## Packaging boundary

The primary runtime candidate is an exact eight-module thin Maven dependency
graph under final group `com.github.dmytromitin` plus two exact-version Coursier
applications. The provisional group is rejected. Root and benchmark cannot
publish. The CLI and MCP applications remain distinct; the MCP server resolves
and delegates to an installed `semantic-scala` sibling through a bounded,
validated PATH contract. These modules are application implementation
artifacts, not a public embeddable-library API.

The Agent Plugins source templates and assembler package existing policy and
capability; they do not own semantic behavior. A generated plugin copies the
canonical skill byte-for-byte and the complete relocatable CLI/MCP stage trees.
Its MCP configuration still delegates every tool call to the bundled CLI and
does not widen the exact-eight registry.

The exact Maven artifacts are public on Central. The project-owned Coursier
URL-channel candidate is locally qualified but not yet published on public
`main` or independently qualified from that public URL.
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
- post-compile TASTy point evidence owns selected-Compile success, source
  stability, receipt-bound artifact hashes, exact-inspector provenance, and a
  deterministic smallest-containing-tree selection; and
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

The v2 point-evidence and reconciliation services are request-local composition
seams in `semantic-reconciliation`. They capture source/artifact content once,
give captured source text to the Presentation Compiler, and recheck source
identity before returning. Ambiguous, partial, unparseable, unavailable,
unmatched, stale, and source-changed states remain explicit. Stale evidence may
coexist with a live result but never triggers completed reconciliation;
unverifiable evidence can complete only with an explicit qualification.

The usages operation aggregates exact ordinary SemanticDB occurrences over an
explicit manifest. It keeps mapping, coverage, duplicate, freshness, and
resource evidence separate from occurrence results. Its cooperative deadline
is not a hard JVM wall-clock or memory guarantee.

## Modules

- `core`: shared data models and JSON codecs.
- `cli`: command parsing, orchestration, and JSON rendering.
- `sbt-runner`: root or validated-project compile/test subprocess integration,
  shared target-Java validation, sbt 1/2 path materialization for v1/v2
  classpath acquisition, and the private fixed-Compile TASTy receipt protocol.
- `semanticdb-reader`: artifact inventory, symbols, coverage, and usages.
- `presentation-compiler`: bounded point and rendered-type queries.
- `semantic-reconciliation`: dynamic/static symbol comparison.
- `fp-analyzers`: conservative syntax-first effect summaries.
- `mcp-server`: CLI-backed stdio transport.
- `benchmark`: reproducible case models and fixtures.
