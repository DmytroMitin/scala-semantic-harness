# `semantic-scala usages`

`usages` is a released public CLI capability in this repository. It performs
one duplicate-aware, freshness-aware, coverage-aware query for exact ordinary
SemanticDB occurrences across an explicit caller-declared manifest. It helps a
reviewer identify exact symbol edit sites—for example, distinguishing one
overload from same-spelled code before a rename—but it does not edit or
refactor source.

Release finalization here means that the repository's supported CLI boundary
is documented and validated. It does not imply an external package-registry
publication, a release tag, MCP availability, or automatic manifest creation.

## Command grammar

There are exactly two target modes and no aliases.

```text
semantic-scala usages --workspace <path> --manifest <relative.json> \
  --symbol <global-symbol> [--include-definitions] [--module <id>]... \
  [--source-set <id>]... [--include-generated] [--limit <1..500>] [--json]

semantic-scala usages --workspace <path> --manifest <relative.json> \
  --file <relative-source> --line <one-based-utf16> --col <one-based-utf16> \
  --semanticdb <declared-relative-artifact> [selectors] [--json]
```

- Global mode uses the exact stable SemanticDB symbol supplied to `--symbol`.
- Point mode resolves the symbol at one point using the named, already-declared
  SemanticDB artifact. `--file`, `--line`, `--col`, and `--semanticdb` must be
  supplied together.
- `--include-definitions` includes definitions in addition to references.
- Repeatable `--module` and `--source-set` values select exact manifest
  provenance identifiers.
- `--include-generated` admits declarations marked `generated: true`.
- `--limit` accepts 1 through 500 and only lowers the returned-occurrence cap.
  It does not increase or alter any other resource limit.
- `--json` selects the versioned machine-facing envelope. There is no schema
  negotiation flag.

Example global query:

```bash
modules/cli/target/stage/bin/semantic-scala usages \
  --workspace . \
  --manifest semantic-usages.json \
  --symbol 'example/Service#run().' \
  --include-definitions \
  --module core \
  --source-set main \
  --limit 100 \
  --json
```

Example point query:

```bash
modules/cli/target/stage/bin/semantic-scala usages \
  --workspace . \
  --manifest semantic-usages.json \
  --file src/main/scala/example/Service.scala \
  --line 12 \
  --col 7 \
  --semanticdb out/META-INF/semanticdb/src/main/scala/example/Service.scala.semanticdb \
  --json
```

## Manifest v1

The input contract identifier is `semantic-scala.usages-manifest.v1`. The
manifest is the sole declaration inventory. Assuming the named files already
exist beneath the workspace, this is a minimal valid example:

```json
{
  "schemaVersion": "semantic-scala.usages-manifest.v1",
  "inventoryClosed": true,
  "sources": [
    {
      "path": "src/main/scala/example/Service.scala",
      "module": "core",
      "sourceSet": "main",
      "generated": false
    }
  ],
  "artifacts": [
    {
      "path": "out/META-INF/semanticdb/src/main/scala/example/Service.scala.semanticdb",
      "module": "core",
      "sourceSet": "main",
      "generated": false
    }
  ]
}
```

Every source and artifact declares a normalized workspace-relative path plus
caller-supplied module, source-set, and generated provenance. The command does
not infer provenance from directory names. Declared paths must be regular,
symlink-free files contained by the workspace and must already exist. Source
paths end in `.scala` or `.java`; artifact paths end in `.semanticdb`.

`inventoryClosed: true` is the caller's assertion that the declarations cover
the selected review scope. It is not discovered build truth, dependency
completeness, runtime reachability, or a claim about every possible usage in a
workspace or dependency graph. `false` records an open inventory and prevents
a complete zero result.

Declaration paths are unique. Distinct artifact paths remain declared even
when their raw bytes are identical, allowing `duplicateGroups` and coverage
counters to report and suppress duplicate content deterministically. Missing
declared files are operational failures; missing, unmapped, ambiguous, stale,
or occurrence-less evidence inside admitted artifacts remains visible through
state, reasons, freshness, and coverage. None of these conditions triggers a
build or refresh. This release has no automatic manifest generator.

The maintained production schemas are:

- [`usages-manifest.v1.schema.json`](../modules/cli/src/main/resources/semantic-scala/schemas/usages-manifest.v1.schema.json)
- [`usages-result.v1.schema.json`](../modules/cli/src/main/resources/semantic-scala/schemas/usages-result.v1.schema.json)
- [`usages-failure.v1.schema.json`](../modules/cli/src/main/resources/semantic-scala/schemas/usages-failure.v1.schema.json)

These closed v1 schemas are frozen for this release boundary.

## Domain states and consumer action

Every valid domain result has exactly one of eight states and exits 0.

| State | Meaning and suggested action |
| --- | --- |
| `EvidenceFound` | Exact ordinary occurrences were returned from complete selected evidence. Review the returned definition/reference sites. |
| `NoUsagesObserved` | Zero matching occurrences were observed in one fresh, unique, fully mapped, closed selected declared scope with no truncation. Treat this only as bounded negative evidence. |
| `CoverageIncomplete` | Some declared or selected coverage is open, missing, unmapped, ambiguous, or otherwise incomplete. Inspect `reasons` and `coverage` before acting. |
| `TargetAmbiguous` | Point mode resolved more than one exact target. Choose a less ambiguous point or correct the selected artifact. |
| `TargetUnresolved` | Point mode resolved no target. Check coordinates, artifact choice, and freshness. |
| `ArtifactStaleOrInconsistent` | Source digest or cross-artifact metadata evidence is stale or conflicting. Re-establish inputs outside `usages`, then create a reviewed manifest and rerun. |
| `Truncated` | A resource cap or the cooperative semantic deadline was hit. Treat returned evidence as partial and inspect `limits.hits`. |
| `UnsupportedConstruct` | The requested identity is outside v1, currently an explicit local symbol in global mode. Use point mode when document-local evidence is appropriate. |

`NoUsagesObserved` never means globally unused and does not by itself authorize
safe deletion. It is valid only for zero exact ordinary occurrences within the
fresh, unique, fully mapped, closed selected declared manifest scope shown in
the result.

Evidence quality is explicit:

- `coverage` records declared/selected/excluded counts, mapping and freshness
  counts, ordinary occurrence counts, and duplicate-copy facts;
- occurrence `freshness` distinguishes `Fresh`, `Stale`, `MissingDigest`,
  `Unmapped`, and `AmbiguousMapping`;
- `duplicateGroups` records equal raw artifact content at distinct paths;
- typed `reasons` distinguish incomplete and inconsistent evidence;
- `limits.hits` identifies truncation, including deadline, document, scan,
  return, warning, duplicate, and result-evidence limits;
- bounded warning text is supplemental and does not replace typed state or
  reason fields.

## Coordinates and identity

Point inputs are one-based UTF-16 line and column coordinates. Result target
and occurrence ranges are zero-based UTF-16.

A stable global SemanticDB symbol is the primary identity in global mode and
for a point that resolves globally. A point-selected local symbol is
document-local: JSON reports `LocalDocumentOnly` and the neutral
`DocumentLocal` marker plus bounded document evidence, never a raw local ID.

## Resource and deadline boundary

The JSON result reports effective limits and any hits. Current hard bounds are:

| Resource | Bound |
| --- | ---: |
| Manifest content | 16 MiB; read with one additional detection byte before over-limit rejection |
| Declared sources | 50,000 |
| Declared artifacts | 256 |
| Parsed documents | 4,096 |
| Aggregate raw artifact bytes | 256 MiB |
| One artifact | 16 MiB |
| One source | 8 MiB |
| Scanned ordinary occurrences | 1,000,000 |
| Returned occurrences | 500; `--limit` may only lower this |
| Result evidence | 1 MiB |
| Warnings | 100 |
| Duplicate groups | 100 |
| Sample paths per duplicate group | 20 |
| Selector values | 256 |
| Strings | 1,024 UTF-8 bytes |
| Paths and document URIs | 4,096 UTF-8 bytes |
| Semantic occurrence processing | 30-second cooperative deadline |

Manifest admission happens before `UsagesBySymbolSpike.run`. It is
hard-bounded by bytes, declaration counts, string/path checks, filesystem
containment checks, and linear fail-fast traversal, but it is not covered by
the semantic engine's 30-second deadline. The cooperative deadline begins at
semantic occurrence-engine entry. It is not a hard whole-process wall-clock
limit, does not interrupt a bounded operation mid-call, and is not a JVM-RSS
guarantee.

## Output, exits, and privacy

The JSON contracts are:

- domain result: `semantic-scala.usages-result.v1`;
- operational failure: `semantic-scala.usages-failure.v1`.

All eight domain states exit 0. Operational failures exit 1 and use exactly
these kinds: `InvalidInput`, `UnsafeFilesystem`, `IoFailure`, `ParseFailure`,
`TimeoutBeforeTargetResolution`, and `InternalInvariant`.

In JSON mode, either a valid domain result or a typed operational failure is
one JSON object on stdout with empty stderr. In human mode, domain results are
written to stdout; an operational failure is an empty stdout plus one concise,
sanitized stderr message.

Inputs and retained evidence are workspace-relative and privacy-bounded. The
result does not expose source bodies, absolute machine paths, classpaths,
caches, environment values, credentials, build logs, raw compiler trees, raw
local IDs, or unsafe document URIs. Filesystem validation rejects absolute,
unnormalized, traversal, backslash, missing/non-regular, and symbolic-link
paths rather than following them outside the workspace.

## Explicit non-goals

`usages` does not:

- discover artifacts or sources inside the command;
- generate SemanticDB;
- run a build;
- refresh artifacts;
- resolve dependencies;
- maintain a hidden persistent index, cache, daemon, pagination session, or
  workspace state;
- inspect synthetics, inferred/desugared references, selected contexts, TASTy,
  or Presentation Compiler/Metals workspace usages;
- compute override or dispatch closure;
- use textual fallback;
- prove runtime reachability, dependency completeness, whole-workspace truth,
  global unusedness, or safe deletion.

Acquiring and reviewing the source/artifact inventory remains an explicit
caller workflow outside this command.

## Relationship to other commands

| Command | Responsibility |
| --- | --- |
| `semanticdb-status` | Inventories existing SemanticDB artifacts and factual artifact metadata. |
| `semanticdb-coverage` | Reports source/artifact coverage facts for its own discovered inventory. |
| `semanticdb-for-source` | Suggests existing artifact candidates for one source. |
| `symbols` | Reads symbols and occurrences from one explicit SemanticDB artifact. |
| `symbol-at` | Provides presentation-compiler point semantics. |
| `reconcile-symbol` | Compares compiler point evidence with SemanticDB identity. |
| `usages` | Aggregates exact ordinary occurrences across the explicit manifest with duplicate, freshness, coverage, and limit evidence. |

The earlier commands may help a caller construct and review a manifest, but
`usages` does not silently absorb their discovery, coverage, compiler, or
reconciliation responsibilities.

## Release boundary

The released capability is CLI-only and uses the frozen
`semantic-scala.usages-manifest.v1`, `semantic-scala.usages-result.v1`, and
`semantic-scala.usages-failure.v1` contracts. The MCP registry remains exactly
eight tools and does not include `usages`. Automatic acquisition and every
other non-goal above require separate design, evidence, and admission.
