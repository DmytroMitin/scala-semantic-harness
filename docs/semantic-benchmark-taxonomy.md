# Semantic Benchmark Taxonomy

## 1. Purpose

Benchmarking semantic tooling is not only about success or failure. A run can
compile successfully while semantic evidence was irrelevant, or it can fail
while exposing a useful semantic behavior. Semantic evidence can help, mislead,
preserve uncertainty, or confirm absence.

This taxonomy is intentionally lightweight and qualitative. Categories are
human-assigned interpretations of benchmark behavior, not numerical scores. They
may evolve as the benchmark set grows and repeated runs expose more patterns.

## 2. Core concepts

Compile/test remains the final oracle for repair tasks. If semantic evidence and
compile/test disagree, compile/test determines whether the patched project is
valid.

Semantic evidence is evidence, not proof. A symbol query, SemanticDB fixture, or
reconciliation result can describe a useful local fact without proving the
globally correct repair.

Semantic usefulness is contextual. The same command can be helpful in one case,
irrelevant in another, or misleading when it describes the current broken program
rather than the intended program.

### Compile-valid vs intent-preserving repairs

A repair can satisfy the current compile/test oracle while still choosing a
different semantic intent. `semantic-misleading-1` demonstrates this: one
compile-valid repair can preserve the current `A.convert` binding by changing
the expected type to `String`, while another can preserve the apparent `Int`
intent by selecting the `B.convert` path.

Current v0 success remains compile/test-based. Current benchmark JSON can record
optional `intentAssessment` and `intentAssessmentNotes` fields so reports can
separately discuss whether a repair appears intent-preserving, especially when
multiple compile-valid patches exist. Future benchmark cases may still need
tests that encode intent or more precise patch-family validation, but those are
not part of the current step.

## 3. Semantic usefulness categories

### irrelevant

Semantic evidence was unused or unnecessary. Compiler or build diagnostics were
already sufficient to identify and validate the repair.

Examples:

- `compile-error-1` benchmark runs.

### helpful

Semantic evidence materially reduced ambiguity or guided repair reasoning.

Examples:

- `semantic-required-1` runs where `symbol-at` confirmed the unresolved call
  site and helped focus repair reasoning on the intended helper.

### misleading

Semantic evidence described the current program state correctly, but pointed
toward a globally wrong repair interpretation if over-trusted.

Examples:

- `semantic-misleading-1` runs where `symbol-at` identified the currently bound
  `convert`, while compile/test showed that the intended repair needed the
  `Int`-returning conversion.

### uncertainty-preserving

Semantic evidence prevented false certainty. The correct behavior was preserving
uncertainty rather than pretending identity had been proven.

Examples:

- `reconciliation-uncertainty-1` runs where `RangeMatchOnly` was handled as
  incomplete evidence, not as symbol identity.

### absence-confirming

Semantic evidence confirmed that no symbol or resolution existed. Absence itself
was useful evidence, not an infrastructure failure.

Examples:

- `no-symbol-1` runs where `symbol-at` returned `symbol:null` at a no-symbol
  position.

### mixed

Semantic evidence had both helpful and misleading aspects.

Examples:

- `semantic-misleading-1` can be interpreted as mixed: the semantic query
  correctly identified the current binding, but the useful repair required not
  over-trusting that binding.

## 4. Relationship between categories

These categories are not mutually exclusive in principle. A run can be both
absence-confirming and helpful, or both misleading and ultimately useful because
compile/test prevented an overconfident repair.

Current `BenchmarkRun` JSON uses one coarse `semanticAssessment` string. That is
deliberately simple. Nuanced interpretation belongs in `notes`, logs, and
reports until the project has enough evidence to justify a richer structure.

## 5. Evidence source, selection, and delivery

Future IDE/LSP comparisons should classify more than whether semantic evidence
was useful. Record separately:

- **source**: plain text, compiler/test, semantic-scala, JetBrains IDE MCP,
  Metals/LSP, or a controlled combination;
- **availability**: capabilities exposed to the agent;
- **selection**: capabilities the agent actually invoked and whether the
  selected tool fit the question;
- **policy**: skills or instructions that encouraged or constrained tool use;
- **delivery**: agent-requested query versus automatic hook such as post-edit
  inspection;
- **decision evidence**: facts the agent relied on for the final patch or
  conclusion.

An unused tool can be irrelevant or appropriately skipped. An invoked tool can
be inappropriate even when its output is correct. An automatic hook can be
helpful without demonstrating agent tool-selection skill. These distinctions
should remain visible instead of being compressed into “semantic tooling was
available.”

JetBrains IDE MCP and Metals/LSP belong to the IDE/LSP evidence family but
should remain separately named implementations. semantic-scala remains the
headless, CLI-first, schema-versioned comparison surface. Combined conditions
should be classified only when evidence attribution remains possible.

## 6. Current benchmark observations

- `compile-error-1` maps to irrelevant: compiler/build diagnostics are enough.
- `reconciliation-uncertainty-1` maps to uncertainty-preserving.
- `no-symbol-1` maps to absence-confirming and sometimes helpful.
- `semantic-required-1` maps to helpful.
- `semantic-misleading-1` maps to misleading or mixed.
- `semantic-disambiguation-1` maps to mildly helpful.

## 7. Limitations

- Tiny sample size.
- No statistical significance.
- Human interpretation is involved.
- Prompt wording influences behavior.
- The taxonomy is exploratory.

## 8. Future directions

Possible future evolution includes richer `semanticAssessment` structure,
multiple simultaneous categories, confidence scores, automatic extraction, agent
comparison, and semantic usefulness metrics.

These are directions only. The current benchmark should not implement them yet.
