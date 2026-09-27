# Claude community submission preparation

Originally prepared on 2026-09-21 and rechecked against the current first-party
directory contract on 2026-09-27. This is a blocked owner-review packet, not a
submitted or published listing.

## Outcome

```text
CLAUDE_COMMUNITY_SUBMISSION_BLOCKED_BY_CURRENT_DIRECTORY_PACKAGE_LIMITS
```

The listing identity and copy remain prepared, and the exact accepted plugin is
still available as a byte-verified public Git source. Anthropic has replaced the
earlier Console submission flow with the claude.ai directory developer portal.
Its public guide documents a branch or tag and no exact commit-SHA field; the
authenticated form was not inspected. Its current validator stops for a GitHub
archive of 50 MiB or more, an unpacked plugin of 256 MiB or more, or an
individual file of 5 MiB or more. This candidate is 339,741,892 unpacked bytes
and contains a 54,008,260-byte runtime image file. Task 252 therefore stopped
before login. Task 252 created no draft, validation attempt, terms acceptance,
or review request; preexisting private dashboard state remains unknown.

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

## Current public route and blocker

Anthropic's current route is the directory developer portal at
<https://claude.ai/directory/manage>. Anyone on a paid Pro, Max, Team, or
Enterprise plan can submit with the documented role. Team requires an Owner;
Enterprise permits an Owner or a member granted the custom Directory role. The
older Claude Console form is no longer supported. A plugin submission uses a
GitHub repository, optional plugin path, and optional tracked branch or tag,
then portal validation, data-handling questions, four compliance
acknowledgements, and `Submit for review`.

The final action remains a review request rather than immediate publication,
but this exact candidate cannot reach that action under the current published
validator limits. The current public documentation states no separate
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

## Human boundary

No form login, GitHub connection, draft, portal validation, terms acceptance,
attestation, review request, catalog mutation, release creation, payment, or
final submit was performed. A separate technical task would be required to
produce and qualify a plugin that satisfies the current directory limits; this
packet is not authority to redesign or republish the candidate. The public
distribution repository and its one initial commit remain unchanged.
