# AGENTS.md

This repository is developed with AI coding agents.

## Project goal

Build a semantic harness for Scala and functional-programming projects that
helps coding agents use compiler, build, test, and type-system information
instead of relying only on text.

The project is not a replacement for Codex, Claude Code, Cursor, Copilot,
opencode, or mature IDE/LSP tooling. It is a semantic evidence layer for them.

## Core rule

Prefer compiler, build, and test truth over model guesses.

## Development style

- Use Scala 3.
- Keep modules small and interfaces explicit.
- Prefer JSON outputs for machine-facing commands.
- Keep commands usable by both humans and agents.
- Avoid hidden global state.
- Preserve bounded resource use and uncertainty in semantic results.
- Keep examples small and benchmarkable.
- Add tests before complex integrations.

## Documentation rules

When architecture or a public contract changes, update:

- `README.md`;
- `ROADMAP.md` when product outcomes or readiness change; and
- the relevant file under `docs/`.

Public documentation must stand alone without private controller history,
machine-specific paths, or operator-only publication policy.

## Validation

- Use the compiler and the relevant tests for behavioral changes.
- Keep the exact eight-tool MCP registry assertion passing.
- Treat semantic queries as bounded evidence, not substitutes for compilation
  or tests.
- Do not stage, commit, push, publish, or change repository visibility without
  explicit user authorization.
