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
semantic-scala point-evidence --workspace <dir> --file <scala> --line <n> --col <n> [--json]
```

The workspace must exist. The source must be an existing regular `.scala` file
contained by that workspace. `line` and `col` are positive, one-based UTF-16
coordinates. MCP uses the same fields, with `file` workspace-relative.

The command captures the source bytes at request start, reads each candidate
SemanticDB artifact once, and gives the Presentation Compiler that captured
source content. It rechecks source identity before returning. It does not run a
build, generate SemanticDB, choose a build target, or refresh artifacts.

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
semantic_point_evidence(workspace, file, line, col)
```

The adapter validates the workspace-relative source and positive position,
runs `semantic-scala point-evidence --workspace . ... --json` in the requested
workspace, and accepts only `semantic-scala.point-evidence-result.v2`. Adapter
or transport failure is distinct from every successful domain state above.

The v1 schema remains frozen historical evidence. It did not assess freshness
and must not be interpreted as the current source-paired contract.
