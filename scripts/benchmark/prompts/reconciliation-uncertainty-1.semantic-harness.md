You are evaluating semantic evidence in scala-semantic-harness.

Goal:
Determine whether the symbol at Main.scala line 6 col 16 can be proven identical to the symbol in the provided SemanticDB fixture.

Allowed harness commands:
- "$SEMANTIC_SCALA_CMD" symbol-at --file modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala --line 6 --col 16 --json
- "$SEMANTIC_SCALA_CMD" symbols --semanticdb modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb --json
- "$SEMANTIC_SCALA_CMD" reconcile-symbol --file modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala --line 6 --col 16 --semanticdb modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb --json

Allowed read-only inspection:
- pwd
- rg --files
- cat, sed, or equivalent file reads
- git status

Forbidden commands:
- external web search
- unrelated tools
- hidden manual help

Rules:
- Do not treat displayName as identity.
- Do not treat RangeMatchOnly as proof.
- Explain whether the evidence is ExactMatch, RangeMatchOnly, SymbolMismatch, or NoMatch.
- Record every command you run.
- At the end, summarize:
  - commands used
  - final status
  - whether the evidence proves identity
