# Semantic API

The stable interface for agents is the CLI. MCP is a thin stdio adapter over
eight documented CLI-backed operations; CLI-only commands remain available
without implying an MCP projection.

The complete alpha-2 CLI/MCP classification is maintained in
[`agent-onboarding.md`](agent-onboarding.md#cli-and-mcp-surface-contract).
`MCP_EXPOSED` means an admitted bounded agent tool, while
`CLI_ONLY_BY_DESIGN` is an intentional transport boundary rather than missing
parity. A future `MCP_CANDIDATE_NOT_ADMITTED` operation would require a
decision-relevant use hypothesis; no current alpha-2 command has that status.

The command synopsis below describes mutable alpha-3 SNAPSHOT development.
The optional `--sbt-project` and `--sbt-java-home` forms are not present in the
supported immutable `0.1.0-alpha.2` package.

## Current Commands

```bash
semantic-scala --help
semantic-scala help
semantic-scala help <command>
semantic-scala version
semantic-scala --version
semantic-scala compile
semantic-scala compile --json
semantic-scala compile [--sbt-project <id>] [--sbt-java-home <absolute-directory>] --json
semantic-scala test
semantic-scala test --json
semantic-scala test [--sbt-project <id>] [--sbt-java-home <absolute-directory>] --json
semantic-scala errors
semantic-scala errors --json
semantic-scala errors [--sbt-project <id>] [--sbt-java-home <absolute-directory>] --json
semantic-scala semanticdb-status --workspace <path>
semantic-scala semanticdb-status --workspace <path> --json
semantic-scala semanticdb-status --workspace <path> --schema-version v1 --json
semantic-scala semanticdb-status --workspace <path> --schema-version v2 --json
semantic-scala semanticdb-coverage --workspace <path>
semantic-scala semanticdb-coverage --workspace <path> --json
semantic-scala semanticdb-for-source --file <path> --workspace <path>
semantic-scala semanticdb-for-source --file <path> --workspace <path> --json
semantic-scala point-evidence --file <path> --workspace <path> --line <n> --col <n>
semantic-scala point-evidence --file <path> --workspace <path> --line <n> --col <n> --json
semantic-scala symbols --semanticdb <path>
semantic-scala symbols --semanticdb <path> --json
semantic-scala usages --workspace <path> --manifest <relative.json> --symbol <global-symbol> [--include-definitions] [--module <id>]... [--source-set <id>]... [--include-generated] [--limit <1..500>] [--json]
semantic-scala usages --workspace <path> --manifest <relative.json> --file <relative-source> --line <n> --col <n> --semanticdb <relative-artifact> [selectors] [--json]
semantic-scala symbol-at --file <path> --line <n> --col <n>
semantic-scala symbol-at --file <path> --line <n> --col <n> --json
semantic-scala infer-type --file <path> --line <n> --col <n> [--workspace <path>] [--classpath <entry>]...
semantic-scala infer-type --file <path> --line <n> --col <n> [--workspace <path>] [--classpath <entry>]... --json
semantic-scala infer-type --file <path> --line <n> --col <n> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] [--sbt-java-home <absolute-directory>]
semantic-scala infer-type --file <path> --line <n> --col <n> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] [--sbt-java-home <absolute-directory>] --json
semantic-scala infer-type-batch --requests <path> --workspace <path> --sbt-project <id> --sbt-configuration Compile|Test [--sbt-cache-mode fresh|refresh|reuse] [--sbt-java-home <absolute-directory>] --json
semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path>
semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> --json
semantic-scala effect-summary --file <path>
semantic-scala effect-summary --file <path> --json
```

The optional build-oracle selector accepts one sbt project ID matching
`[A-Za-z][A-Za-z0-9_-]*`. Without it, `compile`, `errors`, and `test` preserve
their ordinary root behavior and existing v1 payloads. With it, `compile` and
`errors` select that project and run fixed `Compile / compile`; `test` selects
it and runs fixed `Test / test`. The ID is not an sbt command, task,
configuration, or scope expression. Invalid IDs are rejected before sbt;
unknown valid IDs fail without root fallback. Success describes only the
selected bounded invocation, not the aggregate workspace.

Exactly the sbt-backed forms above accept `--sbt-java-home`. Its value must be
an absolute installed Java home with a contained executable platform launcher;
a top-level SDKMAN-style symlink is canonicalized before use. A bounded fixed
`java -version` probe runs before target sbt. The validated home is applied
only to the child sbt process through `JAVA_HOME` and a leading `PATH` entry,
after which existing sandbox settings still apply. The parent harness runtime,
shell, SDKMAN state, and global Java configuration are unchanged. There is no
automatic JDK discovery, download, installation, or fallback. Invalid homes
are validation failures rather than compile failures, and raw home/probe data
is excluded from public results.

`compile` and `test` run `sbt -batch` in the current working directory.
`errors` temporarily reruns compile and returns the resulting compile report.
`semanticdb-status` performs a read-only recursive scan for existing
`.semanticdb` files in a workspace. The default and explicit v1 forms preserve
the original first-document payload. Opt-in v2 parses all documents and adds
raw size/SHA-256, exact duplicate, and bounded-candidate facts. V2
`artifactStatus` describes parseability; `coverageStatus = NotAssessed` makes
clear that checked fixtures and copied resources do not establish ordinary
source coverage. It does not run sbt, generate SemanticDB, mutate build files,
or add MCP behavior.
`semanticdb-coverage` recursively inventories regular Scala and Java files in
the workspace, excluding only the documented generated/metadata/dependency
directory names, then compares that inventory with all parsed SemanticDB
documents after exact raw-byte duplicate suppression. It accepts exact URIs and
unique multi-segment URI suffixes; basename-only URIs are not identity. Its
result is explicitly scoped to the reported inventory and does not establish
freshness, provenance, build-target completeness, or full build coverage. It is
read-only, does not run sbt or generate SemanticDB, and adds no MCP behavior.
`semanticdb-for-source` uses that discovery metadata to map one source file to
zero, one, or many candidates. It uses full URI/path signals, preserves
unparseable path matches as partial evidence, and reports ambiguity explicitly.
It does not run sbt, generate SemanticDB, or add MCP behavior.
`point-evidence` composes source-to-artifact discovery, unique parsed-artifact
selection, a live Presentation Compiler point query, and conditional
reconciliation. It accepts no caller-selected artifact, reports typed
non-selection and not-attempted reasons, and does not assess artifact
freshness. Its standalone contract is in
[`docs/point-evidence.md`](point-evidence.md).
`symbols` reads one explicit `.semanticdb` file path and returns a file-scoped
SemanticDB summary. Dynamic SemanticDB generation remains deferred.

`usages` is a released public CLI capability. It scans exact ordinary
SemanticDB `DEFINITION` and `REFERENCE` occurrences within one strict explicit
`semantic-scala.usages-manifest.v1`. The canonical user contract and examples
are in [`docs/usages.md`](usages.md).
Exactly one target is required: a stable global symbol, or a complete point
target consisting of a declared relative source, positive one-based UTF-16
line/column, and one declared relative SemanticDB artifact. Point-selected
local identities use the neutral `DocumentLocal` marker; raw compiler-local IDs
are never emitted. Output ranges are zero-based UTF-16.

The manifest supplies authoritative module, source-set, generated, and
inventory-closed declarations. It is strict UTF-8/JSON, closed to unknown
fields, limited to 16 MiB, normalized and unique by declaration path, and
validated as symlink-free regular files contained by the workspace. The
metadata size check is a fast fail; the content operation reads at most 16 MiB
plus one byte before rejecting over-limit input, closes its stream on every
outcome, and builds declaration lists in a linear fail-fast pass. The command
does not discover, compile, generate, refresh, retry, cache, or index.
Selectors are definitions, repeatable module/source-set sets, generated scope,
and a returned-occurrence limit that can only lower the hard maximum of 500.

Results retain `EvidenceFound`, `NoUsagesObserved`, `CoverageIncomplete`,
`TargetAmbiguous`, `TargetUnresolved`, `ArtifactStaleOrInconsistent`,
`Truncated`, and `UnsupportedConstruct`, with target, freshness, duplicate,
coverage, reason, and limit evidence. Precedence is target resolution or
unsupported target, then truncation/deadline, stale/inconsistent evidence,
incomplete coverage, and finally evidence versus bounded zero.
`NoUsagesObserved` never means globally unused. All eight domain states exit 0.
Typed `InvalidInput`, `UnsafeFilesystem`, `IoFailure`, `ParseFailure`,
`TimeoutBeforeTargetResolution`, and `InternalInvariant` failures exit 1. JSON
mode writes one result or failure envelope to stdout and nothing to stderr;
human failures write one sanitized stderr message.

Synthetics, inferred/desugared references, selected contexts, and
override/dispatch families are excluded. The public command has no MCP
projection; the MCP registry remains exactly eight tools.

The public `deadlineNanos` value describes the 30-second cooperative semantic
occurrence phase beginning at `UsagesBySymbolSpike.run`; it is not a
whole-command timer over manifest admission. Staged command help explicitly
states no artifact discovery, SemanticDB generation, build execution, or
artifact refresh, and focused tests enforce those boundaries. The current
manifest/result/failure v1 schemas are frozen for this CLI boundary. No MCP
`usages` projection or automatic acquisition is part of the release.

`symbol-at` queries the Scala 3 presentation compiler for the symbol at a source
position. CLI line and column inputs are one-based.
`infer-type` queries the pinned Scala 3 presentation compiler's public hover at
one source position. Lines are one-based and columns count UTF-16 code units.
Without `--classpath` it preserves the narrow Scala runtime context. Each
repeatable `--classpath` value is one explicit compiled classes directory or
JAR. Entries are normalized and de-duplicated in first-seen order; no
OS-separated list, wildcard, recursive expansion, build invocation, target
scan, or dependency resolution occurs. Optional `--workspace` supplies an
existing compiler folder context but does not imply complete project context.
The opt-in sbt-backed form instead requires an explicit workspace, project ID,
and exact `Compile` or `Test` configuration. It is mutually exclusive with
`--classpath`. Project IDs must match `[A-Za-z][A-Za-z0-9_-]*`; no scope is
inferred. The CLI evaluates the selected scoped `fullClasspath`, which may
compile or refresh build outputs, and passes the acquired entries to the same
presentation query. Acquisition failure is nonzero; a successful acquisition
with no usable hover remains exit-zero `Unresolved`.

For that exact sbt-backed form, omitted `--sbt-cache-mode` is `fresh`.
`fresh` evaluates sbt and does not access the persistent cache. `refresh`
evaluates sbt, requires matching pre/post conventional inputs, validates
bounded content evidence for every acquired entry, and atomically replaces the
private exact-key record. `reuse` requires that record and exact matching
covered evidence; it does not run sbt. No failure silently falls back to a
previous context.

Without `--sbt-java-home`, acquisition and persistent reuse remain exactly on
the existing v1 protocol/cache path, including reuse of existing v1 records
and no additional Java probe. An explicit selected home uses an isolated
strict v2 context keyed by opaque home and runtime evidence. It never reads or
rewrites v1. Different canonical homes cannot cross-reuse; if a Java runtime
changes in place, explicit `reuse` fails closed and never invokes sbt.

The private cache is per-user and outside workspaces. Conventional coverage is
root `*.sbt`, `project/`, and nested
`src/main|test/{scala,java,resources}`, excluding `.git`, `target`, `.bloop`,
`.metals`, `.idea`, `.scala-build`, and `node_modules`. Full JAR bytes and all
regular directory-entry bytes are hashed within documented fail-closed bounds.
Custom roots/generators, unsaved buffers, environment/property reads, remote
state, clocks, and arbitrary sbt logic are not covered. Matching bounded
evidence is therefore not proof of current sbt freshness.
`reconcile-symbol` compares one explicit SemanticDB file with the presentation
compiler result for one source position. It is file-scoped and does not perform
directory indexing or symbol normalization.
`effect-summary` reads one explicit Scala source file and summarizes declared
method return types with conservative outer effect classification. It is
syntax-first and does not infer missing return types. Package and owner context
fields are syntactic convenience fields, not compiler symbol identity.

## Schema Versioning

`schemaVersion` identifies the JSON payload shape. It is not the CLI binary
version.

Current schema-versioned payloads:

- `compile --json`: `semantic-scala.compile-result.v1`
- `test --json`: `semantic-scala.test-result.v1`
- `errors --json`: `semantic-scala.errors-result.v1`
- `semanticdb-status --workspace <path> --json`:
  `semantic-scala.semanticdb-status.v1`
- `semanticdb-status --workspace <path> --schema-version v2 --json`:
  `semantic-scala.semanticdb-status.v2`
- `semanticdb-coverage --workspace <path> --json`:
  `semantic-scala.semanticdb-coverage.v1`
- `semanticdb-for-source --file <path> --workspace <path> --json`:
  `semantic-scala.semanticdb-for-source.v1`
- `point-evidence --file <path> --workspace <path> --line <n> --col <n>
  --json`: `semantic-scala.point-evidence-result.v1`
- `symbols --semanticdb <path> --json`: `semantic-scala.symbols-result.v1`
- `usages ... --json`: `semantic-scala.usages-result.v1` on domain outcomes or
  `semantic-scala.usages-failure.v1` on operational failure; its input manifest
  is `semantic-scala.usages-manifest.v1`
- `symbol-at --file <path> --line <n> --col <n> --json`:
  `semantic-scala.symbol-at-result.v1`
- `infer-type --file <path> --line <n> --col <n> --json`:
  `semantic-scala.infer-type-result.v1`
- `infer-type-batch --requests <path> --workspace <path> --sbt-project <id>
  --sbt-configuration Compile|Test --json`:
  `semantic-scala.infer-type-batch-result.v1`; its input is
  `semantic-scala.infer-type-batch-request.v1`
- `reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path>
  --json`: `semantic-scala.reconcile-symbol-result.v1`
- `effect-summary --json`: `semantic-scala.effect-summary.v1`

Current pre-versioned payloads:

- old benchmark or saved CLI JSON captured before schema markers were added

Versioning policy:

- additive optional fields do not necessarily require a new `schemaVersion`;
- removing or renaming fields requires a new `schemaVersion`;
- changing field semantics requires a new `schemaVersion`;
- old benchmark JSON may not have `schemaVersion` and should be interpreted as
  pre-versioned.

The current executable keeps status v1 as the default and selects v2
explicitly. No known external consumer currently requires either runtime
shape, no active script parses them, and no MCP tool invokes status.

The target policy is one current factual all-document status contract, based on
the useful v2 facts, with an embedded `schemaVersion` but no public runtime
version selector. V1/V2 remain executable temporarily because performing the
migration now has less utility than the next semantic feature. When
consolidation is scheduled, internal Scala consumers should migrate first and
the old executable projection may then be removed. Runtime version negotiation
should return only when an independent consumer demonstrates that need.

Admitted public benchmark JSON remains frozen by the public evidence manifest
and interpretable through its schema marker. Historical controller records are
not part of the public reproduction subset. Qualified artifact hints remain
internal because the public-utility review found no public consumer and no
reliable multi-workflow action change.

Opt-in `semanticdb-status.v2` exposes the factual all-document subset: raw
artifact metadata, exact duplicate groups, explicit candidate bounds, and
separate artifact/coverage status. Provenance, freshness, source selection,
and `semanticdb-for-source.v2` remain proposals.

An internal copy-level model records independently qualified provenance,
source-set, output-kind, producer, module, and configuration hints with
explicit evidence. The inventory invokes the pure classifier, but no current
public encoder exposes its result. Path-derived values remain heuristic and
are not canonical build-target IDs. A controlled ordinary-main-source case
matched a pre-existing Bloop/Metals artifact, but that observation does not
promote path hints to canonical target identity.

## Initial JSON Models

`DiagnosticPosition`

- `file`: source path
- `line`: one-based line number
- `column`: one-based column number

`Diagnostic`

- `severity`: diagnostic category such as `error`, `warning`, or `failure`
- `message`: human-readable compiler or test message
- `position`: optional source position

`CompileReport`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.compile-result.v1` for `compile --json` and
  `semantic-scala.errors-result.v1` for `errors --json`
- `success`: whether compilation succeeded
- `diagnostics`: compiler diagnostics

`TestReport`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.test-result.v1`
- `success`: whether all tests passed
- `total`: total test count
- `passed`: passing test count
- `failed`: failing test count
- `failures`: failure diagnostics

`SemanticRange`

- `startLine`: zero-based start line
- `startCharacter`: zero-based start character
- `endLine`: zero-based end line
- `endCharacter`: zero-based end character

`SemanticSymbol`

- `symbol`: canonical SemanticDB symbol string for tool identity
- `displayName`: human-readable name, not a unique identity
- `kind`: optional SemanticDB symbol kind
- `language`: optional SemanticDB language

`SemanticOccurrence`

- `symbol`: canonical SemanticDB symbol string
- `role`: occurrence role such as `DEFINITION` or `REFERENCE`
- `range`: optional source range

`SemanticFileSummary`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.symbols-result.v1`
- `uri`: SemanticDB document URI/path
- `symbols`: symbols defined in the file
- `occurrences`: symbol occurrences in the file

`SemanticdbStatusCandidate`

- `semanticdb`: SemanticDB file path relative to the queried workspace when
  possible
- `uri`: parsed SemanticDB document URI when available
- `parseStatus`: `Parsed` or `Unparseable`
- `symbols`: symbol count when parsed
- `occurrences`: occurrence count when parsed
- `mtimeMillis`: candidate file modification time in milliseconds
- `error`: bounded parse error for unparseable candidates

`SemanticdbStatusReport`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.semanticdb-status.v1`
- `workspace`: normalized absolute workspace path
- `status`: `Available`, `Partial`, `Unavailable`, or `Unparseable`
- `semanticdbFiles`: total candidate count
- `parseableFiles`: candidates parsed successfully
- `unparseableFiles`: candidates that failed parsing
- `sourceRoots`: conservative source-root hints from candidate paths or URIs
- `candidates`: deterministic candidate metadata
- `errors`: reserved for non-fatal scan warnings

`SemanticdbStatusReportV2`

- `schemaVersion`: `semantic-scala.semanticdb-status.v2`
- `artifactStatus`: v1's factual availability logic under an explicit name
- `coverageStatus`: currently `NotAssessed`
- artifact counts: `semanticdbFiles`, distinct `uniqueContentFiles`,
  beyond-representative `duplicateFiles`, `duplicateGroupCount`, and
  parseable/unparseable counts
- bounding facts: `totalCandidates`, `returnedCandidates`, `candidateLimit`
  (200 by default), and `candidatesTruncated`
- `duplicateGroups`: complete multi-member raw-SHA-256 group summaries with
  bounded lexicographically ordered path samples
- `candidates`: workspace-relative path order, raw metadata, duplicate facts,
  all document summaries in protobuf order, document URIs in the same order,
  and explicit totals
- `warnings` / `errors`: honest metadata/scan diagnostics

`SemanticdbCoverageReport`

- `schemaVersion`: `semantic-scala.semanticdb-coverage.v1`
- `workspace`: normalized absolute workspace path
- `coverageStatus`: `NoInventorySources`, `NoSemanticdbDocuments`,
  `NoCoveredSources`, `Partial`, or `CompleteWithinInventory`
- `inventoryBasis`: `WorkspaceRecursiveSourceScanV1`, stable `.scala`/`.java`
  extensions, excluded directory names, no symlink following, and explicit
  inclusion outside conventional source roots
- source counts: `sourceFiles`, `coveredSourceFiles`,
  `uncoveredSourceFiles`, and `ambiguousSourceFiles`
- artifact/document counts: `semanticdbArtifactFiles`,
  `uniqueArtifactContents`, `rawDocumentEntries`, `uniqueDocumentEvidence`,
  `matchedDocumentEvidence`, `unmatchedDocumentEvidence`, and
  `ambiguousDocumentEvidence`
- bounding facts: total/returned source and unmatched-document entries, limits,
  and truncation flags
- `sources`: source-path ordered `Covered`, `Uncovered`, or `Ambiguous` entries
- `unmatchedDocuments`: content-hash/document-index ordered evidence with URI,
  counts, exact-copy count, and bounded artifact path samples
- `warnings` / `errors`: bounded scan, parse, weak-identity, and truncation facts

Coverage count definitions:

- `sourceFiles` is every regular `.scala`/`.java` file found by the reported
  source inventory basis; its three status counts partition that total.
- `semanticdbArtifactFiles` counts discovered `.semanticdb` files, including
  exact copies; `uniqueArtifactContents` counts distinct raw SHA-256 contents.
- `rawDocumentEntries` sums documents across every parseable artifact copy;
  `uniqueDocumentEvidence` counts documents after exact-copy suppression by
  content hash plus document index.
- `matchedDocumentEvidence` counts unique evidence with at least one allowed
  source match; `unmatchedDocumentEvidence` counts evidence with none.
  `ambiguousDocumentEvidence` is the matched subset whose URI matches more than
  one inventory source. Multiple byte-distinct evidence items matching one
  source instead make that source `Ambiguous`.
- `CompleteWithinInventory` means each inventoried source has exactly one safe
  unique evidence match. It does not claim freshness, generated-source or
  build-target discovery, or full project coverage.

`SemanticdbForSourceReport`

- `schemaVersion`: `semantic-scala.semanticdb-for-source.v1`
- `workspace` / `sourceFile`: normalized absolute input paths
- `sourceRelativePath`: workspace-relative source path when the file is inside
  the workspace
- `status`: `UniqueMatch`, `NoMatch`, `Ambiguous`, `Unavailable`,
  `Unparseable`, or `Partial`
- `semanticdbFiles`, `parseableFiles`, `unparseableFiles`: discovery counts
- `matches`: deterministic bounded candidate metadata with `matchKind` equal to
  `UriExact`, `MetaInfSuffix`, or `SourceRootSuffix`
- `candidatesConsidered`: total discovered candidates examined
- `warnings`: partial-evidence warnings; `errors`: scan errors

`SourceRange`

- `startLine`: zero-based start line
- `startCharacter`: zero-based start character
- `endLine`: zero-based end line
- `endCharacter`: zero-based end character

`SymbolAtResult`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.symbol-at-result.v1`
- `symbol`: optional compiler-provided symbol identity
- `displayName`: optional human-readable display name
- `range`: optional source range for the resolved symbol location
- `source`: queried source file path

`InferTypeStatus`

- exact stable strings:
  - `Resolved`: public hover produced a usable rendering;
  - `Unresolved`: the query executed, but public hover produced no usable
    rendering; the status does not claim why.

`InferTypeRenderingKind`

- exact stable strings:
  - `ExpressionType`;
  - `SymbolSignature`;
  - `HoverCode`;
  - `NoRendering`.

`InferTypePublicPosition`

- `line`: one-based line;
- `column`: one-based UTF-16 code-unit column;
- `encoding`: `UTF-16`.

`InferTypeContextSummary`

- `kind`: exact `NarrowRuntime`, `ExplicitClasspath`, or `SbtClasspath`;
- `classpathEntryCount`: normalized caller-supplied explicit entry count, zero
  for narrow runtime, or normalized/de-duplicated acquired scoped
  `fullClasspath` count for sbt mode; it does not add an implicit count for the
  built-in Scala runtime;
- `workspaceProvided`: whether an existing workspace directory was supplied;
- `sbtProject`: selected safe project ID, present only for sbt context;
- `sbtConfiguration`: exact `Compile` or `Test`, present only for sbt context;
- `acquisitionOrigin`: optional `FreshSbt` or `CachedExplicitReuse`, present
  for current sbt-backed results and absent from narrow/manual historical
  contexts;
- `freshnessAssessment`: optional `FreshBySbtEvaluation` or
  `ReusedWithMatchingEvidence`, with the same presence rule.

`InferTypeReport`

- `schemaVersion`: `semantic-scala.infer-type-result.v1`;
- `status`: `InferTypeStatus`;
- `rendering`: optional compiler-rendered evidence;
- `renderingKind`: `InferTypeRenderingKind`;
- `source`: queried source path, following existing CLI path behavior;
- `position`: public one-based UTF-16 position;
- `range`: optional zero-based LSP range returned by the compiler;
- `context`: safe context summary without paths;
- `warnings`: deterministic ordered limitations.

The public report does not include raw compiler Markdown, classpath or cache
paths, private identity/evidence hashes, cache timestamps/age, environment
variables, compiler objects, or source contents. Reuse warnings say both that
sbt did not run for this invocation and that bounded matching evidence does not
prove current sbt freshness or cover arbitrary build inputs.

`ReconciliationStatus`

- string enum encoded as exactly one of:
  - `ExactMatch`
  - `RangeMatchOnly`
  - `SymbolMismatch`
  - `NoMatch`

`ReconciledSymbol`

- `semanticdbSymbol`: optional canonical SemanticDB symbol string
- `compilerSymbol`: optional presentation compiler symbol string
- `displayName`: optional human-readable display name, not identity
- `range`: optional best available source range
- `status`: reconciliation status

`ReconciliationResult`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.reconcile-symbol-result.v1`
- `file`: queried source file path
- `queryPosition`: zero-based point represented as a `SourceRange`
- `result`: reconciled symbol result

`EffectSourceRange`

- `startLine`: zero-based start line
- `startCharacter`: zero-based start character
- `endLine`: zero-based end line
- `endCharacter`: zero-based end character

`EffectMethodSummary`

- `name`: method name
- `range`: optional source range for the method line
- `ownerName`: optional syntactic nearest enclosing owner name, such as
  `Parser` or `UserRepo`
- `qualifiedName`: optional syntactic owner-qualified method name, such as
  `Parser.parseEither`
- `enclosingKind`: optional syntactic owner kind, currently `object`, `class`,
  `trait`, or `enum`
- `packageName`: optional syntactic package declaration, such as `example` or
  `example.foo`
- `packageQualifiedName`: optional syntactic package plus local
  `qualifiedName`, such as `example.Parser.parseEither`
- `sourceFile`: optional source path for the method summary, using the same
  path style as the enclosing report `source`
- `declaredReturnType`: explicit return type when present
- `inferredReturnType`: currently always absent in v0
- `effectCategory`: one of `plain`, `option`, `either`, `future`, `io`, `zio`,
  `generic-effect`, or `unknown`
- `confidence`: `declared` or `unknown` in v0
- `notes`: conservative explanation notes

`EffectSummaryReport`

- `schemaVersion`: payload schema marker, currently
  `semantic-scala.effect-summary.v1`
- `source`: queried source file path
- `methods`: method summaries found in the file

## JSON Output Contract

- When `--json` is used, stdout contains only JSON.
- Logs, progress output, and human-readable debug text must not be mixed into
  JSON stdout.
- stderr may be used for logs or human-readable errors.
- On this Java/Scala runtime, terminal transcripts may show JVM
  `sun.misc.Unsafe` deprecation warnings on stderr before or beside the JSON
  command output. These warnings are not part of stdout and do not invalidate
  the machine-readable JSON payload.
- Exit code `0` means the CLI command executed successfully, even when a JSON
  report contains `success: false`.
- Non-zero exit codes are reserved for CLI/runtime failures such as invalid
  commands, parsing failures, or internal exceptions.
- Compile/test result failure is represented by `success: false` in the JSON
  report.
- `compile --json` emits a `CompileReport` with top-level `schemaVersion`
  `semantic-scala.compile-result.v1`.
- `test --json` emits a `TestReport` with top-level `schemaVersion`
  `semantic-scala.test-result.v1`.
- `errors --json` emits a `CompileReport` with top-level `schemaVersion`
  `semantic-scala.errors-result.v1`.
- `semanticdb-status --workspace <path> --json` emits a
  `SemanticdbStatusReport` and keeps stdout JSON-only. Its top-level
  `schemaVersion` is `semantic-scala.semanticdb-status.v1`.
- Adding `--schema-version v1` produces the same v1 behavior. Adding
  `--schema-version v2` emits `SemanticdbStatusReportV2` with top-level
  `schemaVersion` `semantic-scala.semanticdb-status.v2`.
- `semanticdb-coverage --workspace <path> --json` emits a
  `SemanticdbCoverageReport` and keeps stdout JSON-only. Its top-level
  `schemaVersion` is `semantic-scala.semanticdb-coverage.v1`.
- `semanticdb-for-source --file <path> --workspace <path> --json` emits a
  `SemanticdbForSourceReport` and keeps stdout JSON-only. Its top-level
  `schemaVersion` is `semantic-scala.semanticdb-for-source.v1`.
- `point-evidence --file <path> --workspace <path> --line <n> --col <n>
  --json` emits a `PointEvidenceReport` with full discovery, conservative
  selection, live point evidence, and completed or typed not-attempted
  reconciliation. Its top-level `schemaVersion` is
  `semantic-scala.point-evidence-result.v1`.
- `symbols --semanticdb <path> --json` emits a `SemanticFileSummary` and keeps
  stdout JSON-only. Its top-level `schemaVersion` is
  `semantic-scala.symbols-result.v1`.
- `symbol-at --file <path> --line <n> --col <n> --json` emits a
  `SymbolAtResult` and keeps stdout JSON-only. Its top-level `schemaVersion` is
  `semantic-scala.symbol-at-result.v1`.
- `infer-type --file <path> --line <n> --col <n> ... --json` emits an
  `InferTypeReport` and keeps stdout JSON-only. Resolved and unresolved semantic
  results both exit `0` with empty stderr. Invalid input/context and
  infrastructure failures exit nonzero with empty stdout and concise stderr.
  Human output is `<source>:<line>:<column>: <rendering>` or
  `<source>:<line>:<column>: <unresolved>`.
- `reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path>
  --json` emits a `ReconciliationResult` and keeps stdout JSON-only. Its
  top-level `schemaVersion` is
  `semantic-scala.reconcile-symbol-result.v1`.
- Valid no-symbol or no-match reconciliation queries exit `0` with status
  `NoMatch`.
- `effect-summary --file <path> --json` emits an `EffectSummaryReport` and
  keeps stdout JSON-only. Its top-level `schemaVersion` is
  `semantic-scala.effect-summary.v1`.

## MCP Adapter Status

`modules/mcp-server` currently exposes eight tools over a minimal
MCP-compatible stdio JSON-RPC transport: build-oracle tools
`semantic_compile`, `semantic_errors`, and `semantic_test`, plus
`semantic_effect_summary`, `semantic_symbol_at`, `semantic_symbols`, and
`semantic_reconcile_symbol`, and `semantic_point_evidence`.
External-client validation notes live in
[`docs/mcp-client-validation.md`](mcp-client-validation.md). They cover project
configuration, bounded real-client observations, and the current staged
exact-eight-tool smoke. Those observations are client/version specific and do
not replace the protocol and schema checks below.
Each tool validates a workspace directory through the adapter and runs the
packaged CLI with the process working directory set to that workspace:

```bash
semantic-scala compile --json
semantic-scala errors --json
semantic-scala test --json
semantic-scala effect-summary --file <path> --json
semantic-scala symbol-at --file <path> --line <n> --col <n> --json
semantic-scala symbols --semanticdb <path> --json
semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> --json
semantic-scala point-evidence --workspace . --file <path> --line <n> --col <n> --json
```

For the first three tools, optional MCP string inputs `sbtProject` and
`sbtJavaHome` map only to CLI `--sbt-project` and `--sbt-java-home`. They use
the same strict project-ID and absolute-home policies. Both fields are absent
from tools 4-8, and the ordered registry remains exactly eight tools. Public
MCP command metadata redacts the selected Java home.

The adapter parses stdout as the authoritative CLI JSON payload. It requires
`semantic-scala.compile-result.v1` for `semantic_compile` and
`semantic-scala.errors-result.v1` for `semantic_errors`, and
`semantic-scala.test-result.v1` for `semantic_test`.
`semantic_effect_summary` requires a relative `.scala` file path inside the
workspace, rejects paths that escape the workspace, and requires
`semantic-scala.effect-summary.v1`.
`semantic_symbol_at` uses the same relative `.scala` file policy, requires
positive one-based `line` and `col` integers, and requires
`semantic-scala.symbol-at-result.v1`.
`semantic_symbols` requires a relative `.semanticdb` file path inside the
workspace, rejects paths that escape the workspace, and requires
`semantic-scala.symbols-result.v1`. It reads an explicit SemanticDB file and
returns file-scoped symbols and occurrences evidence; it does not generate
SemanticDB files or discover SemanticDB directories.
`semantic_reconcile_symbol` combines the relative `.scala` file policy,
positive one-based `line`/`col` validation, and relative `.semanticdb` file
policy. It requires `semantic-scala.reconcile-symbol-result.v1` and returns
reconciliation evidence between the Presentation Compiler point query and
SemanticDB occurrence data.
`semantic_point_evidence` uses the relative `.scala` file and positive position
policy, delegates to the CLI-owned composition, and requires
`semantic-scala.point-evidence-result.v1`. It never accepts a caller-selected
SemanticDB artifact and does not assess artifact freshness.

Reconciliation statuses are domain evidence rather than MCP transport status.
`ExactMatch` is strong evidence that compiler and SemanticDB symbols agreed.
`RangeMatchOnly` is weaker evidence where a range or one side of the evidence
matched without exact symbol identity. `SymbolMismatch` is conflicting symbol
evidence. `NoMatch` means no useful reconciliation match was found. All of
these statuses are adapter success when the CLI exits `0` and emits the
expected schema.

Wrapper metadata such as `ok`, `command`, `workspace`, `exitCode`,
`schemaVersion`, `stderr`, and `error` stays outside `payload`. A compile,
errors, or test report with `success: false` is still an adapter success when
the CLI exits `0` and emits the expected schema. Non-zero CLI exits, malformed
JSON, and schema mismatches are adapter failures and do not fabricate a domain
payload. The build-oracle tools may run sbt/build/test code through the CLI and
are not read-only queries.

MCP `tools/call` returns this wrapper both as `structuredContent` and as a text
content JSON string for compatibility. The transport supports `initialize`,
`ping`, `tools/list`, `tools/call`, and the `notifications/initialized`
and `notifications/cancelled` notifications. All notifications are
response-free. Cancellation carries a string or numeric `params.requestId`; if
it wins the request's terminal race, owned work is stopped and no result is
emitted.

The production runtime admits at most eight active requests. Duplicate active
IDs fail without replacing the original, and IDs are reusable after cleanup.
Build-oracle tools share one cancellation-aware permit; file/read tools may
finish out of order. The default monotonic deadline is ten minutes. Raw
subprocess capture is limited to 32 MiB stdout, 4 MiB stderr, and 36 MiB
aggregate, with strict UTF-8 decoding. The fully encoded JSON-RPC line is
limited to 68 MiB and is emitted under a synchronized complete-line writer.

Adapter failures preserve the wrapper shape but use a logical command,
`"<workspace>"`, empty stderr, and a stable error message. They do not expose
absolute paths, full argv, raw process output, environment values, or exception
text. Successful bounded outputs retain the existing wrapper and domain
schemas. Additional MCP tools, caching, broad workspace-root policy, and
direct-module implementation are not included yet.

### MCP revision and infer-type readiness

The current server directly implements MCP `2025-06-18`; it does not depend on
an MCP SDK. Initialize is compatibility-only and stores no client capabilities,
workspace, approval, or session state. There is no `Mcp-Session-Id`.

The final `2026-07-28` revision at
`5f5440bb26a62e2cf3440b92da5a667efa03b267` removes initialize/session state,
carries protocol/client capability metadata per request, and requires
`server/discover`. Stable official Go and TypeScript SDKs cover that era, but
released conformance `v0.1.16` does not and official Java SDK `v2.0.0` targets
`2025-11-25`. Official Codex and Claude Code documentation does not identify
exact final negotiation. Support is therefore unresolved rather than presumed
incompatible. This project retains only its `2025-06-18` contract and adds no
era selector until released final-revision conformance and bounded client
gates exist.

Future `semantic_infer_type` and `semantic_infer_type_batch` should be separate
thin projections of the existing public CLI objects. Every call must carry an
explicit workspace and context variant; sbt calls must carry project,
configuration, and `fresh|refresh|reuse`. They must preserve neutral
`Unresolved`, batch item statuses, provenance, and bounds, and must not expose
private paths/cache evidence. Fresh/refresh require build-capable approval;
refresh also writes the private cache; reuse is an explicit bounded cache read
and never falls back. The server-side cancellation, timeout,
output-containment, and failure-privacy gates already exist. Infer-type MCP
tools remain deferred pending a separate host-approval and public-contract
decision.

For local development, the server can be run with:

```bash
sbt --error "mcpServer/run"
```

For MCP clients, prefer the staged launcher:

```bash
sbt mcpServer/stage
modules/mcp-server/target/stage/bin/semantic-scala-mcp
```

Configure the packaged CLI with `SEMANTIC_SCALA_CLI` or
`semantic-scala-mcp --cli /path/to/semantic-scala`.

The staged transport can be smoke-tested with:

```bash
sbt cli/stage
sbt mcpServer/stage
scripts/mcp/smoke-mcp-tools.py
```

The smoke script validates initialization, tool listing, `semantic_compile` and
`semantic_errors` against compile-success and compile-failure examples,
`semantic_test` against test success and failure examples,
`semantic_effect_summary` against the FP analyzer fixture,
`semantic_symbol_at` against the presentation compiler fixture,
`semantic_symbols` against the SemanticDB reader fixture,
`semantic_reconcile_symbol` against the reconciliation fixture, invalid
workspaces, invalid input shape, invalid file paths, invalid symbol-at and
reconcile positions, invalid `.semanticdb` paths, and unknown tools.

## Diagnostic Limits

- Diagnostic extraction is conservative.
- Long diagnostic messages are truncated.
- Full sbt logs are not embedded in JSON diagnostics.
- Unknown test counts are represented as `0`.

## SemanticDB Limits

- `semanticdb-status --workspace <path> --json` reports workspace SemanticDB
  availability using existing `.semanticdb` files only.
- `semanticdb-for-source` maps a source file to existing candidates with
  `semanticdb-for-source --file <path> --workspace <path> --json`; it reports
  ambiguous and partial evidence rather than choosing silently.
- Discovery and mapping do not currently identify module/build target,
  provenance, or source freshness. The fixed source-root
  list covers only conventional main/test Scala and Java roots, so other roots
  such as `src/test/resources` can return `NoMatch` even when a nearby fixture
  exists.
- Opt-in status v2 keeps v1 unchanged, parses every document, hashes raw bytes,
  reports exact duplicate groups, bounds candidate objects deterministically,
  and preserves complete aggregate/group facts. Artifact availability does not
  mean source coverage; coverage is explicitly `NotAssessed`.
- The separate factual coverage command inventories `.scala`/`.java`
  recursively while excluding `.git`, `.idea`, `.metals`, `.bloop`,
  `.scala-build`, `target`, `out`, and `node_modules`; symlinks are not followed
  and custom/hidden non-excluded roots are included. Exact artifact copies are
  suppressed by raw SHA-256 before document matching. Only exact URIs and
  unique multi-segment suffixes establish coverage; basenames do not.
  Classification uses complete inventories before default bounds of 200 source
  entries, 200 unmatched documents, and 20 artifact path samples.
- An internal classifier records independent, qualified copy-level
  provenance/source-set/output/producer/module/configuration hints. Structured
  path rules remain heuristic; there is no canonical build-target field, and
  Git/Bloop/BSP metadata enrichment is deferred. Current status, coverage, and
  mapping schemas do not expose or select by hints. Freshness/selection, public
  hint fields, `semanticdb-for-source.v2`, and higher-level source selection
  remain deferred. A bounded compatibility matrix has tested Scala 2.13.18 and
  Scala 3.3.8 artifacts; target-version claims remain command-specific rather
  than inferred from artifact availability.
- `symbols --semanticdb` and `reconcile-symbol --semanticdb` still require
  explicit SemanticDB paths.
- It does not generate SemanticDB dynamically.
- The existing explicit acquisition recipe is observational rather than a
  generation contract: first run `semanticdb-status` and
  `semanticdb-for-source` (or `point-evidence`), then, only with approval for
  its build side effects, run `semantic-scala compile --json` for the ordinary
  root or `semantic-scala compile --sbt-project <id> --json` for one known
  validated project and repeat the same SemanticDB query. `compile` can execute target
  build/plugin code, resolve dependencies, populate caches, and write build
  outputs. It produces SemanticDB only when the target's checked-in build is
  already configured to do so.
- A successful compile followed by a matching artifact demonstrates
  acquisition only for the observed build scope; preserve unique, ambiguous,
  partial, coverage, and freshness states. A successful compile followed by
  no matching artifact does not prove source absence, unsupported Scala, or
  that SemanticDB cannot be generated. Stop rather than inserting compiler
  flags/plugins or mutating the build, and ask the project owner to select or
  enable an appropriate SemanticDB-producing build. Build failure remains a
  separate result.
- The optional build-oracle project selector does not discover projects or
  accept arbitrary configuration/scope syntax. When root or selected scope
  does not cover the source, report the scope uncertainty; do not replace the
  public recipe with a hidden shell build.
- It does not parse TASTy or use Metals/BSP/presentation compiler APIs.
- Canonical `symbol` values are preserved for tools; `displayName` is for
  humans and may be ambiguous.

## Dynamic Semantic Query Limits

- Dynamic point queries use the Scala 3 presentation compiler directly.
- The pinned Scala 3.3 presentation compiler resolved the shared-syntax points
  in one Scala 2.13.18 fixture, but it is not a native Scala 2 presentation
  compiler. That result does not establish support for Scala-2-specific syntax,
  macros, compiler plugins, or arbitrary project classpaths.
- The CLI accepts one explicit `.scala` source file path.
- CLI line and column inputs are one-based.
- No Metals, BSP, LSP, TASTy, MCP, graph storage, vector search, or FP analyzer
  integration is added.
- No project-wide workspace indexing is implemented.
- Symbol identity depends on what the presentation compiler returns for the
  queried source and minimal classpath.

`infer-type` exposes the pinned compiler's rendered hover type/signature at one
point: an explicitly labelled expression type when the LSP hover provides one,
otherwise the public symbol signature, otherwise one leading Scala hover code
block. It does not claim canonical type identity, alias expansion, singleton
widening, or successful whole-file/project compilation.

Input uses one-based lines and UTF-16 columns. Explicit context accepts
normalized, de-duplicated compiled classpath entries plus an optional workspace
directory. Beyond the built-in Scala runtime, only those caller-supplied
compiled entries are available. The API does not invoke sbt,
discover/download dependencies, infer configuration, or model uncompiled/open
sibling sources. Empty hover is the successful `Unresolved` / `NoRendering`
result: the public API cannot reliably distinguish whitespace/comments,
unsupported point targets, source errors, incomplete classpath, or another
hover limitation.

Controlled evidence resolved an API-owned `DomainId` under an app Compile
classpath and a test-only `TestToken` only under app Test. Removing API output,
using Compile context for the test consumer, or using narrow runtime returned
neutral unresolved results. Eight additional CLI/presentation-compiler
queries against checked-in source resolved with the 64-entry staged CLI
Compile classpath, including repository-owned sibling types, a `List[Path]`
expression, an `Option[Extracted]` signature, and Circe `asJson`. Removing the
sole staged entry owning a sibling type or Circe syntax changed the matched
query to neutral `Unresolved`; workspace-only comparisons were also
unresolved. Repeated sanitized capture was byte-identical. These remain
Scala/presentation-compiler 3.3.3-dependent renderings, not canonical type
identity. No `infer-type` MCP tool exists.

Compiled sibling output was sufficient for the selected checked-in sources.
Live, open, uncompiled, or unsaved sibling source remains unsupported and
unproven.

Deterministic, opt-in sbt acquisition lives at the CLI/orchestration boundary.
It uses isolated temporary global settings and a private versioned file
protocol rather than parsing `show`; target build definitions are not
modified. Controlled Compile/Test scopes differed correctly, representative
real queries matched semantically, and valid wrong-project context returned
neutral `Unresolved`. Successful public output exposes only project,
configuration, and entry count—not paths or sbt logs.

Scoped `fullClasspath` evaluation may compile or refresh outputs. Always-fresh
evaluation is the default; `refresh` and `reuse` are explicit. The private
strict record uses exact canonical workspace,
project, and configuration identity, bounded conventional-input and full
known-entry content evidence, a bounded exact-key lock, and force/reread/
atomic-only publication. Missing, stale, malformed, oversized, symlinked,
locked, failed-acquisition, and failed-publication cases are nonzero with no
fallback.

The acquisition layer does not acquire compiler options/plugins, infer a
project/configuration, or model live sibling sources. Real `cli / Compile`
reuse retained most of the measured sbt-acquisition saving, but each single
query still creates a presentation compiler.

The sbt-context-only batch form is:

```text
semantic-scala infer-type-batch --requests <batch-request.json> \
  --workspace <path> --sbt-project <id> \
  --sbt-configuration Compile|Test \
  [--sbt-cache-mode fresh|refresh|reuse] --json
```

The strict input marker is
`semantic-scala.infer-type-batch-request.v1`. Its ordered, nonempty
`requests` array is capped at 128; IDs are unique and use
`[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. Unknown top-level or item fields are
rejected. Lines/columns are positive and one-based, with UTF-16 columns. File
paths resolve relative to the explicit workspace and may not escape it.
Source-file symlinks are rejected.

The result marker is `semantic-scala.infer-type-batch-result.v1`. It contains
one context/provenance summary and warning list, exact request count, and one
ordered item for every request. Item statuses have explicit string codecs:
`Resolved`, neutral `Unresolved`, `InvalidRequest`, and `QueryFailure`.
Structurally invalid request documents and shared context/cache failures exit
nonzero without semantic JSON. Once acquisition succeeds, item-local outcomes
do not affect exit code and do not cancel later items.

The request file is capped at 4 MiB; IDs at 128 UTF-8 bytes; paths at 16 KiB;
sources at 8 MiB per item; renderings at 256 KiB; messages at 16 KiB; and
encoded output at 32 MiB. Bounds fail closed without semantic truncation.
Public item sources are workspace-relative. Output contains no request-file
path, source text, raw hover Markdown, classpath/cache path, private digest,
timestamp, cache age, environment value, or sbt log.

CLI performs exactly one cache resolution per batch. Fresh remains
default; refresh/reuse semantics and provenance are unchanged. Controlled
equivalence, isolation, close, and latency evidence selected one shared
presentation compiler reused sequentially for valid items; no parallel
requests are issued. Each item captures its source once and checks metadata
after querying. Repeated items may see later external edits, so the batch is
not an atomic workspace snapshot.

No `infer-type` MCP tool exists. The current request lifecycle and output
containment support are necessary but not sufficient to expose one; a separate
host-approval and public tool-contract gate remains.

## Reconciliation Limits

- Reconciliation compares one explicit `.scala` file and one explicit
  `.semanticdb` file.
- It does not prove equivalence unless status is `ExactMatch`.
- It preserves SemanticDB and compiler symbols separately.
- It compares symbol strings without normalization.
- `displayName` is never treated as identity.
- Status values are `ExactMatch`, `RangeMatchOnly`, `SymbolMismatch`, and
  `NoMatch`.
- Valid no-symbol/no-match results are not CLI errors.
- No Metals, BSP, LSP, TASTy, MCP, graph storage, vector search, FP analyzer,
  directory indexing, dynamic SemanticDB generation, caching, daemonization, or
  project-wide analysis is added.

## FP Effect Summary Limits

- Effect summary reads one explicit `.scala` source file.
- It classifies declared method return types using syntax only.
- It tracks simple syntactic owner context for nearest enclosing `object`,
  `class`, `trait`, and `enum` definitions when safe.
- It extracts simple syntactic package declarations such as `package example`,
  `package example.foo`, and `package example:`.
- It does not infer missing return types.
- It does not resolve imports, type aliases, or fully qualified type identity.
- `ownerName` and `qualifiedName` are local syntactic context, not semantic
  symbols.
- `packageQualifiedName` is syntactic package/source context, not compiler
  symbol identity.
- Optional context fields may be absent when the analyzer cannot identify them
  conservatively.
- Brace package syntax, complex nested syntax, and multiline syntax may omit
  context fields instead of guessing.
- It classifies only the outer return type.
- Unrecognized parameterized return types are reported as `unknown`.
- No presentation compiler, SemanticDB, Cats Effect semantics, ZIO channel
  inference, typeclass solving, interprocedural analysis, or benchmark
  automation is added.

## Non-public selected-context prototype

A package-private SemanticDB prototype can inspect bounded compiler synthetics
for source-owned selected Scala 3 `given`, contextual-parameter, or legacy
`implicit` evidence at successful use sites. It has no public JSON schema, CLI
command, MCP tool, agent-policy workflow, dynamic SemanticDB generation,
automatic build, cache, or workspace index.

The prototype does not inventory every visible instance, explain rejected or
ambiguous candidates, diagnose a missing instance, or provide source-only
declaration catalogs. It is not a public product seam and is not part of the
public benchmark reproduction subset. Any future proposal requires a new
standalone contract and independently reproducible evidence.

## Deferred given and extension explanations

There is no public `explain-given` or `explain-extension` command. Bounded
evaluation preserves three different evidence owners:

- the Scala compiler owns missing, nested, and ambiguous contextual-search
  diagnostics and failed or ambiguous extension lookup;
- `symbol-at` and `infer-type` own successful extension symbol, owner,
  overload, and rendered signature evidence;
- the non-public SemanticDB prototype owns the narrower compiled-artifact fact
  of which contextual argument was inserted at a successful use site.

SemanticDB occurrences and symbols are not a complete search proof. TASTy can
be inspected comparatively after successful compilation, but is
compiler-version coupled and is not a required runtime dependency. Presentation
Compiler hover and definition evidence remain useful at successful sites;
standalone `didChange` did not produce diagnostics for the evaluated erroneous
fixtures. Metals/LSP remains a comparison surface with workspace/build state,
not a required CLI dependency.

These explanations remain deferred for insufficient incremental utility. A
future command must not merely reformat compiler messages, combine current
hover/navigation facts, or rename selected-context output.

## Usages contract boundary

The public CLI `usages` command is the only released aggregate exact-symbol
occurrence operation. Its caller supplies a strict, explicit, workspace-relative
source/artifact manifest with module, source-set, generated, and closed/open
scope. The engine scans ordinary exact SemanticDB `DEFINITION` and `REFERENCE`
occurrences, suppresses byte-identical artifacts, maps documents
conservatively, and uses SemanticDB MD5 for source freshness.

The eight domain states, typed operational failures, deterministic resource
bounds, deadline semantics, selectors, output privacy, and examples are the
current contract documented in [`docs/usages.md`](usages.md). In particular,
`NoUsagesObserved` is only bounded negative evidence inside one fresh, unique,
fully mapped, closed selected inventory; it is not a global-unused or
safe-deletion claim.

`usages` does not perform hidden discovery, automatic generation, synthetics,
selected-context analysis, override-family closure, dependency search, TASTy,
Metals integration, persistent indexing, or pagination. It has no MCP
projection. The existing status, coverage, mapping, symbol, point, and
reconciliation commands may provide factual inputs while leaving caller-owned
module, source-set, generated, and closed-scope declarations explicit.
