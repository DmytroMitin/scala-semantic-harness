# Claude community submission preparation

Observed on 2026-09-21. This is an owner-review packet, not a submitted or
published listing.

## Outcome

```text
CLAUDE_COMMUNITY_SUBMISSION_READY_PENDING_HUMAN_REVIEW_AND_SUBMIT
```

The listing identity and copy are prepared. The exact accepted plugin is now
published as a byte-verified, commit-pinned public Git source, passes the
current strict and community external-source validators, and has passed an
isolated public-source install plus one read-only Claude client semantic call.
No community submission, draft, terms acceptance, or review request exists yet.

## Prepared listing

- Name and display name: `semantic-scala`
- Version: `0.1.0-alpha.3`
- Category: `development`
- Repository: <https://github.com/DmytroMitin/scala-semantic-harness>
- Public plugin source: <https://github.com/DmytroMitin/semantic-scala-claude-plugin>
- Public source commit: `c05aac9f38e7755a51f511078ff555a587f97ccf`
- Plugin root: repository root
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

## Verified public distribution source

Claude Code's generic marketplace format supports Git repositories,
repository subdirectories, npm packages, HTTPS zip archives, and
command-produced plugin directories. The current `claude-community` review
pipeline is narrower: its external-source validation clones `github`, `url`,
or `git-subdir` sources and requires a 40-character lowercase commit SHA.

The selected source is the root of
<https://github.com/DmytroMitin/semantic-scala-claude-plugin> at commit
`c05aac9f38e7755a51f511078ff555a587f97ccf`. Its single root tree is exactly the
accepted generated plugin: content SHA-256
`72230630cb739d71e0003cb50728bb928dfa786c5184454c41bcb4aa3015419b`,
3,730 inventoried files, and 339,741,892 inventoried bytes. An anonymous HTTPS
clone reproduced the accepted byte-and-mode inventory and passed strict Claude
validation. Anthropic's current published external-source action then cloned
and validated the same pinned commit. Claude Code `2.1.278` installed it from a
disposable marketplace, exposed one skill and one MCP server, and completed
exactly one read-only `semantic_effect_summary` call successfully.

The main `scala-semantic-harness` repository remains the source of truth for the
skill policy, packaging templates, assembler, runtime provenance, issues, and
future generation. The dedicated repository is generated distribution material
only. The maintained template subdirectory and oversized archive alternatives
remain non-selected routes.

## Human boundary

No form login, draft, terms acceptance, attestation, review request, catalog
mutation, release creation, payment, or final submit was performed. The owner
must review all post-login fields and legal or policy attestations before a
separately authorized human submission. The public distribution repository and
its one initial commit are the only publication performed for this source gate.
