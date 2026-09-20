# Claude community submission preparation

Observed on 2026-09-20. This is an owner-review packet, not a submitted or
published listing.

## Outcome

```text
CLAUDE_COMMUNITY_SUBMISSION_BLOCKED_BY_PLUGIN_DISTRIBUTION_MODEL
```

The listing identity and copy are prepared, and the exact accepted plugin still
passes the current strict validator. The packet is not submission-ready because
there is no public source that Claude Code can currently install as the complete
qualified plugin.

## Prepared listing

- Name and display name: `semantic-scala`
- Version: `0.1.0-alpha.3`
- Category: `development`
- Repository: <https://github.com/DmytroMitin/scala-semantic-harness>
- License: Apache-2.0
- Support: <https://github.com/DmytroMitin/scala-semantic-harness/issues>
- Platform: Linux x86_64, compatible GNU libc, system zlib, no host Java

Short description:

> Bounded Scala compiler, build, test, type, effect, symbol, reconciliation,
> and SemanticDB evidence for coding agents.

The longer copy in `submission.json` describes one agent skill, one local stdio
MCP server, the exact platform boundary, and Alpha-3 status without claiming an
IDE replacement, autonomous correctness, broad platform support, or guaranteed
build success.

## Current public route

Anthropic's public third-party route is the reviewed `claude-community`
marketplace. Individual authors can enter through the Claude Console submission
page; the alternative claude.ai form requires a Team or Enterprise organization
and directory-management access. The public page is a login boundary, so the
exact post-login form fields, draft behavior, attestations, and any country or
region choice remain human-only unknowns.

Public sources checked:

- <https://code.claude.com/docs/en/plugins>
- <https://code.claude.com/docs/en/plugins-reference>
- <https://code.claude.com/docs/en/plugin-marketplaces>
- <https://platform.claude.com/plugins/submit>
- <https://github.com/anthropics/claude-plugins-community>
- <https://github.com/anthropics/claude-plugins-community/blob/main/.github/actions/validate-plugins/README.md>

Approved entries are pinned to a source commit in Anthropic's community catalog,
and the catalog syncs from the review pipeline. A separate publisher-operated
marketplace repository is not required by the public contract.

## Distribution blocker

Claude Code's generic marketplace format supports Git repositories,
repository subdirectories, npm packages, HTTPS zip archives, and
command-produced plugin directories. The current `claude-community` review
pipeline is narrower: its external-source validation clones `github`, `url`,
or `git-subdir` sources and requires a 40-character lowercase commit SHA. The
live catalog currently uses the latter two commit-pinned Git forms. No complete
qualified plugin is available through that required public Git model:

1. `packaging/claude-plugin/semantic-scala/` is a maintained source template,
   not a complete plugin. It omits the canonical skill and bundled runtime that
   the deterministic assembler adds.
2. The complete generated tree is 339,741,892 bytes across 3,730 files and is
   intentionally ignored build material, not content at a public pinned commit.
3. Generic Claude marketplace archive support does not provide a current
   community-review route. Independently, Claude Code rejects plugin archives
   larger than 256 MiB: a fresh deflate-9 zip measured 285,354,435 bytes,
   exceeding that limit by 16,918,979 bytes.
4. The published MCPB is 285,603,142 bytes and is not a Claude plugin archive
   layout.
5. No npm plugin package or existing product-owned command source assembles the
   plugin for users; those generic marketplace source types are not accepted by
   the current community review pipeline in any case. Inventing a downloader or
   changing runtime layout would be a separate packaging design and
   qualification task.

Do not submit the current repository path as if it were installable. A later
task must choose and publish an installable source route, then validate that
public route before human submission.

## Human boundary

No login, form draft, terms acceptance, attestation, review request, catalog
mutation, repository creation, release creation, payment, or final submit was
performed. The owner must review all post-login fields and legal or policy
attestations after the technical distribution blocker is resolved.
