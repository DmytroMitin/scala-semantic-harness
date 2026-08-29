# Point evidence composition

`point-evidence` is the public, read-only composition of SemanticDB artifact
discovery, content-based freshness assessment, conservative artifact selection,
one Presentation Compiler point query, and conditional static/dynamic
reconciliation.

The CLI is the source of truth. The MCP tool `semantic_point_evidence` is a thin
adapter over the same CLI JSON report; it does not reimplement selection,
freshness, or reconciliation.

## Request

```text
semantic-scala point-evidence --workspace <dir> --file <scala> --line <n> --col <n> [--sbt-project <id>] [--sbt-scala-version <version>] [--sbt-java-home <absolute-directory>] [--json]
```

The workspace must exist. The source must be an existing regular `.scala` file
contained by that workspace. `line` and `col` are positive, one-based UTF-16
coordinates. MCP uses the same fields, with `file` workspace-relative.

The command captures the source bytes at request start, reads each candidate
SemanticDB artifact once, and gives the Presentation Compiler that captured
source content. It rechecks source identity before returning. Without target
options it does not run sbt, generate SemanticDB, choose a build target, or
refresh artifacts.

## Result

JSON output has schema version `semantic-scala.point-evidence-result.v2` and
contains the complete v2 `semanticdb-for-source` discovery report plus:

- `selection`: `SelectedFresh`, `SelectedUnverifiable`, or an explicit
  `NotSelectedStale`, `NotSelectedNoCandidate`, `NotSelectedAmbiguous`,
  `NotSelectedPartialOrUnparseable`, or `NotSelectedUnavailable` state;
- `livePoint`: `Resolved`, `Unresolved`, or `Unavailable`, preserving either
  the complete point result or the failure reason; and
- `reconciliation`: `CompletedFresh`,
  `CompletedQualifiedUnverifiable`, or `NotAttempted` with a typed reason and
  detail.

Fresh means only that the captured source content agrees with the captured
SemanticDB document using valid SemanticDB MD5, exact embedded text fallback,
or both consistently. It does not prove that a build ran, that the whole source
set is covered, or that the project compiles. Mtimes are descriptive and never
authoritative.

Stale candidates remain visible in discovery and selection, but cannot produce
completed reconciliation. Unverifiable evidence remains explicitly qualified;
it is never silently promoted to fresh. If the source changes during the
request, `SourceChangedDuringRequest` supersedes otherwise successful work and
the command withholds completed reconciliation.

Only exactly one mapped parsed discovery candidate is eligible for selection.
No candidate is guessed from ordering, module names, or source-set hints.
Ambiguity and partial or unparseable evidence remain visible. The command may
still perform a live point query when static evidence is stale so callers can
see both current live evidence and the stale-artifact gate.

`ExactMatch`, `RangeMatchOnly`, `SymbolMismatch`, and `NoMatch` retain their
existing nested reconciliation meanings. They are reachable only under fresh
or explicitly qualified-unverifiable evidence. Artifact availability is not
source coverage, and a successful live query is not whole-project compile
proof.

## MCP

The exact eighth MCP tool is:

```text
semantic_point_evidence(workspace, file, line, col[, sbtProject, sbtScalaVersion, sbtJavaHome])
```

The adapter validates the workspace-relative source and positive position.
Without target inputs it runs the v2 CLI route and accepts only
`semantic-scala.point-evidence-result.v2`; with `sbtProject` it forwards the
target options and accepts only `semantic-scala.point-evidence-result.v4`.
`sbtScalaVersion` and `sbtJavaHome` require `sbtProject`. Adapter or transport
failure is distinct from every successful domain state above.

The v1 schema remains frozen historical evidence. It did not assess freshness
and must not be interpreted as the current source-paired contract.

## Opt-in target-aware v4

Adding `--sbt-project <id>` fixes the target configuration to `Compile` and
emits `semantic-scala.point-evidence-result.v4`. Optional
`--sbt-scala-version` selects a strictly validated axis and must match the
effective receipt axis. It and optional `--sbt-java-home` require a project
selector. Omitting target options preserves the v2 schema and behavior exactly,
including `NotSelectedAmbiguous` when shared sources map to multiple artifacts.

The v4 request acquires one fixed partial-existing-output point-context receipt
in one bounded sbt lifecycle. The receipt owns the selected project, fixed
Compile configuration, requested/effective Scala axis, class directory,
authoritative SemanticDB output root, target external dependencies, and bounded
selected-JDK context. It never requests target compilation, `fullClasspath`,
products, or exported products. Checked-in sbt build/plugin code still executes
and may resolve dependencies or populate build metadata and caches.

Workspace discovery and target ownership remain separate facts. V4 retains
the complete workspace-wide v2 discovery report, then canonically checks each
existing artifact against the receipt's authoritative SemanticDB root. Only
one safely target-owned Fresh candidate, or one explicitly qualified
Unverifiable candidate, may be selected. Zero, multiple, outside, stale,
symlink-unsafe, unrepresentable-root, source-changed, and acquisition-failed
states are typed non-selection and cannot yield completed reconciliation.

Live point evidence uses only the selected existing class directory when it is
present, followed by distinct target external dependencies. Missing class
output is omitted and never triggers compilation. The report always exposes
`PartialExistingOutputs`, so `Resolved` does not imply a complete target
classpath and `Unresolved` may reflect missing internal project products. No
other target or platform output is inferred. The harness Presentation Compiler
does not replay the target compiler version, flags, compiler plugins, or plugin
lifecycle. Public JSON reports relative roots, presence and entry counts, and
redacted JDK context; it never exposes raw classpaths, dependency-cache paths,
or Java home.

Every request starts a fresh sbt lifecycle, so it cannot inherit an interactive
`++` selection from an earlier process. Requested and effective axes and their
match status are explicit; omission uses the build default in that lifecycle.

The exact eighth MCP tool accepts optional `sbtProject` and dependent
`sbtScalaVersion`/`sbtJavaHome`. Without target inputs it requires v2; with a
project it requires v4. The MCP
registry remains exactly eight tools. Source mapping remains CLI-only, and
direct `reconcile-symbol` remains explicit-artifact, v2, and target-independent.

The v3 schema and models remain frozen historical/review evidence and are no
longer emitted by the current target-aware CLI or MCP route.
