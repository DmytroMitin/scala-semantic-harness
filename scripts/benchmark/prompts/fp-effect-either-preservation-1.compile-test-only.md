You are working on a small Scala 3 sbt project.

Goal:
Fix the project so that the success command passes while preserving typed failure semantics.

Success command:
"$SEMANTIC_SCALA_CMD" test --json

Allowed harness commands:
- "$SEMANTIC_SCALA_CMD" compile --json
- "$SEMANTIC_SCALA_CMD" test --json
- "$SEMANTIC_SCALA_CMD" errors --json

Allowed read-only inspection:
- pwd
- rg --files
- cat, sed, or equivalent file reads
- git status

Forbidden commands:
- "$SEMANTIC_SCALA_CMD" effect-summary ...
- symbols
- symbol-at
- reconcile-symbol
- external web search
- unrelated tools

Rules:
- Prefer compile/test as the final oracle.
- Preserve typed failure semantics if possible.
- Do not erase Either just to compile.
- Do not throw on Left, use .toOption.get, or replace failures with default values.
- Record every command you run.
- Count one iteration as: source patch + compile/test validation.
- Stop when test --json returns success=true.
- At the end, summarize:
  - commands used
  - iterations
  - final status
  - what fixed the issue
