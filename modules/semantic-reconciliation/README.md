# semantic-reconciliation

Minimal reconciliation layer between static SemanticDB facts and dynamic
presentation compiler `symbol-at` results.

Current command surface:

```bash
semantic-scala reconcile-symbol --file <path> --line <n> --col <n> --semanticdb <path> --json
```

The module also contains the internal `PointEvidenceService` composition seam.
One request carries a workspace, source file, one-based line, and one-based
UTF-16 column. Its typed result retains the complete source-to-SemanticDB
discovery report, selects an artifact only for one parsed match, obtains one
live point result, and reconciles without querying the Presentation Compiler a
second time. There is no public command, JSON schema, or MCP tool for this seam.

`ReconciliationStatus` is encoded as a JSON string:

- `ExactMatch`
- `RangeMatchOnly`
- `SymbolMismatch`
- `NoMatch`

Only `ExactMatch` means the SemanticDB symbol and compiler symbol strings are
identical. `displayName` is not identity.

## Limits

- File-scoped only.
- Reads one explicit `.semanticdb` file.
- Uses the existing presentation compiler `symbol-at` query.
- Does not normalize symbol strings.
- Does not silently choose ambiguous, partial, or unparseable artifacts.
- Does not infer artifact freshness from a mismatch.
- No Metals, BSP, LSP, TASTy, MCP, graph storage, vector search, caching,
  daemonization, dynamic SemanticDB generation, or project-wide indexing.
