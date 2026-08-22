# semantic-scala Agent Skill

The authoritative, client-neutral policy is
[`skills/semantic-scala/SKILL.md`](../skills/semantic-scala/SKILL.md).

Supported project wrappers:

- Claude Code:
  [`.claude/skills/semantic-scala/SKILL.md`](../.claude/skills/semantic-scala/SKILL.md)
- Codex:
  [`.agents/skills/semantic-scala/SKILL.md`](../.agents/skills/semantic-scala/SKILL.md)

Both clients support repository-scoped skills in these locations. Each wrapper
contains discovery/invocation guidance only and loads the same generic core.
The wrappers intentionally are regular files rather than symbolic links so the
package remains portable across client versions and checkouts.

The core supersedes the earlier duplicated policy in this document. It covers
the exact CLI and eight-tool MCP surfaces, conservative selection, side effects
and approvals, result interpretation, fallback, benchmark evidence, privacy,
reproducibility, and bounded examples. Update the core first; change a wrapper
only when its client's supported discovery or invocation convention changes.

For one exact Presentation Compiler declaration-selection question, the core
chooses `symbol-at`. It reserves `point-evidence` for questions where coherent
artifact discovery/selection, live evidence, and conditional reconciliation
are themselves decision-relevant. Neither rule overrides the source-sufficient
boundary: if ordinary inspection already answers the question, no semantic
query is warranted.

This package is documentation and client policy only. It adds no command, MCP
tool, hook, service, session state, or automatic invocation.
