# Claude Directory source and submission packet

This packet describes the current six-file `semantic-scala` skills-only source
and the existing Claude Directory submission. It is not evidence of a public
Directory listing or an executed resubmission.

## Current outcome

- Plugin distribution: `0.1.0-alpha.3.2`.
- Independently installed CLI prerequisite: exact `0.1.0-alpha.3`.
- Public source commit: `f28fe7a61ff449c9775d554d5bc9a11e2a9cfda1`.
- Public source tree: `62d8689c830dbf6460115c76fbd1a2df48fb775b`.
- Existing submission: `82a31369-158c-4fce-826a-752c4b7efe5b`.
- Submission status: `Needs changes`.
- Portal-resolved source: rejected thin commit `05d4f0d`.
- Resubmissions, new submissions, and listings created in this update: zero.

The public source update is qualified. The portal update is blocked: Settings
states that rejected submissions are not scanned through scheduled checks or
push webhooks and exposes no source refresh except `Resubmit for review`. That
representational action was deliberately not taken.

## Package contract

The package contains plugin metadata, one skill, the canonical semantic-scala
policy reference, a README, Apache-2.0 license, and deterministic manifest. It
contains no MCP server, CLI runtime, executable, hook, LSP server, installer,
updater, bootstrap, runtime download, or `allowed-tools` declaration.

Claude Code may invoke an independently installed `semantic-scala` command
through its ordinary shell permission flow. The skill never installs the CLI,
never substitutes `semantic-scala-mcp`, and refuses to fabricate evidence when
the prerequisite is absent.

Build-backed CLI commands may load a project build, resolve dependencies,
execute project/plugin/test code, and write normal outputs or caches. Installing
the skill grants no permission for those effects.

## Verified candidate

- Files: 6.
- Files plus directories: 10.
- Unpacked bytes: 41,500.
- Largest file: 21,188 bytes.
- Content SHA-256:
  `141362f7893bb555f770cde0091737695d7045052363ecf8ec836bd5edd9d40b`.
- Deterministic ZIP: 16,392 bytes.
- Deterministic ZIP SHA-256:
  `9488c0283393bdaf1c2b704d37c8f7f8021ea98c80db2ce4d76412c879c5d1af`.

Claude Code `2.1.220` strict plugin validation passes. Two deterministic builds
are byte-identical. The focused packaging tests and contained-reach checks pass.
Authenticated installed-client effect-summary, build-backed, missing-CLI,
no-tool, and surface-boundary cases pass.

## Verified public source

The source is the repository root of
<https://github.com/DmytroMitin/semantic-scala-claude-plugin> on `main`.
Anonymous HTTPS readback reproduced the candidate bytes and tree exactly.

The actual GitHub archive is:

<https://github.com/DmytroMitin/semantic-scala-claude-plugin/archive/f28fe7a61ff449c9775d554d5bc9a11e2a9cfda1.zip>

It measured 18,708 bytes with SHA-256
`6c507aa64ae6effe63552c62838685156f24919e62c6aaacfc3a703fcdfa30a5`.
It contains eleven entries including directories, six regular non-executable
files, 41,500 unpacked bytes, and a 21,188-byte largest file. It has no links,
runtime, download, bootstrap, or MCP declaration and passes current Directory
archive, unpacked-size, entry-count, and per-file limits.

History is preserved. The rejected thin source remains reachable at
`05d4f0de35a02916504bf29156bc42270cf77a23`, and the historical full package
remains reachable at `c05aac9f38e7755a51f511078ff555a587f97ccf`.

## Prepared listing copy

Name and display name: `semantic-scala`

Short description:

> Skills-only guidance for bounded Scala compiler, build, test, type, effect,
> symbol, reconciliation, and SemanticDB evidence through an independently
> installed semantic-scala CLI.

Long description:

> semantic-scala helps coding agents consult structured Scala compiler, build,
> test, type, effect, symbol, reconciliation, and SemanticDB evidence instead of
> relying only on source text. This package contains one skill and no MCP server
> or runtime. Claude Code can invoke an independently installed exact
> semantic-scala `0.1.0-alpha.3` CLI only through its ordinary shell permission
> flow. Plugin installation performs no automatic CLI download.

Repository: <https://github.com/DmytroMitin/semantic-scala-claude-plugin>

Support: <https://github.com/DmytroMitin/scala-semantic-harness/issues>

Privacy: <https://github.com/DmytroMitin/scala-semantic-harness/blob/main/PRIVACY.md>

License: Apache-2.0

## Existing submission boundary

The existing submission maps to the same repository, repository-root plugin
path, and default branch. The portal still shows only
`v0.1.0-alpha.3 · 05d4f0d`, failed with reviewer-requested changes. Settings
states that it is not scanning new commits and instructs the owner to select
`Resubmit for review` to check the latest commit.

No duplicate submission is appropriate. A later owner-authorized resubmission
must use the existing submission and must first verify that the portal resolves
`f28fe7a6` and `0.1.0-alpha.3.2`. Source publication alone does not establish
that portal validation has occurred.
