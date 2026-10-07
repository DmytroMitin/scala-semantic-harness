---
name: semantic-scala
description: Use an independently installed semantic-scala CLI for bounded Scala compiler, build, test, type, effect, symbol, reconciliation, or SemanticDB evidence when it could materially change the work.
---

# semantic-scala CLI for Claude Directory

Read `references/semantic-scala-policy.md` completely before choosing or
interpreting semantic evidence. Its semantic selection, side-effect,
interpretation, privacy, and reporting rules are authoritative.

This public plugin is guidance only. It contains no CLI binary, installer,
updater, MCP server, hook, or pre-approved tool. Use only an independently installed `semantic-scala`
command through Claude's ordinary shell permissions.

## Provider rule for this package

For this skills-only workflow, the external CLI rule below replaces only the
canonical policy's MCP-first provider preference and fallback steps:

1. When semantic evidence is actually warranted, run `semantic-scala version`
   first. Do not inspect environment variables, credentials, keychains, or
   unrelated machine state.
2. If the command is missing or does not report exact `0.1.0-alpha.3`, stop and
   report the independent CLI prerequisite. Point to the package README for the
   documented Coursier command. Do not assume pre-1.0 version compatibility.
3. Do not install, update, or download the CLI or a runtime.
4. Do not substitute an MCP server, even when one is separately configured.
5. If the CLI is present, use the narrowest applicable command below through
   ordinary shell permissions. Preserve any permission denial as the outcome.
6. Never describe CLI evidence as MCP evidence.

## Core CLI equivalents

Use the canonical policy for complete arguments and result semantics. These
forms make the exact eight CLI equivalents explicit:

- `semantic-scala compile --json`
- `semantic-scala errors --json`
- `semantic-scala test --json`
- `semantic-scala effect-summary --file <scala> --json`
- `semantic-scala symbol-at --file <scala> --line <n> --col <n> --json`
- `semantic-scala symbols --semanticdb <file> --json`
- `semantic-scala reconcile-symbol --file <scala> --line <n> --col <n> --semanticdb <file> --json`
- `semantic-scala point-evidence --workspace <dir> --file <scala> --line <n> --col <n> --json`

CLI-only commands in the canonical policy may be selected when they are the
narrowest valid answer. They do not create permission to execute build code,
download dependencies, refresh caches, or broaden the requested workspace.

## Restraint and surfaces

Do not run a semantic command when ordinary source or test inspection already
answers the question, including prose-only and formatting-only work.

Claude Code is the qualified surface for this workflow. In Cowork, use the CLI
only when the actual local desktop/session environment exposes the existing
command and normal permissions allow it. In Claude chat or another environment
without local CLI access, explain that limitation and do not claim semantic
evidence from the user's machine.
