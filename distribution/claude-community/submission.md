# Claude community submission preparation

Originally prepared on 2026-09-21 and rechecked against the current first-party
directory contract on 2026-09-27. This is an owner-review packet for a locally
qualified thin candidate, not a submitted or published listing.

## Outcome

```text
CLAUDE_DIRECTORY_THIN_PLUGIN_READY_FOR_PUBLIC_SOURCE_QUALIFICATION
```

The public Git source still contains the accepted historical full plugin. It is
339,741,892 unpacked bytes and contains a 54,008,260-byte file, so it remains
blocked by the current 256 MiB unpacked and 5 MiB individual-file limits. A new
local thin candidate instead contains 6 files, 39,938 unpacked bytes, a
21,188-byte largest file, and a 14,697-byte ZIP. It passed deterministic build,
strict Claude validation, focused bootstrap security tests, and real cold/warm
installed-client treatments. The thin candidate is not yet in the public plugin
repository and has not been accepted by the directory portal. No draft,
validation attempt, terms acceptance, or review request was created;
preexisting private dashboard state remains unknown.

## Prepared listing

- Name and display name: `semantic-scala`
- Version: `0.1.0-alpha.3`
- Category: `development`
- Repository: <https://github.com/DmytroMitin/scala-semantic-harness>
- Historical full-plugin source: <https://github.com/DmytroMitin/semantic-scala-claude-plugin>
- Historical source commit: `c05aac9f38e7755a51f511078ff555a587f97ccf`
- Thin-plugin source: local candidate, not yet published
- Plugin root: repository root
- License: Apache-2.0
- Support: <https://github.com/DmytroMitin/scala-semantic-harness/issues>
- Host: Python 3.11 or newer; first-start network access; no host Java
- Runtime: Linux x86_64, compatible GNU libc, and system zlib

Short description:

> Bounded Scala compiler, build, test, type, effect, symbol, reconciliation,
> and SemanticDB evidence for coding agents.

The longer copy in `submission.json` describes one agent skill, one local stdio
MCP server, the exact platform boundary, and Alpha-3 status without claiming an
IDE replacement, autonomous correctness, broad platform support, or guaranteed
build success.

## Current public route and remaining gate

Anthropic's current route is the directory developer portal at
<https://claude.ai/directory/manage>. Anyone on a paid Pro, Max, Team, or
Enterprise plan can submit with the documented role. Team requires an Owner;
Enterprise permits an Owner or a member granted the custom Directory role. The
older Claude Console form is no longer supported. A plugin submission uses a
GitHub repository, optional plugin path, and optional tracked branch or tag,
then portal validation, data-handling questions, four compliance
acknowledgements, and `Submit for review`.

The final action remains a review request rather than immediate publication.
The thin candidate fits the published hard limits, but its runtime bootstrap
may receive human security review. Public-source publication and qualification
must precede any portal use. The public documentation states no separate
submission fee, although a paid Claude plan is required.

Public sources checked:

- <https://code.claude.com/docs/en/plugins>
- <https://code.claude.com/docs/en/plugins-reference>
- <https://code.claude.com/docs/en/plugin-marketplaces>
- <https://claude.com/docs/directory/publish>
- <https://claude.com/docs/plugins/submit>
- <https://claude.com/docs/plugins/pre-submission-checklist>
- <https://github.com/anthropics/claude-plugins-community>
- <https://github.com/anthropics/claude-plugins-community/blob/main/.github/actions/validate-plugins/README.md>

The public community catalog remains a reviewed distribution surface, but the
developer portal now owns submission, review, version tracking, and publication.

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

## Locally qualified thin candidate

The thin candidate uses Python 3.11 or newer to download the immutable Alpha-3
MCPB on first MCP start. Its URL, expected 285,603,142-byte size, and SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`
are fixed. The bootstrap verifies them before safe extraction and atomic
owner-only cache installation. Warm starts reuse the cache without a fetch, and
the downloaded MCPB is not retained. No host Java is required. Claude Code
`2.1.283` connected the exact-eight server and completed one read-only
`semantic_effect_summary` call in both cold and warm client sessions. This is
local technical evidence only; the exact public source remains unqualified.

## Human boundary

No form login, GitHub connection, draft, portal validation, terms acceptance,
attestation, review request, catalog mutation, release creation, payment, or
final submit was performed. This packet is not authority to publish or submit
the thin candidate. The public distribution repository and its one initial
commit remain unchanged. A later explicit task must publish the thin candidate
and requalify the exact public branch or commit before portal work resumes.
