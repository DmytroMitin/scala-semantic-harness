You are working on a small Scala 3 sbt project.

Goal:
Fix the project so that the success command passes while preserving the intended Either failure channel.

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
- Preserve the intended `Either[String, String]` failure channel.
- Do not downgrade `Either` to `Option` or plain `String`.
- Do not use unsafe `.get`, throw on absence, or return default values.
- Do not change parser methods to erase the `Either` contract.
- Record every command you run.
- Count one iteration as: source patch + compile/test validation.
- Stop when test --json returns success=true.
- At the end, summarize:
  - commands used
  - iterations
  - final status
  - what fixed the issue
