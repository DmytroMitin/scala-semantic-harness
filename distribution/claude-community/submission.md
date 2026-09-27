# Claude community submission preparation

Originally prepared on 2026-09-21, rechecked against the first-party directory
contract on 2026-09-27, and exercised in the authenticated portal on
2026-09-27--28. This is an owner-review packet for a public-source-qualified
thin candidate with one saved portal draft, not a submitted or published
listing.

## Outcome

```text
CLAUDE_DIRECTORY_SUBMISSION_BLOCKED_ON_OWNER_ACKNOWLEDGEMENT
```

The public distribution repository now has the accepted six-file thin wrapper
on `main` at commit `fc3a6c8e2ce74923141b9a5b740513bbee04ac11` and tree
`da2ce788648462dd461a8f7b09c8e33159df1f54`. The historical full plugin remains
available at its parent commit, `c05aac9f38e7755a51f511078ff555a587f97ccf`.
An anonymous clone reproduced the accepted 39,938-byte content exactly. The
actual GitHub source archive is 16,983 bytes and passes every documented hard
limit with large headroom. Current strict source/marketplace validation and real
public-source cold/warm installed-client treatments passed. Published evaluator
guidance can hold local launcher/download chains for human review, so this is a
validation pass with an expected future human-review gate, not directory
acceptance. The authenticated
portal resolved `main` to `fc3a6c8`, passed all seven source checks with one
missing-icon warning, and saved one exact draft. Submission stopped at the
compliance step: the portal requires an acknowledgement that the plugin's
privacy policy accurately describes its data handling, but the qualified source
provides no privacy-policy link and the prepared technical note explicitly says
it is not a legal privacy policy. All four acknowledgements remained unchecked,
no contact email was entered, and no review request or public listing was
created.

## Prepared listing

- Name and display name: `semantic-scala`
- Version: `0.1.0-alpha.3`
- Category: `development`
- Repository: <https://github.com/DmytroMitin/semantic-scala-claude-plugin>
- Portal branch value: `main` (or the repository default branch where the form
  omits an explicit branch)
- Immutable qualification commit: `fc3a6c8e2ce74923141b9a5b740513bbee04ac11`
- Historical full-plugin source: <https://github.com/DmytroMitin/semantic-scala-claude-plugin>
- Historical source commit: `c05aac9f38e7755a51f511078ff555a587f97ccf`
- Thin-plugin source: public repository `main`, qualified at the commit above
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
The thin candidate fits the published hard limits, and authenticated source
validation passed, but its runtime bootstrap is expected to receive human
security review. The current Directory Policy requires a clear privacy-policy
link for software that collects user data or connects to a remote service. The
fixed GitHub runtime download makes that legal/policy gate relevant, while the
qualified source contains only a technical data-handling note that disclaims
being a legal privacy policy. A separately authorized source-publication task
must resolve and qualify that link before the existing draft can truthfully
continue. The public documentation states no separate submission fee, although
a paid Claude plan is required.

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

The selected portal value is
<https://github.com/DmytroMitin/semantic-scala-claude-plugin> with branch
`main` and plugin root at the repository root. The immutable qualification
record is commit `fc3a6c8e2ce74923141b9a5b740513bbee04ac11`, tree
`da2ce788648462dd461a8f7b09c8e33159df1f54`, and parent
`c05aac9f38e7755a51f511078ff555a587f97ccf`. Its current tracked tree contains
exactly the accepted six files, content SHA-256
`ab0d5feaed7b4b9e6313e61116b5b3fc86d2cc5bc114b127a6290de5e29e8b8f`,
and 39,938 bytes. An anonymous clone reproduced the byte and mode inventory and
passed current strict Claude validation.

The actual GitHub archive for the qualified commit is available at
<https://github.com/DmytroMitin/semantic-scala-claude-plugin/archive/fc3a6c8e2ce74923141b9a5b740513bbee04ac11.zip>.
It measured 16,983 bytes with SHA-256
`6fcc25d296a36d43ca9a45229ec55c581a76032f0bf296b605deb378f98e0587`.
Safe extraction produced ten total entries including directories, six files,
39,938 plugin bytes, and a 21,188-byte largest file. Both an exact-commit source
fixture and a portal-like `main` fixture resolved to this tree and passed
current strict marketplace validation.

The historical full self-contained plugin remains available at the parent
commit. That tree retains its earlier anonymous/source/client evidence but is
not the current portal source because it exceeds the unpacked and per-file hard
limits. The main `scala-semantic-harness` repository remains authoritative for
the skill policy, templates, assembler, runtime provenance, issues, and future
generation; the dedicated repository is generated distribution material only.

## Qualified public thin source

The thin candidate uses Python 3.11 or newer to download the immutable Alpha-3
MCPB on first MCP start. Its URL, expected 285,603,142-byte size, and SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`
are fixed. The bootstrap verifies them before safe extraction and atomic
owner-only cache installation. Warm starts reuse the cache without a fetch, and
the downloaded MCPB is not retained. No host Java is required. Claude Code
`2.1.283` connected the exact-eight server and completed one read-only
`semantic_effect_summary` call in both cold and warm client sessions. This is
public-source qualification evidence, not a directory acceptance claim. During
the cold session the client's first tool invocation used an invalid absolute
file value and was rejected before semantic execution; one corrected read-only
call then succeeded. The warm session made exactly one read-only semantic call.
This discrepancy is retained in the evidence and side-effect ledger.

## Human boundary

An existing authenticated Pro session was used without retaining credentials,
cookies, MFA data, or the account contact value. The initial dashboard contained
zero submissions. Exactly one validation attempt resolved `main` to the
qualified commit and passed. The owner approved the four factual data-handling
answers (`No`, `No`, `Not retained`, `No`), which the portal saved in one
exact draft. No compliance acknowledgement was approved or checked, no contact
email was entered, and the final review page was not reached. `Submit for
review` was not clicked. The resulting dashboard contains one `semantic-scala`
draft and no pending-review or public listing. Resume that exact draft only
after a separate authorized task publishes and qualifies an applicable
privacy-policy link; do not create a duplicate.
