---
name: semantic-scala
description: Choose and interpret semantic-scala compiler, test, type, effect, symbol, reconciliation, and SemanticDB evidence for Scala work. Use when build truth or a concrete semantic uncertainty could materially change a Scala diagnosis or patch.
---

# semantic-scala

## Purpose

Use this policy to decide whether ordinary inspection, compiler/test evidence,
or a narrow semantic query is warranted in a Scala project. Preserve what the
selected tool actually proves, its side effects, and remaining uncertainty.

The compiler, build, and tests remain the final correctness oracle. The
semantic commands are microscopes for specific questions, not substitutes for
whole-project validation.

## Non-goals

- Do not invoke semantic tools merely because Scala files are present.
- Do not assume an unsupported `semantic-scala` capability exists or modify a
  project merely to fabricate semantic evidence.
- Do not bypass point-evidence selection by guessing an artifact from path,
  module, or source-set hints.
- Do not claim canonical type or symbol identity from rendered hover text,
  display names, hints, or syntax-only summaries.
- Do not use this policy as permission to execute untrusted build code.

## Selection ladder

1. Read the relevant source, tests, and project instructions first.
2. If the question is build correctness, use `compile`, `errors`, or `test`.
3. If inspection and build evidence leave a concrete semantic uncertainty,
   state that uncertainty before querying.
4. Choose the narrowest command that answers it.
5. When the question is specifically which declaration the Presentation
   Compiler selects at one exact source position, use `symbol-at`; do not
   escalate to `point-evidence` merely because it is more comprehensive.
6. Use `point-evidence` when source-artifact discovery and unique selection,
   live point evidence, and conditional reconciliation are themselves
   decision-relevant, especially when caller-selected artifact routing is the
   uncertainty.
7. Prefer a configured, functioning MCP tool when that exact capability exists.
8. Do not repeat an expensive query unless inputs changed or the report records
   a specific reason.
9. Record the actual tool, bounded inputs, result, side effects, and uncertainty.

No semantic query is needed when source and existing tests already answer the
question or when only formatting, prose, file movement, or another non-semantic
change is involved.

## Capability matrix

The public CLI name is `semantic-scala`. It has more commands than the MCP
adapter. The MCP surface is exactly the eight tools named below.

| Question | CLI command | MCP tool | Evidence and limits |
| --- | --- | --- | --- |
| Does the project compile? | `compile [--sbt-project <id>] --json` | `semantic_compile` | Root or one validated selected-project invocation; domain `success: false` is a valid compile result. |
| Do relevant tests pass? | `test [--sbt-project <id>] --json` | `semantic_test` | Root or one validated selected-project test invocation; can execute project test code. |
| What compiler diagnostics should guide repair? | `errors [--sbt-project <id>] --json` | `semantic_errors` | Structured root or selected-project diagnostics; may rerun compilation. |
| What return wrapper is declared in this file? | `effect-summary --file <scala> --json` | `semantic_effect_summary` | Syntax-first declared return shape and conservative category, not inferred semantics. |
| What does the presentation compiler report at a point? | `symbol-at --file <scala> --line <n> --col <n> --json` | `semantic_symbol_at` | Dynamic point evidence; rendered or hover-like output is not canonical identity. |
| What symbols occur in an explicit SemanticDB file? | `symbols --semanticdb <file> --json` | `semantic_symbols` | Static canonical symbol strings and ranges from that file only. |
| Do dynamic and static symbol evidence agree? | `reconcile-symbol --file <scala> --line <n> --col <n> --semanticdb <file> --json` | `semantic_reconcile_symbol` | Exact or non-exact comparison; preserve the returned status and uncertainty. |
| What coherent point evidence is available without caller-selected artifact routing? | `point-evidence --workspace <dir> --file <scala> --line <n> --col <n> --json` | `semantic_point_evidence` | Full discovery, unique-parsed selection only, live result, and completed or typed not-attempted reconciliation. It does not assess freshness. |
| What type is rendered for an expression? | `infer-type --file <scala> --line <n> --col <n> ... --json` | none | CLI-only presentation-compiler evidence. `Resolved` carries rendered evidence; `Unresolved` is neutral absence. |
| What types are rendered for a bounded request batch sharing one sbt context? | `infer-type-batch --requests <json> --workspace <dir> --sbt-project <id> --sbt-configuration <name> --json` | none | CLI-only ordered batch; strict bounded input and one shared acquisition. |
| Which SemanticDB artifacts are available and what factual provenance is recorded? | `semanticdb-status --workspace <dir> --json` | none | CLI-only artifact inventory. Availability is not source coverage. |
| Which explicit artifacts contain one source? | `semanticdb-for-source --file <scala> --workspace <dir> --json` | none | CLI-only source-to-artifact candidates. Hints are not canonical build-target identity. |
| What source coverage is present in the inventoried artifacts? | `semanticdb-coverage --workspace <dir> --json` | none | CLI-only factual coverage inventory, not proof of complete build coverage. |

`help` and `version` are CLI utility commands. There is no public
`typeclass-summary`, `explain-given`, `explain-extension`, infer-type MCP tool,
or automatic semantic invocation.

## Side effects and approval

| Operation/context | Typical effects | Approval rule |
| --- | --- | --- |
| Source reading, `effect-summary`, or reading an existing explicit SemanticDB artifact | Can be read-only when all inputs already exist. | Follow the client's current read policy; do not infer approval for other actions. |
| `compile`, `errors`, or `test`, through CLI or MCP | Invokes the project build; may execute arbitrary build/plugin/test code and write outputs or caches. | Obtain any approval required for that exact invocation and workspace. |
| `infer-type` with manual `--classpath`, or a narrow runtime-only context | Avoids sbt acquisition but only proves behavior under the supplied bounded context. | Treat paths and dependencies as user-provided evidence, not project freshness proof. |
| sbt-backed `infer-type` or `infer-type-batch` with `fresh` | May execute arbitrary build code, compile, download dependencies, and modify project outputs or shared caches. `fresh` is the default. | Obtain approval for this build execution when the environment requires it. |
| sbt-backed mode with `refresh` | Has `fresh` effects and additionally publishes newly acquired private cache state for later reuse. | Approval must cover execution and cache publication. |
| sbt-backed mode with explicit `reuse` | Validates bounded cached evidence and never silently refreshes. It does not prove arbitrary sbt freshness. | Do not replace failure with `fresh` or `refresh` without a new explicit decision and any required approval. |
| SemanticDB status, lookup, or coverage | Reads existing artifacts; discovery can be broad within the named workspace but does not generate SemanticDB. | Keep the workspace bounded and respect sensitive artifact contents. |

Approval is request-local. Never assume that MCP initialization, tool
discovery, or approval for a previous request authorizes later build execution.

## Result interpretation

Separate three layers:

1. **Transport/infrastructure:** Was the tool found and did the process,
   transport, JSON parsing, and schema validation succeed?
2. **Adapter:** For MCP, `ok: true` means the adapter invocation and payload
   handling succeeded. Non-zero exits, malformed JSON, schema mismatch, or a
   transport error are adapter/infrastructure failures.
3. **Domain:** A parsed payload can report `success: false` for compile or test.
   That is useful domain evidence, not an MCP crash.

Apply these constraints:

- `Resolved` means a type rendering was produced under the recorded context. It
  is not canonical type identity or whole-project compile proof.
- `Unresolved` is neutral. It does not distinguish source error, incomplete
  classpath/context, unsupported hover behavior, or another cause.
- A successful point or type query does not prove the project compiles.
- SemanticDB artifact availability does not prove that every source is covered.
- Source-set, module, and provenance hints are warnings or heuristics unless
  the factual fields independently establish them; they are not canonical
  build-target identity.
- `ExactMatch` is stronger agreement evidence. `RangeMatchOnly`,
  `SymbolMismatch`, and `NoMatch` remain successful domain outcomes when the
  adapter succeeds; report them literally and preserve uncertainty.
- Point evidence selects only one unique parsed artifact. Preserve ambiguous,
  unavailable, partial/unparseable, live-unavailable, and unreadable-artifact
  states. A symbol mismatch does not by itself establish staleness.
- `effect-summary` is syntax-first. Its wrapper categories and names do not
  prove full effect semantics or compiler-selected intent.
- Warnings and hints must remain labeled as such, separate from factual fields.
- Selected-context/typeclass evidence is not available through the public
  CLI/MCP surface. Do not infer it from the existing point or symbol commands.

## Cost and latency

- Source inspection and existing-test inspection are usually cheapest.
- An explicit SemanticDB file query or one-file syntax summary is narrow and
  generally cheaper than build-backed work.
- Presentation-compiler queries can require context setup; manual classpaths
  are bounded but may be incomplete.
- `compile`, `errors`, `test`, and sbt-backed type inference may start sbt,
  resolve dependencies, compile, or run project code. Treat them as expensive.
- Prefer `infer-type-batch` only when multiple bounded point queries genuinely
  share the same sbt context; otherwise use one narrow `infer-type`.
- Do not rerun unchanged expensive calls for reassurance. Record why a retry is
  needed, such as a source edit, classpath change, cache-mode decision, or
  recorded transient transport failure.

## Fallback policy

1. Use a configured MCP tool when the needed command is one of the eight and
   the tool is functioning.
2. Use the staged CLI when no MCP equivalent exists, or after recording an MCP
   transport/adapter failure.
3. Label the fallback as CLI evidence and record the command used; never report
   it as MCP success.
4. Do not fall back from explicit cache `reuse` to `fresh` or `refresh`.
5. After failure, do not invent the missing semantic result. Continue with
   source/build evidence or report the uncertainty.

## Data handling and reporting

- Use repository-relative paths in durable reports when practical.
- Avoid exposing tokens, environment values, private absolute paths,
  dependency caches, raw reasoning, or large tool transcripts.
- Record only the bounded command, context, result, side effects, warnings, and
  uncertainty needed to support the current task.
- Do not copy source or semantic artifacts across repository boundaries unless
  the user has authorized it and the destination is appropriate for that data.

## Bounded examples

### Compiler error: semantic query unnecessary

After reading the failing source and test, run `errors --json`, apply the
smallest diagnostic-driven repair, then run `compile --json` and relevant
tests. Do not query symbols or inferred types unless a concrete uncertainty
remains.

### Inferred expression type

When an unannotated expression's compiler-rendered type determines the repair,
use one CLI-only point query:

```text
semantic-scala infer-type --file src/main/scala/example/Service.scala --line 18 --col 15 --workspace . --sbt-project core --sbt-configuration Compile --json
```

Report `Resolved` or `Unresolved`, recorded context, warnings, and limitations;
validate any patch with compile/test.

### Effect wrapper

When a method should preserve `F[Option[A]]` rather than flatten or substitute
the wrapper, query only its file:

```text
semantic-scala effect-summary --file src/main/scala/example/Repo.scala --json
```

Treat the declared wrapper as syntax-first evidence, then compile and test.

### Static/dynamic disagreement

When point evidence and an explicit SemanticDB occurrence disagree, use
`reconcile-symbol` with the exact source position and artifact. Report
`ExactMatch`, `RangeMatchOnly`, `SymbolMismatch`, or `NoMatch` without upgrading
the status.

### SemanticDB absent or uncertain

Run `semanticdb-status --workspace . --json` and
`semanticdb-for-source --workspace . --file <scala> --json` first. If matching
static evidence is absent and the user has approved build execution, one
explicit `semantic-scala compile --json` may be used to run the project's
ordinary root compile. If the user or build metadata already identifies one
project ID, `semantic-scala compile --sbt-project <id> --json` may instead run
that project's fixed ordinary `Compile` scope. The ID must match
`[A-Za-z][A-Za-z0-9_-]*`; it is not arbitrary sbt syntax or discovery. Either
build can execute project/plugin code, resolve
dependencies, populate caches, and write build outputs. It emits SemanticDB
only when the checked-in target build is already configured to do so.

After a successful compile, repeat the same status/source lookup or
`point-evidence` query. Preserve these outcomes separately:

- a matching artifact appeared: report its actual unique, ambiguous, or
  partial state; availability still does not establish coverage or freshness;
- compile succeeded but no matching artifact appeared: do not infer missing
  source, unsupported Scala, or that SemanticDB is impossible; stop rather
  than injecting compiler flags/plugins or mutating the build, and ask the
  project owner to select or enable an appropriate SemanticDB-producing build;
- compile failed: report build failure separately from SemanticDB absence.

If the ordinary root compile is too broad and no project is already known,
keep that scope uncertainty explicit rather than guessing a project or
substituting a private shell build. A successful selected compile proves only
that bounded project invocation, not whole-workspace correctness.
When artifacts do exist, use `semanticdb-coverage --workspace . --json` before
making inventory-scoped coverage statements.

### Explicit no-tool case

For a Markdown typo, comment rewording, formatting-only edit, or rename already
fully constrained by source and tests, use ordinary inspection and the relevant
lightweight checks. Do not invoke a semantic tool.
