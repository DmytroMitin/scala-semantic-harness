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
The historical self-contained candidate remains at distribution commit
`c05aac9f38e7755a51f511078ff555a587f97ccf`; its 339,741,892 unpacked bytes and
54,008,260-byte runtime image exceed current portal limits. Public `main` now
contains the six-file thin wrapper qualified at
`05d4f0de35a02916504bf29156bc42270cf77a23`. Its actual 17,129-byte GitHub
source archive, 40,205 unpacked bytes, 21,188-byte largest file, and eleven total
archive entries pass every documented hard limit. Exact-pin and `main` source
validation passed. The bootstrap and runtime bytes are unchanged, so the prior
public-source cold/warm Claude Code evidence remains applicable. Published
evaluator guidance can hold the launcher/download chain for human review. The
authenticated portal revalidated `main` to `05d4f0d` and passed seven checks
with one missing-icon warning. The owner-approved
[privacy policy](../PRIVACY.md) is public and linked from the plugin README.
The exact draft was submitted once on 2026-09-28. The security scan completed
and sent qualified version `05d4f0d` to content-policy review because shipped
code could not be cleared automatically. The status is In review, with no
public listing. Versions history retains the earlier `fc3a6c8` detection event;
follow-up must use the existing submission without creating a duplicate.

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
authorized, deployed, production HTTPS endpoint and the applicable remote-MCP
review materials.

The independent `Skills only` candidate is maintained under
[`packaging/openai-skills-plugin/`](../packaging/openai-skills-plugin/). It is a
seven-file package with no MCP configuration. Its allowlisted helper downloads
and verifies the immutable Alpha-3 MCPB on first use, safely caches the verified
runtime, executes only its direct CLI entry point, and exposes one read-only
`effect-summary` workflow. A
clean cold environment, warm cache-only reuse, and a disposable Codex CLI
marketplace installation all returned structured semantic evidence without
changing the fixture or starting an MCP process. The package and owner packet
are locally prepared but have not been uploaded or submitted.

Current first-party guidance requires product-specific OpenAI contact before
submission when a plugin's core value depends on local execution, arbitrary
local-file access, or offline operation. That determination remains a human
prerequisite for this candidate. The prepared owner-approved privacy amendment
covers the OpenAI/Codex path, first-use download, and separate cache; the public
URL will reflect it after separately authorized repository publication. The
package remains held on original production logo and composer-icon assets.
Verified identity, Apps Management write access, country selection, and policy
attestations are portal-only prerequisites. Current submission-error guidance
marks website, support, privacy, and terms URLs optional for a skills-only ZIP,
so Apache-2.0 remains the software license and no separate terms document is
being represented as a current skills-only requirement. The current
first-party documentation does not identify a submission fee; verify that
again before any human action. Submission begins review; approval and
publication remain separate owner decisions.

See [`openai-skills-only-plugin.md`](openai-skills-only-plugin.md) and the
[prepared submission packet](../distribution/openai-skills-only/submission.md)
for the exact supported surface, listing claims, tests, and remaining human
prerequisites.

### Claude community marketplace

The current human entry point is the
[Claude directory developer portal](https://claude.ai/directory/manage).
The historical self-contained candidate passed strict validation with Claude
Code `2.1.278` and remains preserved at commit
`c05aac9f38e7755a51f511078ff555a587f97ccf` in
[`DmytroMitin/semantic-scala-claude-plugin`](https://github.com/DmytroMitin/semantic-scala-claude-plugin).
Its earlier anonymous clone, external-source validation, isolated installation,
and one read-only semantic call remain historical evidence. Public `main` now
contains the thin directory-compatible wrapper, qualified at commit
`05d4f0de35a02916504bf29156bc42270cf77a23`. The maintained template and
assembler in this repository remain the source of truth. See the
[owner preparation packet](../distribution/claude-community/submission.md).

That full candidate remains valid historical marketplace evidence but exceeds
the current directory's unpacked and individual-file limits. A separate thin
candidate under `packaging/claude-directory-plugin/` keeps the canonical skill
and local MCP interface while downloading the immutable Alpha-3 MCPB on first
start. It requires Python 3.11 or newer, verifies the fixed 285,603,142-byte
artifact and fixed SHA-256 before safe extraction, and stores an owner-only
atomic installation in the semantic-scala user cache. It retains no MCPB after
installation and needs no host Java. The deterministic candidate contains 6
files and 40,205 unpacked bytes; its largest file is 21,188 bytes and its ZIP is
14,843 bytes. Claude Code `2.1.283` passed strict validation for the privacy-link
change. Earlier cold-cache and warm/offline client evidence remains applicable
because bootstrap and runtime bytes are unchanged.
See [the thin-plugin contract](claude-directory-thin-plugin.md).

The older Claude Console form is no longer supported. The current portal is
available to paid Pro, Max, Team, and Enterprise accounts with the documented
role, checks GitHub push access, follows a branch or tag, validates the plugin,
asks data-handling questions, and requires four compliance acknowledgements
before `Submit for review`. Current first-party documentation does not identify
a separate submission fee. Authenticated revalidation passed for public `main`
at exact commit `05d4f0de35a02916504bf29156bc42270cf77a23`. The current
qualified source exposes the owner-approved privacy policy through its README.
The owner approved all four acknowledgements, entered the private contact
field, and authorized exactly one review submission. The security scan completed and the qualified `05d4f0d` version is In review
under a content-policy hold because shipped code could not be cleared
automatically. No public listing exists. Versions history retains the earlier
`fc3a6c8` detection event; runtime bootstrap remains a human review gate.

Prepared facts are the strict-valid manifest, public commit-pinned source, local
stdio configuration, canonical skill, exact runtime provenance, platform
boundary, deterministic inventory, listing copy, reviewer plan, technical
data-handling note, and public privacy-policy link. Review-status monitoring must
not create a duplicate submission. Any later publication remains separate.

## Catalog pause

The official MCP Registry record was published at
`2026-09-20T00:55:40.636118Z`. Do not infer missing downstream syndication or
make a secondary manual submission before the planned 24-to-48-hour observation
window, from `2026-09-21T00:55:40.636118Z` through
`2026-09-22T00:55:40.636118Z`. Native plugin publication is independent of
that pause and still requires explicit authority.
