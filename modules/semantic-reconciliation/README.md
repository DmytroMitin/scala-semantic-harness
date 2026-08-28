# semantic-reconciliation

Snapshot-consistent reconciliation between static SemanticDB facts and dynamic
Presentation Compiler `symbol-at` results.

Current command surfaces:

```bash
semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> --json
semantic-scala point-evidence --workspace <dir> --file <path> --line <n> --col <n> --json
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
