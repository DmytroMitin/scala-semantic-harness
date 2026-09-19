# Discoverability

semantic-scala has one canonical agent skill and one exact-eight-tool stdio MCP
server. Stable catalog-facing facts are checked in at
[`distribution/discovery/metadata.json`](../distribution/discovery/metadata.json).
That file is reusable input, not a claim that any external catalog has accepted
or published the project.

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
accepted directly by the official MCP Registry. A future registry submission
therefore requires a separately versioned MCPB or OCI distribution, a matching
`server.json`, and validation with the official publisher CLI. No such registry
artifact or listing is currently supported.

The generated [Agent Plugin package](agent-plugin.md) is useful packaging
evidence, but it is not itself a listing in a public plugin directory. Current
native directory routes also have distinct contracts:

- OpenAI's plugin directory can accept a skill-only plugin, while an MCP plugin
  requires a stable public HTTPS endpoint rather than this local stdio server.
- Claude Code expects `.claude-plugin/plugin.json`, root `.mcp.json`, and
  `${CLAUDE_PLUGIN_ROOT}` paths; the current portable Agent Plugin uses different
  manifest and variable conventions.

Native wrappers should be generated from the canonical skill and validated in a
separate packaging change. The client-neutral skill remains authoritative.

## Scope of catalog claims

Catalog indexes and submission rules change independently of this repository.
Absence from a bounded search is not proof of permanent absence, and an upstream
registry may syndicate a listing into downstream catalogs. Prefer one canonical
upstream record and avoid duplicate submissions unless a catalog documents an
independent route.
