# Discoverability

semantic-scala has one canonical agent skill and one exact-eight-tool stdio MCP
server. Stable catalog-facing facts are checked in at
[`distribution/discovery/metadata.json`](../distribution/discovery/metadata.json).
That file is reusable input and does not by itself claim external acceptance.
The exact official MCP Registry publication described below is the one verified
catalog exception.

## Install the current canonical skill

From the target project root, run:

```bash
npx skills add https://github.com/DmytroMitin/scala-semantic-harness --skill semantic-scala
```

This route was qualified with `skills` CLI 1.7.0: the repository exposed one
candidate named `semantic-scala`, and the installed `SKILL.md` was byte-identical
to [`skills/semantic-scala/SKILL.md`](../skills/semantic-scala/SKILL.md). The
command installs current repository guidance; it does not install the Scala
runtime or pin a released skill revision. Use the immutable-tag manual procedure
in [`agent-onboarding.md`](agent-onboarding.md) when a frozen skill revision is
required.

The current supported runtime remains exact `0.1.0-alpha.3`. Install the
`semantic-scala` and `semantic-scala-mcp` applications through the documented
[Coursier channel](distribution.md), then configure `semantic-scala-mcp` as a
local stdio server. Skill installation does not change the eight-tool registry.

## Registry and plugin status

The existing Maven Central artifacts and Coursier channel are not package types
accepted directly by the official MCP Registry. The separate exact-Alpha-3
MCPB is attached to the existing GitHub prerelease, and the exact active
official record is maintained in
[`server.alpha3.json`](../distribution/mcp-registry/server.alpha3.json) under
`io.github.DmytroMitin/semantic-scala`. Its public URL, size, and SHA-256 were
read back anonymously before the Registry record was published once and
verified through the anonymous official API. See
[`mcpb-package.md`](mcpb-package.md).

This package is only for Linux x86_64 with compatible GNU libc and system zlib;
it requires no host Java. Official Registry availability does not widen that
boundary and does not establish indexing by any downstream catalog.

The generated [Agent Plugin package](agent-plugin.md) is useful packaging
evidence, but it is not itself a listing in a public plugin directory. The
repository now also carries [locally validated native candidates](native-plugin-packages.md)
with distinct contracts:

- OpenAI/Codex accepts the portable Agent Plugins core and an optional
  `.codex-plugin/plugin.json` compatibility overlay. The local candidate bundles
  the exact stdio runtime, but public MCP submission requires a stable public
  HTTPS endpoint rather than this local server.
- Claude Code's candidate uses `.claude-plugin/plugin.json`, root `.mcp.json`,
  and `${CLAUDE_PLUGIN_ROOT}` paths around the same exact runtime.

Both are deterministic generated candidates with byte-identical canonical
skill content and relocated exact-eight smoke evidence. Codex CLI `0.154.0`
also passed a disposable local-marketplace install, packaged-skill load,
bundled-MCP registration, and one read-only client-mediated semantic call.
Claude Code `2.1.220` passed disposable direct and marketplace loading through
skill discovery and MCP startup, then passed a separately authorized
disposable installed-client treatment with plugin-local MCP connection and one
read-only `semantic_effect_summary` call. The exact Claude candidate is now
also published as generated distribution material at commit
`c05aac9f38e7755a51f511078ff555a587f97ccf` in the public
[`semantic-scala-claude-plugin`](https://github.com/DmytroMitin/semantic-scala-claude-plugin)
repository. Claude Code `2.1.278` passed anonymous-source validation, isolated
install, skill/MCP discovery, and one read-only semantic call from that pin.
The Claude thin candidate has one submission in Needs changes. The reviewer
rejected its post-install GitHub Release download, so it is historical evidence
only and must not be resubmitted unchanged. No public directory listing is
verified. The client-neutral skill remains authoritative.

The Claude community packet is under
[distribution/claude-community/](../distribution/claude-community/). The
generated-distribution repository preserves the historical full candidate at
c05aac9f38e7755a51f511078ff555a587f97ccf and the rejected thin candidate at
05d4f0de35a02916504bf29156bc42270cf77a23.

The product-repository no-download candidate embeds a compact local MCPB in
the plugin and has no runtime bootstrap, updater, or executable download. The
MCPB is 145,197,017 bytes and 181,937,037 expanded bytes across 1,952 files;
the six-file ZIP is 144,399,347 bytes and 145,233,144 expanded bytes. Official
MCPB and Claude plugin validation, three byte-identical builds, the full
exact-eight suite, and one isolated installed-client semantic call pass.
Public Directory publication is blocked because the current GitHub-source
checklist requires an archive under 50 MiB and every plugin file under 5 MiB.
The distribution repository and live submission are unchanged. See
[claude-directory-embedded-plugin.md](claude-directory-embedded-plugin.md).

The product also maintains a tiny, contained Claude Directory skills-only
candidate under
[`packaging/claude-directory-skills-only-plugin/`](../packaging/claude-directory-skills-only-plugin/).
It has no declared or bundled runtime and instead teaches Claude Code to use an
independently installed `semantic-scala` CLI through ordinary shell
permissions. First-party policy supports this package shape, and its local
manifest, deterministic archive, contained-reach, and prerequisite-matrix
checks pass. Client qualification is incomplete because the isolated current
Claude session reached expired OAuth before model/tool execution; no public
listing or source-update claim follows. See
[`claude-directory-skills-only-plugin.md`](claude-directory-skills-only-plugin.md).

The independent OpenAI skills-only candidate is maintained under
[`packaging/openai-skills-plugin/`](../packaging/openai-skills-plugin/). It is
not the local-MCP native candidate and contains no MCP configuration. Its
package-contained helper exposes one read-only direct-CLI `effect-summary`
workflow, obtains the exact hash-pinned Alpha-3 runtime on first use, and reuses
the verified cache afterward. Clean cold, warm closed-proxy, and disposable
Codex CLI 0.157.1 installed-client proofs passed with unchanged fixture bytes
and no MCP process. This establishes local Codex technical value, not a public
listing or universal ChatGPT support. Before any OpenAI Platform draft, obtain
the product-specific determination required for local execution/file/offline
use. Current first-party guidance names the owner's OpenAI partner but exposes
no generic actionable contact route, so the result remains Class E /
`NO_CONTACT_ROUTE`, not eligibility. One original shared 512-by-512 SVG is
owner-approved and locally validated for both logo fields. Verified
identity, Apps Management write access, availability selection, and policy
attestations remain human gates. The OpenAI-specific privacy policy is public. See
[`openai-skills-only-plugin.md`](openai-skills-only-plugin.md).

## Scope of catalog claims

Catalog indexes and submission rules change independently of this repository.
Absence from a bounded search is not proof of permanent absence, and an upstream
registry may syndicate a listing into downstream catalogs. Prefer one canonical
upstream record and avoid duplicate submissions unless a catalog documents an
independent route.
