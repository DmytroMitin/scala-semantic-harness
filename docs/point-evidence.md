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
semantic-scala point-evidence --workspace <dir> --file <scala> --line <n> --col <n> [--sbt-project <id>] [--sbt-scala-version <version>] [--sbt-java-home <absolute-directory>] [--include-existing-internal-outputs [--require-fresh-internal-outputs]] [--json]
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
semantic_point_evidence(workspace, file, line, col[, sbtProject, sbtScalaVersion, sbtJavaHome, includeExistingInternalOutputs, requireFreshInternalOutputs])
```

The adapter validates the workspace-relative source and positive position.
Without target inputs it runs the v2 CLI route and accepts only
`semantic-scala.point-evidence-result.v2`; with `sbtProject` and the internal
output opt-in omitted/false it forwards the target options and accepts only
`semantic-scala.point-evidence-result.v4`.
Optional boolean `includeExistingInternalOutputs=true` requires `sbtProject`
and selects only `semantic-scala.point-evidence-result.v5`. Omission or false
does not change the v2/v4 routes.
Optional `requireFreshInternalOutputs=true` requires both the project and
`includeExistingInternalOutputs=true`, then accepts only
`semantic-scala.point-evidence-result.v6`. Omission or false preserves the
v2/v4/v5 route selected by the other fields.
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
other target or platform output is inferred. Live queries use the harness's
linked Scala 3.9.0 Presentation Compiler; they do not switch to or replay the
target compiler version, flags, compiler plugins, or plugin lifecycle. Static
selection continues to describe the selected target SemanticDB artifact.
Public JSON reports relative roots, presence and entry counts, and redacted JDK
context; it never exposes raw classpaths, dependency-cache paths, or Java home.

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

## Explicit internal-existing-output v5

Adding `--include-existing-internal-outputs` to a valid target-aware request
emits `semantic-scala.point-evidence-result.v5`. The flag is a boolean presence
flag, accepts no value, and requires `--sbt-project`. V5 preserves the v4
selected-project receipt and adds a bounded ordered receipt for admitted
internal Compile dependencies. Its basis is
`ExistingSelectedAndInternalCompileOutputsPlusExternalDependencies` and its
completeness is always `PartialExistingCompileOutputs`.

The acquisition lifecycle reads the selected project's
`thisProject.dependencies` setting and follows dependencies only, never
aggregates. It performs deterministic depth-first traversal in declared edge
order, deduplicates shared transitive project refs, and terminates cycles before
acquiring output settings. A missing or unavailable class-directory setting is
typed without truncating descendants already admitted by that graph. The output
phase reads only each admitted dependency's `Compile / classDirectory` setting. Default
mapping and unambiguous `Compile->Compile` mappings are admitted. Test-only,
non-Compile, unsupported, and ambiguous mappings are excluded with typed
provenance instead of guessed. This is a bounded Compile-to-Compile policy, not
general sbt configuration algebra.

The live context order is the selected existing class directory, then present
admitted internal directories in receipt order, then the existing v4 external
dependencies. Every admitted dependency remains in the receipt with requested
and effective Scala axes, configuration, relative expected directory, mapping
and direct/transitive provenance, acquisition effect, and either
`PresentIncluded`, `AbsentNotIncluded`, or conservative `UnavailableUnsafe`
state. Only present safe same-axis directories contribute to the Presentation
Compiler context. Counts distinguish admitted, present, absent, unsafe, and
excluded dependencies without claiming an arbitrary complete classpath.

V5 does not request selected or dependency compilation, `products`,
`exportedProducts`, `fullClasspath`, or `internalDependencyClasspath`; it does
not create missing outputs or replay target compiler options/plugins. The
receipt retains `buildPerformed=NotRequested`,
`TargetSourceOutputsNotRequested`, and per-dependency
`DependencySourceOutputsNotRequested`. Checked-in build/plugin loading,
dependency resolution, task evaluation for settings, and metadata/cache writes
remain possible. A resolved v5 point is bounded evidence under this partial
existing-output context, not whole-project compile, generated-semantics, or
workspace-completeness proof.

## Strict internal-output freshness v6

Adding `--require-fresh-internal-outputs` to the explicit v5 request emits
`semantic-scala.point-evidence-result.v6`. The second presence flag requires
both `--sbt-project` and `--include-existing-internal-outputs`. V6 retains the
same graph, order, axis, ownership, and partial-context boundaries, but reads
each admitted dependency's existing same-axis
`Compile / compileAnalysisFile` path plus the configured `sourceDirectories`,
`unmanagedSourceDirectories`, `managedSourceDirectories`, and the cardinality
of the `sourceGenerators` setting in the same bounded sbt lifecycle. Reading
that generator setting inspects task objects; it does not execute them.

The reader uses the published Zinc 1.12.1 persistence/analysis API inside one
on-demand bounded JDK 21 child per v6 request. All admitted internals are sent
as one batch through `semantic-scala-zinc-freshness-worker.v1`; no child is
launched per dependency. The exact Scala 2.13.18/Zinc 1.12.1/JNA 5.14.0 graph
is resolved cache-first. First uncached use may acquire it from Maven Central
and populate the Coursier cache; a populated cache can run offline. A cold
offline acquisition failure, timeout, nonzero exit, malformed/partial result,
or protocol mismatch maps affected internals to fail-closed
`Unverifiable / UnsupportedAnalysisFormatOrVersion` and never to Fresh.
V2, v4, and v5 do not resolve or launch this worker. The reader accepts
the supported binary analysis container and compiler-axis family only; a
missing path/file, corrupt archive, unsupported layout/version, or axis
mismatch is `Unverifiable`. It does not decode private archive bytes itself,
request compilation, generate sources, or fall back to another axis.

`Fresh` means every safely represented source recorded by the admitted
analysis still has the recorded content stamp, every recorded product is
present beneath the dependency class directory with the recorded content
stamp, source/product relations are internally consistent, the relation
product set exactly equals the bounded current regular-file inventory beneath
the dependency class directory, and the
bounded inventory of the exact configured unmanaged source roots equals the
recorded source set. The configured all-source roots must equal the union of
the configured unmanaged and managed roots. Any configured source generator,
any current source under a managed root, unavailable layout provenance, or an
unsafe/outside root yields `GeneratedOrManagedSourceUnbounded` instead of
Fresh. This deliberately refuses to run a generator.

The reader accepts at most a 64 MiB analysis archive, 16 ZIP entries, 128 MiB
per declared entry, and 256 MiB declared uncompressed aggregate. Parsed and
current inventories allow at most 50,000 source/product/relation entries and
100,000 walked filesystem entries. Content hashing reads at most 8 MiB per
file and 256 MiB per source or product inventory. Exceeding a bound or an I/O
race becomes typed `Unverifiable`, never Fresh.

A content mismatch for a recorded source or product is `Stale`. Missing
expected products, unsafe/outside paths, incomplete or unbounded inventories,
inconsistent relations, and unsupported generated/managed layouts are
`Unverifiable`. Only `PresentFreshIncluded` contributes to the live Presentation
Compiler context. Stale, Unverifiable, absent, and unsafe entries remain typed
in the receipt but do not contribute. The result stays
`PartialExistingCompileOutputs`; Fresh does not mean that the selected target,
whole build, arbitrary configurations, generated semantics, or workspace is
fresh or complete. File mtimes, directory timestamps, Git state, and mere
existence are not freshness authority. Public JSON contains only safe relative
analysis paths and aggregate counts, never raw Zinc databases or private
absolute paths.

The worker uses at most a 512 MiB heap, a 30-second whole-process deadline,
bounded cleanup, 1 MiB request/response limits, and 4 KiB field limits. The
normal staged/runtime classpath no longer contains the Zinc graph solely for
v6. This is a normal distribution/classpath reduction, not a claim that total
disk use falls after the separate on-demand cache is populated.
