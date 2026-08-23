# semantic-scala Agent Skill

The authoritative, client-neutral policy is
[`skills/semantic-scala/SKILL.md`](../skills/semantic-scala/SKILL.md).

For an external project using the supported `0.1.0-alpha.2` distribution,
install the canonical file from the immutable tag. Do not fetch mutable `main`:

```bash
skill_file=.agents/skills/semantic-scala/SKILL.md
test ! -e "$skill_file" || { echo "refusing to overwrite $skill_file"; exit 1; }
mkdir -p "$(dirname "$skill_file")"
curl --fail --location \
  --output "$skill_file" \
  https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/0.1.0-alpha.2/skills/semantic-scala/SKILL.md
```

Claude Code uses `.claude/skills/semantic-scala/SKILL.md` instead. The
complete runtime, MCP, client, and verification sequence is in
[`agent-onboarding.md`](agent-onboarding.md).

Supported project wrappers:

- Claude Code:
  [`.claude/skills/semantic-scala/SKILL.md`](../.claude/skills/semantic-scala/SKILL.md)
- Codex:
  [`.agents/skills/semantic-scala/SKILL.md`](../.agents/skills/semantic-scala/SKILL.md)

Both clients support repository-scoped skills in these locations. These are
thin wrappers for this source repository only: each contains
discovery/invocation guidance and loads the same generic core by a relative
path. Copying a wrapper by itself to an external project is invalid.
The wrappers intentionally are regular files rather than symbolic links so the
package remains portable across client versions and checkouts.

Local alpha-2 qualification discovered the exact immutable canonical bytes in
Codex CLI `0.149.0` and Claude Code `2.1.220`. Codex also explicitly selected
the skill and completed one permitted read-only MCP call. Cursor's documented
skill route was not locally exercised because no disposable headless agent
surface was available; the installed VS Code lacked the GitHub Copilot agent
client. Therefore discoverability/installability is ready only for the scoped
Codex and Claude project-local paths, while adoption effectiveness remains
partial. Client statuses and official documentation links are kept in the
onboarding matrix.

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
