# semantic-reconciliation

Snapshot-consistent reconciliation between static SemanticDB facts and dynamic
Presentation Compiler `symbol-at` results.

Current command surfaces:

```bash
semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> --json
semantic-scala point-evidence --workspace <dir> --file <path> --line <n> --col <n> --json
semantic-scala point-evidence --workspace <dir> --file <path> --line <n> --col <n> --sbt-project <id> --include-existing-internal-outputs --json
semantic-scala point-evidence --workspace <dir> --file <path> --line <n> --col <n> --sbt-project <id> --include-existing-internal-outputs --require-fresh-internal-outputs --json
```

`reconcile-symbol` captures source and artifact content once and never asks the
Presentation Compiler to reread the path. `point-evidence` adds conservative
source-to-artifact discovery and freshness-aware selection without accepting a
caller-selected artifact. Both recheck the source before returning.

Fresh evidence can produce `CompletedFresh`. Unverifiable evidence can produce
only `CompletedQualifiedUnverifiable`. Stale evidence remains reported but
produces `NotAttempted` / `StaleArtifact`, and source mutation produces
`NotAttempted` / `SourceChangedDuringRequest`. A stale point-evidence request
may still report current live point evidence; it never labels that combination
as completed reconciliation.

Nested `ReconciliationStatus` remains:

- `ExactMatch`
- `RangeMatchOnly`
- `SymbolMismatch`
- `NoMatch`

Only `ExactMatch` means the SemanticDB symbol and compiler symbol strings are
identical. `displayName` is not identity. None of these statuses proves a
whole-project compile.

The explicit target-aware v5 route retains v4 ownership and final integrity
gates while adding only already-present same-axis internal Compile outputs from
the bounded settings receipt. Its context order is selected output, ordered
internal outputs, then external dependencies. Missing and unsafe directories
do not enter the compiler context, and the report remains
`PartialExistingCompileOutputs`; no compile or arbitrary complete classpath is
claimed.

The explicit v6 route adds a strict freshness gate over the same v5 graph. It
reads existing same-axis `Compile / compileAnalysisFile` archives with the
published Zinc 1.12.1 persistence API and compares bounded source/product
inventories, relations, and content stamps. Exact configured source-root and
generator-list provenance comes from the non-running sbt receipt; configured
generators or managed-source residue fail closed. Archive, inventory, walk,
per-file, and aggregate content bounds prevent unbounded freshness reads. Only Fresh internal directories
enter the live compiler context. Stale, missing, corrupt, unsupported, unsafe,
incomplete, and generator-dependent states are typed and excluded without a
compile or generation fallback. V6 remains `PartialExistingCompileOutputs` and
does not claim whole-target or build freshness.

## Limits

- Source-paired and request-local only.
- Reads existing SemanticDB artifacts; it does not build or refresh them.
- Uses captured source content for the existing Presentation Compiler query.
- Does not normalize symbol strings.
- Does not silently choose ambiguous, partial, or unparseable artifacts.
- Content freshness is not build provenance or complete source coverage.
- No Metals, BSP, LSP, TASTy, graph storage, vector search, caching,
  daemonization, dynamic SemanticDB generation, or project-wide indexing.

The v1 reconciliation and point-evidence models remain frozen historical
schemas and do not carry the v2 snapshot/freshness guarantees.
