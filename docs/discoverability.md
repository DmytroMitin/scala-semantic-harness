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
read-only `semantic_effect_summary` call. Neither candidate is publicly
submitted, listed, or a supported public install channel. The client-neutral
skill remains authoritative.

## Scope of catalog claims

Catalog indexes and submission rules change independently of this repository.
Absence from a bounded search is not proof of permanent absence, and an upstream
registry may syndicate a listing into downstream catalogs. Prefer one canonical
upstream record and avoid duplicate submissions unless a catalog documents an
independent route.
