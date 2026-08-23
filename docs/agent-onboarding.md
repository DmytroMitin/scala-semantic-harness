# Agent-first onboarding

This is the shortest supported route from a fresh JDK 21 environment to
`semantic-scala` in an agent client. The supported packaged version is exactly
`0.1.0-alpha.2`. Mutable `main` is alpha-3 SNAPSHOT development; its optional
project selector and target-JDK selector are not alpha-2 features.

## 1. Install the CLI and MCP server

Install Coursier using its
[official instructions](https://get-coursier.io/docs/cli-installation), then:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json \
  semantic-scala semantic-scala-mcp
semantic-scala version
```

The version command must report `0.1.0-alpha.2`. The channel is currently
pinned to immutable Maven Central artifacts for that version. JDK 21 is the
supported harness runtime. Target-workspace sbt is additionally required by
build-executing commands such as `compile`, `errors`, and `test`.

Choose one or both integration modes:

- **CLI** provides the complete alpha-2 command surface and is the fallback
  when a client cannot attach the MCP server.
- **MCP** provides a deliberately curated eight-tool agent surface. It is not
  intended to mirror every CLI command.
- **Skill** teaches an agent when to use either surface. It adds no runtime,
  hook, background service, or automatic invocation.

The recommended combinations are:

1. **MCP + skill** when the agent supports both: MCP supplies the curated tool
   bridge and the skill supplies selection, interpretation, safety, and
   fallback policy.
2. **CLI + skill** for shell-capable agents without MCP, and whenever a
   CLI-only diagnostic is needed.
3. **MCP without skill** remains functional, but has less product-specific
   guidance.
4. **Manual CLI** is the troubleshooting and automation fallback, not the
   primary interactive experience.

## 2. Install the immutable alpha-2 skill

Install the canonical file from the immutable release tag, not mutable `main`.
For Codex, Cursor, and clients that discover `.agents/skills`, run from the
target project root:

```bash
skill_file=.agents/skills/semantic-scala/SKILL.md
test ! -e "$skill_file" || { echo "refusing to overwrite $skill_file"; exit 1; }
mkdir -p "$(dirname "$skill_file")"
curl --fail --location \
  --output "$skill_file" \
  https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/0.1.0-alpha.2/skills/semantic-scala/SKILL.md
```

For Claude Code, use the same procedure with
`.claude/skills/semantic-scala/SKILL.md`. The repository's own files under
`.agents/skills` and `.claude/skills` are thin source-tree wrappers that load
the canonical in-repository skill. They are not standalone files to copy into
another project.

Project-local installation is preferred because it pins guidance with the
repository. Optional user-wide locations documented by the clients are
`~/.agents/skills` for Codex, `~/.claude/skills` for Claude Code,
`~/.cursor/skills` for Cursor, and `~/.copilot/skills` for VS Code/Copilot.
Apply the same no-overwrite and immutable-tag rules when choosing user scope.

## 3. Configure a client

All examples assume `semantic-scala-mcp` is on the client process `PATH` and
the client starts it with the target project as working directory. If a GUI
does not inherit that `PATH`, use the absolute result of
`command -v semantic-scala-mcp` as `command`.

### Codex CLI and Codex Desktop

Codex documents shared MCP configuration for its CLI, IDE extension, and
desktop app. Add the server with the
[official Codex MCP command](https://learn.chatgpt.com/docs/extend/mcp?surface=cli):

```bash
codex mcp add semantic-scala -- semantic-scala-mcp
codex mcp list
```

Project-scoped configuration may instead live in `.codex/config.toml`:

```toml
[mcp_servers.semantic-scala]
command = "semantic-scala-mcp"
```

Install the skill at `.agents/skills/semantic-scala/SKILL.md`; Codex documents
that project scope in its
[skills guide](https://learn.chatgpt.com/docs/build-skills). Invoke it
explicitly as `$semantic-scala` when deterministic activation matters.

Local qualification used Codex CLI `0.149.0`: it discovered the immutable
alpha-2 skill, listed the exact eight tools in order, called only
`semantic_effect_summary`, and received the expected bounded schema. Codex
Desktop shares the configuration contract but was not separately exercised.

### Claude Code

Claude Code supports a project `.mcp.json` according to its
[MCP guide](https://code.claude.com/docs/en/mcp):

```json
{
  "mcpServers": {
    "semantic-scala": {
      "command": "semantic-scala-mcp",
      "args": []
    }
  }
}
```

Install the skill at `.claude/skills/semantic-scala/SKILL.md`, as described in
the [Claude Code skills guide](https://code.claude.com/docs/en/skills), and use
`/semantic-scala` for explicit invocation. Claude Code `2.1.220` locally
discovered the exact skill bytes. MCP end-to-end qualification was blocked by
an expired client login before tool discovery, so the recipe is official but
not locally qualified.

### Cursor

Cursor documents project MCP configuration in `.cursor/mcp.json` and skills in
`.agents/skills` or `.cursor/skills`; see its
[MCP guide](https://cursor.com/docs/context/mcp) and
[skills guide](https://cursor.com/docs/skills). Use:

```json
{
  "mcpServers": {
    "semantic-scala": {
      "command": "semantic-scala-mcp",
      "args": []
    }
  }
}
```

Cursor `3.17.8` was present locally, but no headless agent surface was
available for a disposable end-to-end tool or skill run. Both recipes remain
official but not locally qualified.

### VS Code with GitHub Copilot

VS Code documents MCP servers in `.vscode/mcp.json` and agent skills in
`.agents/skills` (among other compatible locations); see the
[MCP server guide](https://code.visualstudio.com/docs/agent-customization/mcp-servers)
and [agent skills guide](https://code.visualstudio.com/docs/agent-customization/agent-skills).
The MCP shape is:

```json
{
  "servers": {
    "semantic-scala": {
      "type": "stdio",
      "command": "semantic-scala-mcp",
      "args": []
    }
  }
}
```

VS Code was installed locally without a GitHub Copilot agent surface, so this
client was unavailable for local MCP or skill qualification.

## Client qualification matrix

The statuses describe the exact local qualification, not general vendor
compatibility.

| Client | MCP status | Skill status | Local boundary |
| --- | --- | --- | --- |
| Codex CLI 0.149.0 | `QUALIFIED_LOCAL` | `QUALIFIED_LOCAL` | Exact eight-tool discovery and one read-only call; immutable alpha-2 skill discovered and explicitly selected. |
| Codex Desktop | `OFFICIAL_RECIPE_NOT_LOCALLY_QUALIFIED` | `OFFICIAL_RECIPE_NOT_LOCALLY_QUALIFIED` | Shares the documented Codex configuration, but the desktop surface was not separately exercised. |
| Claude Code 2.1.220 | `OFFICIAL_RECIPE_NOT_LOCALLY_QUALIFIED` | `QUALIFIED_LOCAL` | Exact skill discovered; expired client login blocked MCP discovery and invocation. |
| Cursor 3.17.8 | `OFFICIAL_RECIPE_NOT_LOCALLY_QUALIFIED` | `OFFICIAL_RECIPE_NOT_LOCALLY_QUALIFIED` | Editor present; no disposable headless agent qualification surface. |
| VS Code / GitHub Copilot | `CLIENT_NOT_AVAILABLE` | `CLIENT_NOT_AVAILABLE` | VS Code present without the Copilot agent client. |

`RECIPE_NOT_ADMITTED` and `SKILL_NOT_SUPPORTED` are reserved for clients where
the project has not admitted an official configuration or skill route. No
client in the table currently has either status.

## CLI and MCP surface contract

The CLI is the complete local/reference surface. MCP is a small agent-facing
projection chosen for bounded use hypotheses, approvals, and stable schemas.
Shared operations must preserve their semantic meaning across transports, but
command-count parity is not a goal.

| Alpha-2 CLI operation | MCP classification | MCP tool |
| --- | --- | --- |
| `compile` | `MCP_EXPOSED` | `semantic_compile` |
| `errors` | `MCP_EXPOSED` | `semantic_errors` |
| `test` | `MCP_EXPOSED` | `semantic_test` |
| `effect-summary` | `MCP_EXPOSED` | `semantic_effect_summary` |
| `symbol-at` | `MCP_EXPOSED` | `semantic_symbol_at` |
| `symbols` | `MCP_EXPOSED` | `semantic_symbols` |
| `reconcile-symbol` | `MCP_EXPOSED` | `semantic_reconcile_symbol` |
| `point-evidence` | `MCP_EXPOSED` | `semantic_point_evidence` |
| `semanticdb-status` | `CLI_ONLY_BY_DESIGN` | — |
| `semanticdb-coverage` | `CLI_ONLY_BY_DESIGN` | — |
| `semanticdb-for-source` | `CLI_ONLY_BY_DESIGN` | — |
| `usages` | `CLI_ONLY_BY_DESIGN` | — |
| `infer-type` | `CLI_ONLY_BY_DESIGN` | — |
| `infer-type-batch` | `CLI_ONLY_BY_DESIGN` | — |
| `help`, `version` | `CLI_ONLY_BY_DESIGN` | — |

Mutable alpha-3 SNAPSHOT development additionally provides
`tasty-point-evidence` as `CLI_ONLY_BY_DESIGN`. It owns a fresh selected
`Compile` request and has no ninth MCP tool. It is not present in the immutable
alpha-2 package or supported alpha-2 installation route.

No current alpha-2 CLI operation is `MCP_CANDIDATE_NOT_ADMITTED`. That status
requires a concrete agent use hypothesis and separate admission; apparent
symmetry is not sufficient.

## Verify and troubleshoot

After configuration, ask the client to list tools before approving any
build-executing operation. The registry must contain, in order:

```text
semantic_compile
semantic_errors
semantic_test
semantic_effect_summary
semantic_symbol_at
semantic_symbols
semantic_reconcile_symbol
semantic_point_evidence
```

For a harmless smoke call, ask for `semantic_effect_summary` on one Scala file.
For example: “Using semantic-scala, summarize the effect shape of
`src/main/scala/example/UserRepo.scala`; do not compile or edit anything.”
The compile, errors, and test tools may execute target build logic and require
separate approval. If MCP attachment fails, verify JDK 21, run
`semantic-scala version`, confirm the client can resolve
`semantic-scala-mcp`, and use the CLI directly while diagnosing the client.

For update behavior, source identity, uninstall, and Coursier warning
interpretation, see [`distribution.md`](distribution.md). For detailed result
semantics and uncertainty, see [`semantic-api.md`](semantic-api.md).
