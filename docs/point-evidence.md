# Point evidence composition

`point-evidence` is the public, read-only composition of four existing semantic
operations for one source position: SemanticDB artifact discovery, conservative
artifact selection, a live Presentation Compiler point query, and conditional
static/dynamic reconciliation.

The CLI is the source of truth. The MCP tool `semantic_point_evidence` is a thin
adapter over the same CLI JSON report; it does not reimplement selection or
reconciliation.

## Request

```text
semantic-scala point-evidence --workspace <dir> --file <scala> --line <n> --col <n> [--json]
```

The workspace must exist. The source must be an existing regular `.scala` file
contained by that workspace. `line` and `col` are positive, one-based UTF-16
coordinates. MCP uses the same fields, with `file` workspace-relative.

The command reads existing SemanticDB artifacts and performs one live point
query. It does not run a build, generate SemanticDB, choose a build target,
refresh artifacts, or assess freshness.

## Result

JSON output has schema version `semantic-scala.point-evidence-result.v1` and
contains the full `semanticdb-for-source` discovery report plus:

- `selection`: one `SelectedUniqueParsed` artifact, or an explicit
  `NotSelectedNoCandidate`, `NotSelectedAmbiguous`,
  `NotSelectedPartialOrUnparseable`, or `NotSelectedUnavailable` state;
- `livePoint`: `Resolved`, `Unresolved`, or `Unavailable`, preserving either
  the complete point result or the failure reason; and
- `reconciliation`: `Completed` with the complete reconciliation result, or
  `NotAttempted` with one of `NoArtifactCandidate`,
  `AmbiguousArtifactCandidates`, `PartialOrUnparseableArtifactEvidence`,
  `ArtifactEvidenceUnavailable`, `LivePointUnavailable`, or
  `SelectedArtifactUnreadable` plus detail.

Only exactly one parsed discovery match is selected. No candidate is guessed
from ordering, path hints, module names, or source-set hints. Ambiguity and
partial or unparseable evidence remain visible.

`ExactMatch`, `RangeMatchOnly`, `SymbolMismatch`, and `NoMatch` retain their
existing meanings. In particular, a symbol mismatch is conflicting point
evidence; it is not proof that the SemanticDB artifact is stale. Artifact
availability is not source coverage, and a successful live query is not
whole-project compile proof.

## MCP

The exact eighth MCP tool is:

```text
semantic_point_evidence(workspace, file, line, col)
```

The adapter validates the workspace-relative source and positive position,
runs `semantic-scala point-evidence --workspace . ... --json` in the requested
workspace, and accepts only `semantic-scala.point-evidence-result.v1`. Adapter
or transport failure is distinct from every successful domain state above.
