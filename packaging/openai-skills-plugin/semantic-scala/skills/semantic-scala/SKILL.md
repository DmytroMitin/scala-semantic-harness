---
name: semantic-scala
description: Inspect declared effect-like return types in a local Scala source file with bounded semantic-scala CLI evidence. Use only in a Codex environment with a local workspace; do not use for prose-only or non-Scala work.
---

# semantic-scala effect summary

Use this skill only when the user needs declared effect-wrapper evidence from
a local `.scala` file. This public skills-only workflow supports
`effect-summary` and does not use MCP, run builds, or expose other
semantic-scala commands.

1. Resolve this skill's directory from the skill path supplied by the host.
   Do not assume a global plugin-root environment variable.
2. Read `references/semantic-scala-policy.md` before deciding whether semantic
   evidence is necessary. Its selection and interpretation policy is
   authoritative; this preamble only defines the packaged execution route.
3. Require a user-selected relative `.scala` path inside the current workspace.
   Do not pass absolute paths, traversal paths, generated guesses, or extra
   arguments to the helper.
4. Run `python3 scripts/semantic_scala_cli.py status --json` from the skill
   directory. This check never downloads the runtime.
5. If `cached` is false, explain that the next command downloads one fixed
   285,603,142-byte Alpha-3 runtime from GitHub Releases into the local user
   cache. Obtain the user's approval before continuing.
6. From the workspace containing the selected relative path, run:

   `python3 <absolute-skill-directory>/scripts/semantic_scala_cli.py effect-summary --file <relative.scala> --json`

7. Parse the JSON as bounded semantic evidence. Report the declared symbol,
   declared return type, effect category, schema/provenance fields, and any
   uncertainty or error. Do not infer facts absent from the result.

Stop with a clear explanation if the environment is not Linux x86_64, lacks
Python 3.11, cannot provide a local workspace, or does not permit the approved
first-use download. Do not substitute an undeclared semantic-scala install,
start an MCP server, or broaden the helper command.
