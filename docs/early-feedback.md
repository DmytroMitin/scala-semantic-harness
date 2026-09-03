# Early real-project feedback

The next evidence milestone for semantic-scala is feedback from real Scala
projects and concrete decisions. This guide is for technically sophisticated
Scala users and coding-agent users who can compare the harness with the strong
ordinary tooling they already use. It is not a feature-wishlist template, and
none of the examples below is automatically a demonstrated product advantage.

## Smallest evaluation path

1. Install the supported alpha-2 CLI, MCP server, and optional skill using
   [`agent-onboarding.md`](agent-onboarding.md).
2. Pick one concrete decision before choosing a semantic command.
3. Use the narrowest relevant operation. For example,
   `effect-summary --file <scala> --json` gives syntax-first declared wrapper
   evidence, while `point-evidence --workspace <dir> --file <scala> --line <n>
   --col <n> --json` composes existing artifact discovery, live point evidence,
   and conditional reconciliation.
4. Answer the same question with compiler/test, IDE/LSP, artifact, source, and
   shell tools you already trust.
5. Record whether semantic-scala changed the decision, made uncertainty safer,
   improved provenance/merge discipline, added no value, or introduced friction.

The commands are examples, not required calls or superiority demonstrations.
Do not use a semantic command when source and existing compiler/test evidence
already settle the decision.

## What to evaluate

Start with one decision where better evidence could change a diagnosis, edit,
or stop condition. Useful cases include:

- selecting a concrete symbol or rendered type at one source point;
- reconciling live Presentation Compiler evidence with existing SemanticDB;
- understanding artifact availability, source coverage, or routing ambiguity;
- selecting one bounded row in a multi-project build;
- checking target-JDK-specific build behavior;
- inspecting compiler/plugin-generated facts after an authoritative Compile;
- preserving an FP wrapper or effect shape; or
- replacing manual merging of several compiler, IDE, build, or artifact facts.

Record the decision before choosing a semantic-scala command. Compiler, build,
and tests remain the final correctness oracle, and
[`semantic-api.md`](semantic-api.md) defines what each result and warning does
and does not establish.

## Compare with strong ordinary tooling

Record what the same case yields through the tools you normally trust:

- compiler and test output;
- IntelliJ, Metals, BSP, or LSP evidence;
- standard Scala and JDK artifact tools; and
- bounded source, search, and shell inspection.

A semantic-scala result is interesting when it either supplies a missing
decision-relevant fact or composes obtainable facts with materially better
provenance, freshness, completeness, uncertainty, or resource semantics.
Fewer commands alone is not enough. For the public benchmark interpretation
boundary, see [`../benchmarks/README.md`](../benchmarks/README.md); for the
current scoped TASTy comparison, see
[`tasty-point-evidence.md`](tasty-point-evidence.md#marginal-utility-boundary).

Recent bounded competitive work makes external cases especially important. On
two frozen source/build-solvable decisions, valid ordinary and specialized arms
had no material primary-score difference; one combined arm selected semantic
CLI evidence and reported provenance more precisely without changing the
decision, while no treatment selected MCP. This does not make semantic tooling
generally redundant. It means the next useful report should show where a strong
ordinary route actually loses a fact, makes an unsafe choice, or incurs material
merge/orchestration friction. A composition report is strongest when two
independently useful sources contribute distinct facts and the combined packet
has an observable decision role.

Classify the observed outcome as one of:

- **missing decision fact:** ordinary tooling could not establish a fact needed
  for the decision;
- **unsafe or ambiguous decision:** the harness prevented an unjustified
  target, artifact, symbol, or semantic choice;
- **composition or provenance gain:** the individual facts existed elsewhere,
  but the bounded packet materially improved provenance, freshness,
  completeness, uncertainty, or merge discipline;
- **no value / ordinary tooling sufficient:** the harness did not improve this
  case; this is explicitly useful feedback, not a failed report;
- **installation, client, or skill-selection friction**;
- **Scala, sbt, JDK, or project compatibility failure**; or
- **opportunity-qualified broker case:** two independently useful evidence
  sources supplied distinct facts and a combined packet could plausibly improve
  the decision.

## Minimum reproducibility packet

Retain only the bounded, non-secret context needed to understand the result:

- public repository URL, or a minimized reproducer when the project is private;
- shareable commit or revision;
- Scala version and sbt version or other build tool;
- harness JDK and selected target JDK, when relevant;
- selected sbt project, configuration, and requested/effective Scala axis, when
  relevant;
- source-relative file plus one-based line and UTF-16 column, when relevant;
- semantic-scala CLI version and whether CLI, MCP, or the skill was used;
- exact semantic-scala command or MCP tool name;
- result schema, domain status, and the warnings needed for interpretation;
- corresponding ordinary-tool result;
- whether build execution was approved and expected; and
- whether the evidence changed the diagnosis, edit, or decision.

Do not post credentials or tokens, full environment dumps, private absolute
home or cache paths, unrestricted build logs when a bounded excerpt suffices,
or private source and artifacts without permission.

## Supported installation boundary

The current supported packaged route is exactly `0.1.0-alpha.2`; follow
[`agent-onboarding.md`](agent-onboarding.md) and
[`distribution.md`](distribution.md). Mutable `main` reports
`0.1.0-alpha.3-SNAPSHOT`. Its Scala 3.9 harness/Presentation Compiler host lane,
target-aware v4, existing-internal-output v5, strict-freshness v6, project/JDK
selectors, and TASTy evidence are development-source behavior until a later
release; no alpha-3 artifact,
channel, tag, or GitHub Release is claimed. Label source-built alpha-3 SNAPSHOT
feedback explicitly and include the tested revision when shareable. Alpha-3 is
not being cut merely to align the supported package with mutable `main`;
release consideration is intentionally deferred toward qualification nearer
the planned early-September community announcement and remains separately
authorized.

## Especially valuable now

Priority feedback is:

1. a strong ordinary-tool comparison where semantic-scala changes a decision
   or safely avoids guessing;
2. an opportunity-qualified case where distinct facts from two useful evidence
   sources make a combined provenance/decision packet materially helpful;
3. a missing Scala, compiler, or FP fact that strong ordinary tooling does not
   already handle adequately;
4. installation, client, MCP, or skill-selection friction; and
5. a reproducible build/project/JDK/Scala-version compatibility failure.

Feature ideas detached from a concrete decision are still hypotheses; label
them speculative rather than presenting them as observed gaps.

## Report the result

Open the lightweight
[real-project feedback issue](https://github.com/DmytroMitin/scala-semantic-harness/issues/new?template=real-project-feedback.yml).
The form mirrors the categories and minimum packet above. Reports where ordinary
tooling was sufficient or semantic-scala added no value are welcome. If the
project cannot be shared, use a minimized reproducer or describe only the
non-secret decision boundary; do not disclose private source to complete the
form.
