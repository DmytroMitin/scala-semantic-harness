# Semantic tooling positioning

## Position

`scala-semantic-harness` is a Scala/FP-specific semantic evidence layer for
coding agents. It is not a general IDE, an LSP implementation, or "Serena for
Scala."

The project principle is:

> Use generic IDE/LSP tools for generic code intelligence; use
> `scala-semantic-harness` where Scala/compiler/FP-specific semantics provide
> additional decision-relevant evidence to coding agents.

In short: complement, do not duplicate, Metals, Serena, or JetBrains.

The CLI remains the source of truth. Its differentiated value is a bounded,
headless contract around:

- exact sbt project, target, configuration, classpath, and freshness context;
- explicit provenance, coverage, completeness, and resource limits;
- explicit uncertainty, ambiguity, staleness, and unavailable evidence;
- agreement or disagreement between compiler, Presentation Compiler, and
  SemanticDB evidence;
- deterministic agent-facing results;
- Scala-specific compiler semantics; and
- FP/typeclass abstractions or compiler/TASTy/inline/macro explanations only
  after their decision value is demonstrated.

Partial-compilation tolerance is not the primary differentiator. Interactive
compiler, IDE, and LSP tooling already answers many questions in incomplete or
locally broken source. The project differentiates itself by stating what its
evidence establishes and what it does not.

## Division of responsibility

| Surface | Primary role | Relationship to this project |
| --- | --- | --- |
| Metals/LSP | Scala-aware interactive navigation, hover, completion, diagnostics, and editor operations | Complementary baseline; do not reimplement its generic operations |
| JetBrains IDE MCP | Agent access to an IDE-backed project model and editor operations | Complementary comparison/integration surface |
| Serena LSP backend | Agent-oriented semantic operations backed by language servers | Complementary generic semantic layer |
| Serena JetBrains backend | Agent-oriented operations backed by JetBrains semantics | Complementary IDE-backed layer |
| `mcpls`, `mcp-language-server`, `lspi` | Thin MCP-to-LSP exposure | Prefer these or direct LSP integration for ordinary protocol operations |
| Scala compiler / Presentation Compiler | Definitive build diagnostics and live position-oriented Scala semantics, respectively | Evidence sources; preserve source and context provenance |
| SemanticDB | Compiled symbol identities and occurrences | Evidence source whose freshness and coverage must be explicit |
| BSP/build context | Targets, configurations, options, classpaths, and dependencies | Required context; inability to establish it is a material degraded state |
| `scala-semantic-harness` | Bounded Scala/FP-specific evidence and source reconciliation for agents | Complement the surfaces above when unique decision value is established |

This classification is architectural, not a claim that every listed product or
bridge has identical current capabilities. Ordinary feature support should be
verified at the point of a future integration decision.

### Delegate

Normally delegate mature generic operations:

- definition and ordinary reference navigation;
- completion and ordinary hover;
- rename/refactoring, formatting, and general source editing;
- call/type hierarchy;
- generic code actions; and
- generic diagnostic presentation.

This division of responsibility creates no commitment to implement these
operations.

### Complement

Complement generic tools when a bounded result can add reviewable evidence:

- exact build-target or classpath provenance;
- freshness, coverage, and completeness status;
- agreement or disagreement across semantic sources;
- source-specific uncertainty and resource limits; or
- a deterministic, headless contract suitable for an autonomous agent.

Complementary value can come from a better individual semantic primitive or
from better composition of primitives that already exist elsewhere. A bounded
harness operation may be worthwhile when it combines compiler, build, CLI,
IDE/LSP, or other evidence into one coherent agent-facing decision contract
with consistent provenance, freshness, completeness, uncertainty,
disagreement, ordering, and resource limits. This does not authorize
reimplementing the underlying generic operations.

### Implement only after admission

> Build a harness capability when controlled evidence shows that it materially
> improves an agent decision either because the semantic evidence itself is
> better than the available alternatives, or because the harness composes
> available evidence into a more effective, lower-friction, more coherent agent
> operation than the agent can obtain by orchestrating the underlying tools
> directly.

Scala specificity alone is insufficient. A proposal must identify a live
decision that existing evidence does not resolve, define a bounded contract,
and pass a separate utility/admission benchmark before implementation or
public exposure. Composition is not established merely by counting fewer
commands: it must be compared with a strong baseline that can use the relevant
compiler, IDE/LSP/MCP, and CLI tools directly.

A previously tested selected-context prototype demonstrates the negative side
of this rule: it remained internal after controlled agents did not use it.
`explain-given` and `explain-extension` proposals likewise remain deferred
because they have not shown enough incremental value beyond compiler
diagnostics and existing point queries. The public `usages` command
demonstrates the positive but bounded side: it exposes exact-symbol evidence
only through an explicit inventory, stale/ambiguous/incomplete states, and
resource limits, while MCP remains unchanged.

## Degraded project states

"Does not compile" is not one semantic state.

- A local syntax or type error does not invalidate every semantic query.
  Presentation Compiler, IDE, and LSP tooling can often recover useful facts
  elsewhere in the file or project.
- An upstream inference failure can make downstream types or resolutions less
  reliable.
- Missing outputs from a failed upstream module can remove compiled semantic
  facts needed by downstream modules.
- Failure to evaluate/import the build or establish the target and classpath is
  generally more serious than an ordinary compile error.
- SemanticDB can be missing, stale, inconsistent, or incomplete when relevant
  compilation has not produced current artifacts.

The contract rule is:

> Degraded-state semantics are part of the contract: expose which evidence
> source succeeded, failed, is stale, incomplete, ambiguous, or unavailable
> instead of silently equating missing evidence with semantic absence.

A successful result from one source must not silently imply that every other
source, target, or file was available. An empty answer is not a completeness
claim. Reconciliation should preserve disagreement rather than hiding it.

## Diagnostic-to-action evidence

The user-supplied Haskell Language Server Case Split example establishes a
useful design precedent:

```text
compiler diagnostic
    -> structured semantic evidence
    -> bounded repair/action candidate
```

The architectural lesson is that a compiler diagnostic can be structured input
to a repair decision, rather than prose for an agent to reinterpret. It does
not authorize a case-split command, exhaustive-match editor, compiler
quick-fix, or general code-action subsystem here.

Evidence ownership and edit ownership remain separate:

- an IDE/LSP layer may own ordinary editor actions and source edits;
- a future `semantic-scala` capability may expose compiler-backed,
  provenance-aware action evidence only if a separate admission gate proves
  unique agent value; and
- the caller remains responsible for deciding whether and how to edit unless a
  later, separately admitted contract says otherwise.

### Completeness and resource limits

The supplied Case Split precedent also shows that diagnostic-derived action
evidence inherits the diagnostic's bounds. In that example, missing-pattern
candidates inherit a compiler bound such as `-fmax-uncovered-patterns`.
Therefore a result cannot claim to contain "all missing cases" unless
completeness is independently established.

This matches existing project habits: distinguish evidence found from
`CoverageIncomplete`, `Truncated`, unresolved or ambiguous targets, and stale
or inconsistent artifacts. Any future diagnostic-action evidence must retain
its originating diagnostic, build/source context, completeness status, and
limits. No public diagnostic-action schema exists.

## Future comparison baseline

A separately admitted benchmark may compare:

1. shell/plain-file only;
2. compiler/test only;
3. Metals through LSP or MCP;
4. Serena;
5. JetBrains MCP; and
6. generic semantic tooling plus `scala-semantic-harness`.

Such a comparison must control tool-use attribution and environment confounds.
Tool exposure or invocation is not success. The useful outcomes are
decision-relevant semantic-tool use, compile/test success, iterations to green,
incorrect edits and over-edits, exact-symbol errors, contextual/implicit errors
where applicable, and unnecessary human intervention.

No such benchmark or integration is currently scheduled.

## Frozen current boundary

The public MCP registry remains exactly:

1. `semantic_compile`
2. `semantic_errors`
3. `semantic_test`
4. `semantic_effect_summary`
5. `semantic_symbol_at`
6. `semantic_symbols`
7. `semantic_reconcile_symbol`
8. `semantic_point_evidence`

The CLI remains the source of truth. `semantic_point_evidence` is the thin
adapter over the admitted `point-evidence` report; no persistent index or
automatic acquisition is added.
