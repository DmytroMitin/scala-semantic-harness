# Benchmark Design

The benchmark harness v0 exists as an evaluation layer for
`scala-semantic-harness`. It is not a new semantic feature.

Its purpose is to measure whether the current compiler, test, SemanticDB,
presentation compiler, and reconciliation commands help agents repair small
Scala tasks more reliably than compile/test feedback alone. The benchmark
should guide what to build next before adding larger systems such as FP
analyzers, Metals/MCP, TASTy, graph storage, or vector search.

## Core Principle

Compile and test remain the final oracle. Semantic commands provide evidence
that an agent may use while reasoning, but they are not ground truth by
themselves.

This matters most for reconciliation. A `RangeMatchOnly` or `NoMatch` result is
valid benchmark evidence, not an infrastructure failure. Misleading or
incomplete semantic evidence is also a useful benchmark observation because it
shows where agents may over-trust names, ranges, or display strings.

## Semantic Evidence Conditions

Future comparisons should distinguish evidence conditions instead of treating
all semantic tooling as one binary harness flag.

### Plain files

The agent has source text and ordinary file/text search tools only. It does not
receive structured compiler, semantic-scala, IDE, or LSP evidence.

### Compiler/test only

The agent has compile and test feedback but no structured semantic query layer.
This retains the current build-oracle baseline.

### semantic-scala

The agent has schema-versioned semantic-scala CLI or MCP evidence. The run must
record which commands were exposed and which were actually invoked.

### IDE/LSP semantic

The agent has IDE- or language-server-backed evidence such as project-aware
diagnostics, symbol data, hover/type information, project structure, or
post-edit inspections. JetBrains IDE MCP and Metals/LSP should be identified
separately rather than collapsed into one implementation.

### Combined

The agent has semantic-scala plus an IDE/LSP semantic surface. Use this arm only
when transcripts or artifacts can still attribute evidence and decisions to
the contributing tools. Otherwise the arm cannot answer which capability
helped, misled, or was unnecessary.

These conditions are a future experiment design, not implemented benchmark
modes or completed results.

### Capability, policy, and hooks

Record three independent dimensions for every condition:

- **capability**: tools and evidence made available to the agent;
- **policy**: skills, instructions, or prompt guidance about when and how to
  use those tools;
- **hooks**: automatic workflow triggers such as post-edit inspection or
  validation.

Tool availability does not prove tool use, and tool use does not prove that the
selection was appropriate. A post-edit hook is also materially different from
an agent choosing a query, so it should be visible in run artifacts.

## Complementary Architecture Profiles

Expected semantic-scala strengths are headless operation, a CLI-first source of
truth, deterministic JSON schemas, CI-friendly execution, raw-artifact capture,
no requirement for an open/indexed IDE, and Scala-specific experimental
analyzers.

Expected IDE/LSP strengths are an already indexed project model,
project-aware diagnostics, dynamic hover/type/symbol evidence, automated
post-edit inspection, and potential debugger, coverage, and profiler context.

These profiles are complementary and experimentally comparable. The design
does not assume that either profile, or their combination, always improves a
repair.

## Implemented v0 Pieces

The `modules/benchmark` module currently provides:

- `BenchmarkCase`
- `BenchmarkRun`
- `BenchmarkCaseLoader`
- `BenchmarkValidation`
- Circe JSON codecs
- deterministic JSON fixtures under
  `modules/benchmark/src/test/resources/benchmark-cases/`

`BenchmarkCaseLoader` loads `.json` files from a benchmark case directory in
file-name order, so fixture loading is deterministic. Loaded cases are decoded
and validated before use.

`BenchmarkValidation` currently checks required descriptor fields, non-empty
`allowedCommands`, and the supported benchmark modes.

Benchmark case descriptors may also include optional intent metadata:
`expectedIntent`, `acceptablePatchFamilies`, and `intentNotes`. These fields are
used for qualitative interpretation only. They do not replace compile/test as
the success oracle, and they are not required for every case.

## Current Cases

Current fixture descriptors:

- `compile-error-1`
- `ambiguous-symbol-1`
- `no-symbol-1`
- `reconciliation-uncertainty-1`
- `fp-effect-either-preservation-1`
- `fp-effect-generic-wrapper-1`
- `fp-effect-misleading-1`
- `semantic-disambiguation-1`
- `semantic-misleading-1`
- `semantic-required-1`

`compile-error-1` is a compile/test-only case over
`examples/scala3-compile-failure`. It records a small compile error repair where
compiler diagnostics are the main evidence.

`ambiguous-symbol-1` is a semantic-harness case over
`examples/scala3-ambiguous-symbol`. It records whether an agent avoids
over-trusting display names when similarly shaped domain identifiers have
different types.

`no-symbol-1` is a semantic-harness case over `examples/scala3-no-symbol`. It
records whether an agent treats a no-symbol query position as valid semantic
evidence rather than an infrastructure failure.

`reconciliation-uncertainty-1` is a semantic-harness case over checked-in
SemanticDB and presentation compiler fixtures. It records whether an agent
handles reconciliation uncertainty instead of treating semantic output as proof.

`fp-effect-either-preservation-1` is an effect-summary-harness case over
`examples/scala3-fp-effect-either-preservation`. It records whether an agent
preserves an `Either[String, User]` failure channel while repairing a method
that incorrectly treats the `Either` as a plain `User`.

`fp-effect-generic-wrapper-1` is an effect-summary-harness case over
`examples/scala3-fp-effect-generic-wrapper`. It records whether an agent
preserves an abstract outer `F` wrapper and inner `Option` while repairing a
method that incorrectly treats `F[Option[User]]` as if it were already an
`Option[User]`.

`fp-effect-misleading-1` is an effect-summary-harness case over
`examples/scala3-fp-effect-misleading`. It records whether an agent avoids
over-trusting a locally convenient `Option[User]` parser when the intended
public contract requires preserving an `Either[String, String]` failure
channel.

`semantic-disambiguation-1`, `semantic-misleading-1`, and
`semantic-required-1` are semantic-harness cases over tiny standalone Scala 3
projects. They stress wrong symbol assumptions, misleading same-name evidence,
and missing-identifier repairs where semantic inspection should be evaluated
against the compile oracle.

The fixtures are descriptors only. They do not reset projects, run agents,
execute benchmark cases, or store run results automatically.

## Modes

### compile-test-only

Allowed command family:

- `compile --json`
- `test --json`
- `errors --json`

This mode is the baseline. It measures whether an agent can repair a case using
only compiler and test feedback.

### semantic-harness

Allowed command family:

- `compile --json`
- `test --json`
- `errors --json`
- `symbols --semanticdb ... --json`
- `symbol-at ... --json`
- `reconcile-symbol ... --json`

This mode adds static SemanticDB facts, dynamic presentation compiler point
queries, and reconciliation evidence. It measures whether semantic evidence
helps, is ignored, or misleads the agent. Semantic-harness mode does not require
semantic commands to be used when compile/test diagnostics are sufficient; record
that evidence as irrelevant.

The current implementation records allowed commands in case descriptors. It
does not enforce allowed command usage through infrastructure.

### effect-summary-harness

Allowed command family:

- `compile --json`
- `test --json`
- `errors --json`
- `effect-summary --file ... --json`

This mode adds the declaration-first FP effect summary command. It measures
whether agents use declared return-type evidence to preserve wrappers such as
`Either`, `Option`, `F[_]`, `IO`, or ZIO-like aliases. The command is evidence,
not proof, and compile/test remains the final oracle.

## Command Categories

`allowedCommands` describes harness feedback and validation commands, not every
shell operation an agent may need while working.

Allowed harness commands are the mode-specific commands listed in the case
descriptor: compile/test/errors for `compile-test-only`, plus
symbols/symbol-at/reconcile-symbol for `semantic-harness`, or
effect-summary for `effect-summary-harness`.

Allowed read-only inspection includes `pwd`, `ls`, `rg --files`, `cat`, `sed`,
file reads, and `git status`. These commands may help the agent understand the
workspace, but they are not benchmark evidence commands.

Mutating operations are source edits. Count them as part of patch iterations and
record the resulting validation cycle.

Disallowed operations include semantic commands forbidden by the active mode,
external web search, unrelated tools, and hidden manual help.

Sandbox, permission, or sbt execution failures should be recorded as environment
deviations in run notes, not as benchmark task failures.

## Metrics

Benchmark runs should be recorded with `BenchmarkRun` JSON.

Tracked fields:

- `success`: whether the final success command passed.
- `iterations`: number of patch-and-compile/test validation cycles.
- `commandsUsed`: commands used during the run.
- `semanticCommandsUsed`: semantic harness commands actually used during the
  run, such as `symbol-at`, `symbols`, or `reconcile-symbol`.
- `semanticAssessment`: coarse human/benchmark classification of semantic
  evidence as `helpful`, `misleading`, `irrelevant`, `mixed`, or `uncertain`.
- `intentAssessment`: optional interpretation of whether the final patch
  preserved the benchmark's intended semantics, using values such as
  `preserved`, `changed`, `ambiguous`, `not-applicable`, or `unknown`.
- `intentAssessmentNotes`: optional notes explaining the intent assessment.
- `commandCompliance`: optional interpretation of whether the run followed the
  benchmark command protocol, using values such as `compliant`,
  `non-compliant`, `partially-compliant`, `not-assessed`, or
  `invalid-environment`.
- `commandComplianceNotes`: optional notes explaining command compliance.
- `requiredCommandsUsed`, `forbiddenCommandsUsed`, `extraCommandsUsed`, and
  `environmentDeviations`: optional lists for manual protocol review.
- `finalStatus`: final outcome such as `success`, `compile-failed`,
  `test-failed`, `gave-up`, or `invalid-run`.
- `notes`: concise observations about whether semantic evidence was helpful,
  misleading, or irrelevant.

Cross-condition reports should additionally capture, in structured fields when
available or otherwise in run notes/artifacts:

- compile and test success separately;
- bad patches and human interventions;
- semantic/IDE/LSP capabilities made available;
- semantic/IDE/LSP tools actually invoked;
- whether the selected tool was appropriate for the question;
- evidence used in the final decision;
- automatic hooks triggered and their outputs;
- latency, token use, and monetary cost when available;
- irrelevant searches or unnecessary heavyweight analysis.

These metrics must not be interpreted as evidence that exposing more semantic
tools is inherently better.

An iteration is one code patch followed by compile/test validation. Semantic-only
queries such as `symbols`, `symbol-at`, or `reconcile-symbol` do not count as
iterations by themselves.
`semanticCommandsUsed` is derived from the actual recorded run behavior.
`semanticAssessment` is an interpretation field, not a score; nuanced reasoning
and caveats still belong in `notes`.
`intentAssessment` is separate from both compile/test success and semantic
evidence usefulness. A run can be compile-successful while still changing the
intended semantic direction of the case.
`commandCompliance` is a fourth interpretation dimension. A run can compile
successfully but still be non-compliant or partially compliant with the
benchmark prompt.

For compile/test-style commands, success should be judged from structured JSON
result fields, not from LLM guesses.

## Current Limitations

The benchmark harness v0 intentionally does not include:

- automated benchmark runner
- LLM API integration
- benchmark CLI commands
- persistent run storage
- result comparison tooling
- agent orchestration
- hidden telemetry
- performance benchmarking

These limits keep the benchmark small and reproducible while the repository
learns which semantic commands actually help agents.
