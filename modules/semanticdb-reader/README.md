# semanticdb-reader

Fixture-first SemanticDB parsing for `scala-semantic-harness`.

This module reads one explicit `.semanticdb` file and converts it into
repo-owned models:

- `SemanticFileSummary`
- `SemanticSymbol`
- `SemanticOccurrence`
- `SemanticRange`

It also provides read-only workspace discovery through `SemanticdbStatus` and
`SemanticdbStatusV2`, plus conservative source-to-candidate mapping through
`SemanticdbForSource` and factual source inventory coverage through
`SemanticdbCoverage`.

Status v1 remains the default projection and preserves its first-document
fields. Opt-in v2 parses every `TextDocument`, reports per-document and total
counts, raw file size and `sha256:<lowercase-hex>` content hashes, and groups
byte-identical artifacts. Candidates are ordered by workspace-relative path
and capped at 200 by default only after complete aggregate and duplicate-group
facts are computed. Duplicate summaries contain groups with at least two
members; singleton hashes contribute to `uniqueContentFiles` but are not listed.
The lexicographically first member is a reporting representative, not a source
selection preference. V2 `artifactStatus` describes parseability and
`coverageStatus` is currently `NotAssessed`.

`SemanticdbCoverage` recursively inventories regular `.scala` and `.java`
files outside `.git`, `.idea`, `.metals`, `.bloop`, `.scala-build`, `target`,
`out`, and `node_modules`. It reuses unbounded all-document inspection, parses
one representative per raw-SHA-256 content, and preserves exact-copy counts and
bounded path samples. Matching uses exact URIs or unique multi-segment URI
suffixes; basename-only URIs are rejected. Source and unmatched-document arrays
are bounded only after complete classification. Its statuses describe only the
explicit workspace inventory, not freshness or build-target completeness.

The unbounded artifact inventory also assigns internal copy-level
`SemanticdbArtifactHints`. A pure classifier recognizes structured fixture,
target main/test, copied test-resource, and Bloop/Metals path families and
returns independently qualified origin, role, source-set, language, output,
producer, module, configuration, version, and target-directory hints with
content-derived evidence IDs. Path classifications are heuristic; unknown
dimensions remain explicit, and no canonical build-target identity is inferred.
The current public status, coverage, and mapping schemas do not encode these
hints or use them for matching or selection. Git, Bloop/BSP metadata
enrichment, generated-source classification, and cross-Scala-version testing
remain deferred.

The reader preserves canonical SemanticDB `symbol` strings for tool identity and
also exposes `displayName` for human-readable output. Display names are not
treated as unique identifiers.

## Non-public selected-context prototype

`TypeclassSummarySpike` is a package-private API over one explicit
single-document SemanticDB file. It examines bounded compiler synthetics and
reports only selected arguments that can be corroborated by source-owned
`GIVEN` or `IMPLICIT` `SymbolInformation`. Each use site can retain a bounded
set of referenced global symbols, selected-context display/style/type facts,
and declaration ranges.

The spike has explicit `EvidenceFound`, `NoEvidence`, and `Truncated` statuses.
It accepts at most 4 MiB, scans at most 1,024 synthetics, returns at most 256
use sites, and bounds selected contexts, reference symbols, identifier lengths,
and display names. Source bodies, raw signatures, absolute paths, environment
values, and unstable local SemanticDB IDs are not result fields.

This is deliberately not the planned broad `typeclass-summary`. It does not
list all visible instances, prove typeclass definitions, explain candidate
rejection, or replace compiler diagnostics for missing/ambiguous instances.
There is no CLI, JSON schema, MCP tool, SemanticDB generation, cache, or
workspace index.

The prototype is not part of the public CLI, MCP registry, agent skill, or
public benchmark method. External users should not invoke or benchmark it as a
product capability. Any future public proposal would require a standalone
contract, independently reproducible fixtures, and separate admission.

## Usages-by-symbol core and public CLI bridge

`UsagesBySymbolSpike` is a package-private stateless engine over an explicit
caller-declared source and SemanticDB inventory. It resolves either one stable
global symbol or one one-based UTF-16 point against one explicit target
artifact. It scans only ordinary exact `DEFINITION` and `REFERENCE`
occurrences, hashes raw artifacts for deterministic duplicate suppression,
maps documents conservatively, and uses SemanticDB MD5 for source freshness.

The eight domain states distinguish evidence, bounded zero, incomplete
coverage, ambiguous/unresolved targets, stale/inconsistent artifacts,
truncation, and unsupported targets. Operational validation, filesystem, I/O,
parse, pre-resolution timeout, and invariant failures remain outside those
states. Explicit local symbols are unsupported; point-selected locals are
document-only and privacy-neutral.

The hard limits include 256 artifacts, 4,096 documents, 50,000 sources, 256 MiB
aggregate artifact bytes, 1,000,000 scanned and 500 returned occurrences, 1 MiB
of deterministic result evidence, and a 30-second monotonic deadline, with
additional per-file, string, selector, warning, and duplicate bounds. One
absolute deadline starts at `run` entry and is checked through preparation,
bounded read/parse operations, point resolution, freshness, scanning, output
bounding, and finalization. Blocking reads/parses are checked immediately
before and after but are not interrupted mid-call; the spike claims neither a
hard wall-clock kill nor a hard JVM RSS ceiling. It does not discover or
generate artifacts and has no MCP, build, cache, or index integration. A
narrow CLI bridge supplies a strict explicit v1 manifest loader, versioned
result/failure transport models, exhaustive typed reason mapping, and
deterministic human/JSON rendering. The public adapter does not duplicate or
broaden the occurrence algorithm. Synthetics, selected contexts,
override-family closure, TASTy, Metals, Presentation Compiler, and text fallback
remain excluded.

## Fixture

The initial checked-in fixture lives at:

```text
src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb
```

It is read directly during `sbt test`. Tests do not regenerate it.

The selected-context prototype's source matrix lives under
`src/test/resources/typeclass-summary-fixtures/`. Its generated Scala 3
SemanticDB fixture is separate from the original reader-fixture inventory.
The failing ambiguous and missing-instance sources are diagnostic fixtures and
are not compiled as module test sources.

## Limits

- Explicit symbol inspection still accepts a single `.semanticdb` file path.
- Workspace discovery scans existing `.semanticdb` files and source mapping
  uses full URI/path suffixes only; it does not infer identity from filenames,
  packages, or display names.
- Status v2 does not assess source coverage. The separate coverage command does
  not expose internal provenance hints, assess freshness/build targets, or
  select an artifact for higher-level source queries. `semanticdb-for-source`
  remains v1.
- Does not generate SemanticDB dynamically.
- The internal selected-context spike requires one explicit SemanticDB document
  and ignores selected arguments whose `GIVEN`/`IMPLICIT` metadata is not owned
  by that document.
- The internal usages spike trusts only the explicit caller inventory and
  cannot turn open, selected-out, unmapped, ambiguous, missing-digest, or stale
  evidence into a complete zero.
- Does not parse TASTy.
- Does not depend on `sbt-runner`, Metals, BSP, MCP, graph storage, vector
  search, or FP analyzers.
