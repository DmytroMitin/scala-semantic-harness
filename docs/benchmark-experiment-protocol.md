# Benchmark Experiment Protocol v0

## 1. Goal

Compare whether agents do better with semantic harness commands than with
compile/test only.

The experiment should answer whether semantic commands reduce wrong guesses,
create misleading confidence, or get ignored. Compile/test remains the final
oracle for success.

## 2. Modes

Mode A: compile/test only

Allowed commands:

- `compile --json`
- `test --json`
- `errors --json`

Mode B: semantic harness

Allowed commands:

- `compile --json`
- `test --json`
- `errors --json`
- `symbols --semanticdb ... --json`
- `symbol-at ... --json`
- `reconcile-symbol ... --json`

Mode B may use static SemanticDB facts, presentation compiler point queries, and
reconciliation output. These commands provide evidence, not proof.
Semantic-harness mode does not require semantic commands to be used when
compile/test diagnostics are sufficient; record them as irrelevant.

Mode C: effect summary harness

Allowed commands:

- `compile --json`
- `test --json`
- `errors --json`
- `effect-summary --file ... --json`

Mode C may use declaration-first FP return-type summaries. These commands
provide evidence about explicit method return types, not full effect inference.
Compile/test remains the final oracle.

FP effect-summary cases may include limitation cases where the summary is
locally correct but incomplete. For example, `fp-effect-misleading-1` is
designed so `effect-summary` can report both an `Option[User]` parser and an
`Either[String, User]` parser; the intended repair is still determined by the
public `Either` contract and tests.

### Competitive semantic-evidence condition matrix

A completed bounded competitive sequence used the same task in these arms:

1. **A — ordinary baseline**: source/shell plus compiler/test feedback.
2. **B — Metals**: the current verified Metals MCP or officially supported
   headless Metals semantic surface.
3. **C — semantic-scala**: schema-versioned CLI/MCP evidence.
4. **D — combined**: Metals plus semantic-scala, with evidence and tool-use
   attribution kept separable.

These were later controlled conditions, not new implemented
`BenchmarkCase.mode` values. The public v0 run JSON remains a separate earlier
screening layer and is not rewritten as this later comparison.

Before freezing such an experiment, the design must mechanically recheck the
current Metals capabilities, install route, version, tool names, readiness
state, and client compatibility. The completed sequence qualified released
Metals MCP 1.6.8 with its exact 17-tool surface and reproducible isolated writable
initialization. Old prose and historical Metals observations remain
insufficient current capability authority. Candidate decisions included:

- cross-built/shared-source target ambiguity;
- stale SemanticDB after source mutation;
- multi-project missing-output or partial-context decisions; and
- compiler-plugin/generated facts where live source and compiled truth can
  differ.

For each arm, record independently:

- capabilities exposed;
- policy/skill instructions supplied;
- automatic hooks enabled, including post-edit validation;
- tools actually invoked and evidence returned.

This separation prevents a capability comparison from being confused with an
instruction-quality or automatic-workflow comparison.

Primary metrics are decision-oriented: correct diagnosis/classification,
correct final edit when applicable, unsafe semantic/artifact/target selection,
false certainty or false static/live agreement, wrong project/Scala-axis/JDK
attribution, stale-artifact mistakes, exact evidence or non-attempt attribution,
iterations to compile/test green, controller intervention, redundant or
irrelevant semantic calls, wall-clock latency, and token/context/cost when
reliably observable. Fewer tool calls alone are not product value.

Interpret outcomes without precommitting to a winner:

- **C > B** supports independent semantic-scala value;
- **D > B and D > C** supports the hypothesis that semantic-scala is most
  valuable as a complementary evidence/decision layer;
- **B >= C and D adds no material value** supports scope reduction,
  redirection, or deferral; and
- **mixed** results identify bounded decision families rather than a general
  winner.

The later comparison adds two distinct evidence layers without changing the
historical v0 screening data:

- **controlled coherent composition:** on one frozen public shared-source
  decision with fact-equivalent primitive inputs, coherent `point-evidence`
  improved typed non-attempt attribution and merge discipline under bounded
  low- and medium-reasoning conditions;
- **natural competitive selection:** on a fully valid generated-fact decision,
  A/B/C scored 8/8 and D scored 7/8 with no veto, a non-material difference. On
  the shared-source decision, valid A/B/D arms scored 6/6, while C exceeded its
  fixed operation budget twice and remains unavailable for confirmatory
  comparison. D selected semantic CLI evidence that sharpened provenance but
  did not change its decision. Direct calibration established MCP callability,
  but no treatment selected an MCP call.

Do not average these layers. The controlled result is bounded positive
composition evidence; the natural-surface results are a case-specific null plus
one incomplete comparison. They separate capability availability, natural
selection, and material gain, and they do not establish general redundancy or
negative broker evidence. The two-case automatic rerun sequence is closed.

The broker hypothesis requires a separately preregistered,
opportunity-qualified case where independently useful evidence sources provide
distinct facts and the combined arm has room to improve correctness, safety, or
material orchestration friction. Do not manufacture such room by withholding a
released capability or adding a criterion that merely rewards tool use.

## 3. Command Categories

`allowedCommands` means harness feedback and validation commands, not every
shell or file-inspection operation.

Allowed harness commands:

- `compile --json`, `test --json`, and `errors --json`.
- `symbols`, `symbol-at`, and `reconcile-symbol` only when the mode allows them.
- `effect-summary` only when the mode allows it.

Allowed read-only inspection:

- `pwd`
- `ls` or `rg --files`
- `cat`, `sed`, or other file reads
- `git status`

Mutating operations:

- source edits
- any patch that must be counted as part of an iteration

Disallowed operations:

- semantic commands forbidden by the active mode
- external web search
- unrelated tools
- hidden manual help

Record sandbox, permission, or sbt execution failures as environment deviations,
not benchmark task failures.

## 4. Procedure

For each case:

1. Reset to the initial state.
2. Give the agent the case prompt and allowed commands.
3. Let the agent work.
4. Record commands used.
5. Record patches.
6. Count iterations.
7. Stop when `successCommand` succeeds or the agent gives up.

The initial state must match the case descriptor. Reset the project between
runs, even when the previous run failed.

## 5. Iteration Definition

Iteration = code patch + compile/test validation.

Semantic-only queries do not count as iterations. For example, running
`symbols`, `symbol-at`, or `reconcile-symbol` may inform the next patch, but it
does not increment the iteration count until a patch is validated with
`compile --json` or `test --json`.

## 6. Result Recording

Use `BenchmarkRun` JSON.

Fields:

- `caseId`: benchmark case id, such as `compile-error-1`.
- `mode`: `compile-test-only` or `semantic-harness`.
- `success`: whether the final success condition passed.
- `iterations`: patch-and-validation cycles.
- `commandsUsed`: commands used during the run.
- `semanticCommandsUsed`: semantic commands actually used, currently
  `symbol-at`, `symbols`, or `reconcile-symbol`.
- `semanticAssessment`: coarse interpretation of whether semantic evidence was
  `helpful`, `misleading`, `irrelevant`, `mixed`, or `uncertain`.
- `intentAssessment`: optional interpretation of whether the final patch
  preserved intended benchmark semantics, such as `preserved`, `changed`,
  `ambiguous`, `not-applicable`, or `unknown`.
- `intentAssessmentNotes`: optional notes explaining the intent assessment.
- `commandCompliance`: optional interpretation of whether the run followed the
  command protocol, such as `compliant`, `non-compliant`,
  `partially-compliant`, `not-assessed`, or `invalid-environment`.
- `commandComplianceNotes`: optional notes explaining compliance.
- `requiredCommandsUsed`, `forbiddenCommandsUsed`, `extraCommandsUsed`, and
  `environmentDeviations`: optional lists for manual protocol review.
- `finalStatus`: final outcome, such as `success`, `compile-failed`,
  `test-failed`, `gave-up`, or `invalid-run`.
- `notes`: concise observations about the run.

For future cross-condition studies, also capture in run notes or adjacent
artifacts until the JSON schema is deliberately extended:

- compile success and test success;
- bad patches and human interventions;
- tools made available and tools actually invoked;
- appropriateness of the selected tool;
- evidence used in the final decision;
- automatic hook invocations;
- latency, token use, and monetary cost when available;
- irrelevant searches and unnecessary heavyweight analysis.

Example:

```json
{
  "caseId": "compile-error-1",
  "mode": "compile-test-only",
  "success": true,
  "iterations": 1,
  "commandsUsed": ["compile --json"],
  "semanticCommandsUsed": [],
  "semanticAssessment": "irrelevant",
  "intentAssessment": "preserved",
  "intentAssessmentNotes": ["Patch preserved the intended missing-identifier repair."],
  "commandCompliance": "compliant",
  "commandComplianceNotes": ["Final compile validation was run."],
  "finalStatus": "success",
  "notes": ["Compiler diagnostic identified the missing identifier."]
}
```

`semanticCommandsUsed` is derived from actual run behavior. `semanticAssessment`
is a human/benchmark interpretation field, not an automated score. Keep nuanced
interpretation, caveats, and environment deviations in `notes`.
`intentAssessment` is also an interpretation field. It should not change
whether `success` is true, but it can identify compile-valid patches that
changed the intended semantic direction.
`commandCompliance` records benchmark protocol behavior separately from success.
A compile-successful run can still be non-compliant if it used forbidden
commands or skipped required validation.

Notes should record whether semantic evidence helped, misled, was ignored, or
was irrelevant. If the agent used a disallowed harness command, external search,
unrelated tool, or hidden manual help, record that deviation in `notes`.

## 7. First Recommended Experiment

Use Codex first.

Run:

- `compile-error-1` in `compile-test-only` mode.
- `compile-error-1` in `semantic-harness` mode.

Then optionally run:

- `reconciliation-uncertainty-1` in `semantic-harness` mode.

The first pair tests whether adding semantic commands changes behavior on a
simple compile repair. The optional reconciliation case tests whether the agent
handles uncertainty instead of over-trusting semantic output.

This v0 recommendation remains historical methodology. The later Metals intake
and A/B/C/D sequence described above is substantially executed without product
integration. Its bounded results shift the next gap source toward real-project,
user, and community feedback against strong ordinary tooling, while leaving
fresh product development and compatibility validation open.

## 8. Fairness Rules

- Use the same repo commit.
- Use the same agent/model where possible.
- Use the same initial state.
- Reset between runs.
- Use the same task wording except allowed commands.
- Provide no hidden manual help.
- Record deviations.
- Keep policy/skill text equivalent unless policy itself is the independent
  variable.
- Keep automatic hooks disabled or equivalent unless hook behavior is the
  independent variable.
- Name the concrete IDE/LSP implementation and record its indexing/readiness
  state.
- For a combined condition, preserve per-tool transcripts or artifacts so
  evidence attribution remains possible.

If any rule cannot be followed, keep the run but mark the deviation in
`BenchmarkRun.notes`.

## 9. What to Observe

- Semantic commands helped.
- Semantic commands misled.
- Agent ignored allowed commands.
- Agent over-trusted `displayName`.
- Agent misunderstood `RangeMatchOnly` or `NoMatch`.
- Compile/test oracle caught the issue.
- Agent selected an appropriate tool for the question.
- An available semantic tool was unnecessary or ignored appropriately.
- An automatic post-edit hook found evidence the agent did not request.
- IDE/LSP and semantic-scala evidence agreed, conflicted, or covered different
  parts of the decision.

Also record whether the agent treated semantic evidence as evidence or as ground
truth. A misleading semantic result is a valid benchmark observation.

## 10. Future Automation

Later, this experiment can be automated using:

- agent CLIs
- scripted prompts
- git worktrees
- reset scripts
- `BenchmarkRun` JSON collection

Benchmark v0 intentionally avoids automation. Manual and semi-manual runs are
enough to validate the case model, reveal useful metrics, and decide whether a
benchmark CLI or runner is worth adding next.

## 11. Public reproduction subset

The public tree retains portable condition prompts and a repository-relative
packaged-CLI wrapper. It does not include client-specific orchestration or raw
transcript capture.

```bash
sbt cli/stage
export SEMANTIC_SCALA_CMD="$PWD/scripts/benchmark/semantic-scala-packaged"
"$SEMANTIC_SCALA_CMD" version
python3 benchmarks/validate-public-evidence.py
sbt -batch test
```

These commands validate the admitted bytes, prompt and fixture availability,
the packaged CLI, and compile/test behavior without launching a model session.
The bounded historical aggregate and its limitations are documented in
[`../benchmarks/README.md`](../benchmarks/README.md).
