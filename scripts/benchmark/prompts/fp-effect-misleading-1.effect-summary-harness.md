You are working on a small Scala 3 sbt project.

Goal:
Fix the project so that the success command passes while preserving the intended Either failure channel.

Success command:
"$SEMANTIC_SCALA_CMD" test --json

Allowed harness commands:
- "$SEMANTIC_SCALA_CMD" compile --json
- "$SEMANTIC_SCALA_CMD" test --json
- "$SEMANTIC_SCALA_CMD" errors --json
- "$SEMANTIC_SCALA_CMD" effect-summary --file src/main/scala/example/Main.scala --json

Allowed read-only inspection:
- pwd
- rg --files
- cat, sed, or equivalent file reads
- git status

Forbidden commands:
- symbols
- symbol-at
- reconcile-symbol
- external web search
- unrelated tools

Rules:
- Do not read Codex memory files, saved memories, previous benchmark reports, or any files outside this example project except through the explicitly allowed harness command.
- Prefer compile/test as the final oracle.
- effect-summary is evidence, not proof.
- Use effect-summary before patching unless the first compile/test diagnostic already proves the fix.
- Do not over-trust the currently convenient wrapper.
- Seeing `parseOption: Option[User]` is locally correct but may be misleading for the intended repair.
- Expected signals include `parseOption` with declared return type `Option[User]`, `parseEither` with declared return type `Either[String, User]`, and `userName` with declared return type `Either[String, String]`.
- Preserve the intended `Either[String, String]` failure channel.
- Do not erase `Either` to `Option` or plain `String`.
- Do not use unsafe `.get`, throw on absence, or return default values.
- Do not change parser methods to erase the `Either` contract.
- Record every command you run.
- Count one iteration as: source patch + compile/test validation.
- Stop when test --json returns success=true.
- At the end, summarize:
  - commands used
  - iterations
  - final status
  - whether effect-summary helped, misled, or was irrelevant
