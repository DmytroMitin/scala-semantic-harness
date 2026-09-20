# Native plugin package candidates

## Status and boundary

The repository can assemble locally validated OpenAI/Codex and Claude Code
plugin candidates from the exact published `0.1.0-alpha.3` MCPB. A disposable
local Codex marketplace installation has now passed real-client skill and MCP
adoption. Claude Code has now passed the equivalent disposable
local-marketplace installation, skill discovery, plugin-local MCP connection,
and one read-only client-mediated semantic call after an explicit owner login
checkpoint. Neither candidate has been publicly submitted, listed, or
published as a release asset.

Both candidates contain:

- [`skills/semantic-scala/SKILL.md`](../skills/semantic-scala/SKILL.md), copied
  byte-for-byte;
- the exact eight-tool Alpha-3 MCP server and CLI;
- the package-local Corretto 21 runtime and notices already qualified in the
  published MCPB; and
- native manifests and relative runtime paths for the selected client.

They inherit the MCPB boundary: Linux x86_64 with a compatible GNU-libc
environment and system zlib. They require no host Java. They are not macOS,
Windows, ARM, musl, or general-Linux candidates.

The source artifact is the public
[`semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb`](https://github.com/DmytroMitin/scala-semantic-harness/releases/download/0.1.0-alpha.3/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb),
285,603,142 bytes with SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`.
The assembler rejects any other bytes before extraction. Mutable Alpha-4
source stages are not substituted.

## Current native contracts

The contracts below were checked against first-party documentation on
2026-09-20.

OpenAI now prefers a portable Agent Plugins root `plugin.json`, root
`mcp.json`, and `skills/`; `.codex-plugin/plugin.json` remains a compatibility
fallback. A local Codex package can retain the bundled stdio MCP server, so the
candidate includes both the portable core and a compatibility overlay. Public
OpenAI MCP submission is different: the current portal requires a stable
public HTTPS MCP endpoint. This local-stdio candidate is therefore locally
valid but not eligible for public MCP submission as-is. A future owner may
separately choose a skills-only submission or authorize and qualify a remote
service; neither exists here.

- [OpenAI package documentation](https://developers.openai.com/plugins/build/plugins)
- [OpenAI submission documentation](https://developers.openai.com/plugins/deploy/submission)

Claude Code uses `.claude-plugin/plugin.json`, root `.mcp.json`,
`skills/<name>/SKILL.md`, and `${CLAUDE_PLUGIN_ROOT}` for package-local paths.
The candidate follows that layout and bundles the local stdio server directly.
Claude Code's generic marketplace format can use Git, npm, HTTPS archive, and
command-produced sources. The current public community review pipeline is
narrower: it validates cloneable `github`, `url`, or `git-subdir` sources pinned
to a 40-character lowercase commit SHA. The maintained template is not a complete
installable plugin, and the complete generated candidate is absent from any
public pinned commit. As supplementary evidence, a fresh deflate-9 zip of the
exact candidate measured 285,354,435 bytes, above the generic marketplace's
256 MiB archive limit. No currently published source therefore installs the
qualified candidate through the community catalog.

- [Claude Code plugin documentation](https://code.claude.com/docs/en/plugins)
- [Claude Code plugin reference](https://code.claude.com/docs/en/plugins-reference)
- [Claude Code marketplace documentation](https://code.claude.com/docs/en/plugin-marketplaces)

## Build and validate

Retain or download the exact MCPB above, then use fresh output paths:

```bash
python3 -m unittest scripts.tests.test_package_native_plugins

python3 scripts/package-native-plugins.py assemble \
  --platform openai \
  --mcpb target/mcpb/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb \
  --output target/native-plugins/openai/semantic-scala

python3 scripts/package-native-plugins.py assemble \
  --platform claude \
  --mcpb target/mcpb/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb \
  --output target/native-plugins/claude/semantic-scala

python3 scripts/package-native-plugins.py validate \
  --platform openai \
  --plugin-root target/native-plugins/openai/semantic-scala

python3 scripts/package-native-plugins.py validate \
  --platform claude \
  --plugin-root target/native-plugins/claude/semantic-scala
```

The assembler refuses existing outputs, safely extracts only the MCPB runtime
payload, rejects links and path traversal, copies native templates plus the
canonical skill, scans text for machine-specific paths and strong credential
markers, and writes a deterministic path/mode/size/SHA-256 inventory.

For relocation and runtime validation, use fresh paths containing spaces:

```bash
python3 scripts/package-native-plugins.py smoke \
  --platform openai \
  --plugin-root '/tmp/semantic scala openai' \
  --plugin-data '/tmp/semantic scala openai data'

python3 scripts/package-native-plugins.py smoke \
  --platform claude \
  --plugin-root '/tmp/semantic scala claude' \
  --plugin-data '/tmp/semantic scala claude data'
```

The smoke removes host Java from `PATH`, initializes the bundled server,
requires the ordered exact-eight registry, performs one read-only
`semantic_point_evidence` call in a disposable external project, requires no
project mutation, and verifies that no helper process remains.

Two clean builds produced identical inventories. The current content hashes
are:

- OpenAI/Codex: `55488e2c4f0b4261d028f6c8c3b68139309b649c76bd4ecf1aa536ed8ecb07ca`
  across 3,731 files and 339,743,164 bytes;
- Claude Code: `72230630cb739d71e0003cb50728bb928dfa786c5184454c41bcb4aa3015419b`
  across 3,730 files and 339,741,892 bytes.

OpenAI's current plugin-creator validator passed the compatibility overlay, and
the portable manifests passed the current Agent Plugins 1.0 JSON schemas.
Claude Code `2.1.220` passed `claude plugin validate <path> --strict`. That
installed version predates the validator's JSON-output option, so the retained
local result is textual rather than machine JSON.

## Real-client qualification

Observed on 2026-09-20 with Codex CLI `0.154.0` and Claude Code `2.1.220`.

Codex passed the supported disposable local-marketplace flow under an isolated
`CODEX_HOME`: marketplace add/list, plugin install/list, packaged-skill loading,
bundled MCP registration, and a real `semantic_effect_summary` call all passed.
The call returned adapter `ok: true` for a read-only one-file fixture, and the
complete fixture remained unchanged. Plugin and marketplace removal left no
active disposable integration. The owner's normal Codex plugin state and
configuration were unchanged. This qualifies local installed-client plumbing;
it does not create a public listing or a supported public install channel.

Claude Code passed strict native and local-marketplace validation, disposable
marketplace add/install/list, packaged-skill discovery, and plugin-local MCP
connection under an isolated `CLAUDE_CONFIG_DIR`. After the owner completed the
official login checkpoint, a fresh client session invoked exactly one
`semantic_effect_summary` call through the installed plugin. The adapter
returned `ok: true`, schema `semantic-scala.effect-summary.v1`, method `value`
in `Fixture`, and declared return type `Option[Int]`. Cleanup removed the
disposable plugin, marketplace, and task-scoped cache logs; the fixture
remained unchanged, and the owner's normal Claude plugin and settings state
remained unchanged. This qualifies local installed-client plumbing; it does
not create a public listing or a supported public install channel.

## Generated layouts

The OpenAI/Codex candidate adds native metadata to the shared payload:

```text
semantic-scala/
├── plugin.json
├── mcp.json
├── .codex-plugin/plugin.json
├── skills/semantic-scala/SKILL.md
├── bin/
├── app/
├── runtime/
├── LICENSES/
└── package-manifest.json
```

The root portable manifest and `mcp.json` are authoritative. The compatibility
overlay declares the skill and presentation metadata but deliberately does not
duplicate the MCP declaration.

The Claude Code candidate uses:

```text
semantic-scala/
├── .claude-plugin/plugin.json
├── .mcp.json
├── skills/semantic-scala/SKILL.md
├── bin/
├── app/
├── runtime/
├── LICENSES/
└── package-manifest.json
```

## Future human publication actions

No action in this section was performed.

### OpenAI universal directory

The future human entry point is the
[OpenAI plugin submission portal](https://platform.openai.com/plugins).
The submitter must sign in to the owning Platform organization, have Apps
Management write permission, use a verified developer or business identity,
review current policy attestations and terms, and choose the countries where
support and legal terms are ready. The current local-MCP candidate must not be
uploaded as a `With MCP` submission: that lane requires a separately
authorized, deployed, production HTTPS endpoint, domain verification, accurate
tool annotations, privacy/terms/support URLs, five positive tests, and three
negative tests. The alternative `Skills only` lane would require a separately
assembled final skill bundle and the same listing/test materials. The current
first-party documentation does not identify a submission fee; verify that
again before any human action. Submission starts review and remains reversible
as a draft; approval still requires a separate owner choice to publish.

Prepared facts are the name, descriptions, author, repository, license,
category/tags, canonical skill, platform boundary, and validated local runtime.
Not prepared are a logo, public privacy/terms/support pages, a remote endpoint,
tool annotations, portal test cases, country selection, or policy
attestations.

### Claude community marketplace

The future human entry point is the
[Claude plugin submission form](https://platform.claude.com/plugins/submit).
The exact candidate passed strict validation with a disposable current Claude
Code `2.1.278`, but the submission form must not be opened as an action path
yet. The maintained Git subdirectory omits generated runtime content, and no
complete candidate exists at a public commit SHA for the current community
review pipeline. Separately, the exact complete zip is 16,918,979 bytes above
the generic marketplace's current 256 MiB archive cap. See the
[owner preparation packet](../distribution/claude-community/submission.md).

After a separate task publishes and validates an installable source, the human
submitter can sign in to Claude Console, review then-current terms and review
requirements, provide the repository and listing materials, and submit to the
`claude-community` review lane. The official `claude-plugins-official`
marketplace remains separately curated and has no general application process.
Current first-party documentation does not identify a submission fee; verify
that again before action. After acceptance, verify the pinned commit and public
catalog entry before claiming installability.

Prepared facts are the strict-valid manifest, local stdio configuration,
canonical skill, exact runtime provenance, platform boundary, and deterministic
inventory, listing copy, reviewer plan, and technical data-handling note.
Marketplace authentication, terms acceptance, review, and any public listing
remain human-only actions after the distribution blocker is resolved.

## Catalog pause

The official MCP Registry record was published at
`2026-09-20T00:55:40.636118Z`. Do not infer missing downstream syndication or
make a secondary manual submission before the planned 24-to-48-hour observation
window, from `2026-09-21T00:55:40.636118Z` through
`2026-09-22T00:55:40.636118Z`. Native plugin publication is independent of
that pause and still requires explicit authority.
