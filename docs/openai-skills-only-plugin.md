# OpenAI skills-only plugin candidate

semantic-scala maintains a separate OpenAI skills-only candidate under
[`packaging/openai-skills-plugin/`](../packaging/openai-skills-plugin/). It does
not replace the local native OpenAI/Codex package and it is not a public
`With MCP` submission.

## Supported value

The first public version is intentionally narrow. On a compatible local Codex
environment it can inspect one workspace-relative Scala file and return the
declared effect wrapper for a method through `effect-summary`. The result is
structured semantic-scala JSON. The package does not expose compilation,
tests, arbitrary semantic-scala arguments, arbitrary shell commands, or MCP
tools.

This boundary keeps the listing aligned with demonstrated behavior. The full
canonical semantic-scala policy remains the source of selection and
interpretation guidance, but the skills-only execution preamble explicitly
limits tool use to the packaged helper's single allowlisted query.

## Package layout

The generated candidate contains:

```text
semantic-scala/
├── plugin.json
├── .codex-plugin/plugin.json
├── README.md
├── package-manifest.json
└── skills/semantic-scala/
    ├── SKILL.md
    ├── references/semantic-scala-policy.md
    └── scripts/semantic_scala_cli.py
```

The compatibility manifest exists because current local Codex plugin tooling
requires it for installed-client qualification. Neither manifest declares an
MCP server, and the package contains no `mcp.json` or `.mcp.json`.

Generate, validate, and pack the candidate with explicit output paths:

```bash
python3 scripts/package-openai-skills-plugin.py assemble \
  --output target/openai-skills-only/semantic-scala
python3 scripts/package-openai-skills-plugin.py validate \
  --plugin-root target/openai-skills-only/semantic-scala
python3 scripts/package-openai-skills-plugin.py pack \
  --plugin-root target/openai-skills-only/semantic-scala \
  --output target/openai-skills-only/semantic-scala-0.1.0-alpha.3.zip
```

The generator copies the canonical
[`skills/semantic-scala/SKILL.md`](../skills/semantic-scala/SKILL.md) byte for
byte into `references/semantic-scala-policy.md`, validates the two manifests,
rejects forbidden MCP files, and writes a deterministic ZIP.

## Direct CLI bootstrap

The helper requires Python 3.11 or newer and currently supports Linux x86_64
with a compatible GNU C library and system zlib. On first semantic use it makes
one HTTPS request to the fixed Alpha-3 GitHub Release asset, accepts HTTPS
redirects only, and verifies both the exact 285,603,142-byte size and pinned
SHA-256 before extraction. It rejects unsafe archive paths, unsafe file modes,
duplicate or conflicting entries, and unexpected inventory. Installation uses
owner-only temporary paths and an atomic rename.

The retained cache is versioned below
`$XDG_CACHE_HOME/semantic-scala/openai-skills-runtime` when that variable is an
absolute path, or below `~/.cache/semantic-scala/openai-skills-runtime`
otherwise. Warm use verifies the marker and required direct-CLI files before
reuse. The helper starts `bin/semantic-scala` directly and never starts
`semantic-scala-mcp`. It has no configurable download URL, telemetry, silent
updates, credential access, or shell evaluation.

Example package-local invocation:

```bash
python3 skills/semantic-scala/scripts/semantic_scala_cli.py \
  effect-summary --file path/inside/workspace/Fixture.scala --json
```

The file must already exist, use a relative path within the current workspace,
and end in `.scala`.

## Qualified surface and limitations

Codex CLI 0.157.1 passed a disposable local-marketplace installation. The
client discovered the installed skill, read its canonical policy reference,
invoked the package-contained helper, received structured evidence for
`Fixture.value: Option[Int]`, and interpreted the result correctly. Separate
cold and warm executions showed immutable Alpha-3 acquisition, cache-only
reuse with networking directed to a closed proxy, unchanged fixture bytes, and
no MCP process.

This evidence qualifies a local Codex CLI workflow. Codex desktop is expected
to work only where its local execution environment provides the same workspace,
Python, platform, and outbound first-use access; it was not separately tested.
Ordinary ChatGPT chat does not provide this local workspace/helper execution
path. ChatGPT Work remains unqualified pending the product-specific OpenAI
determination described below. A universal directory listing would therefore
not imply universal execution support.

## Submission boundary

The candidate is locally validated and unsubmitted. Current first-party OpenAI
guidance says to contact OpenAI before submission when the core experience
requires local execution, local-file access, offline operation, hardware or
application access, or inbound messages. semantic-scala's useful workflow uses
the first three, so that product-specific determination is mandatory before a
Platform draft.

The owner-approved policy amendment in the prepared product diff covers the
OpenAI/Codex client path, first-use GitHub download, and separate local cache.
The GitHub privacy URL will reflect it only after separately authorized
repository publication. The remaining human prerequisites are:

- original production-quality `interface.logo` and `interface.composerIcon`
  assets;
- a verified individual or business developer identity;
- Apps Management write access in the owning Platform organization;
- country or region selections and current policy attestations; and
- the OpenAI product-specific determination for this local execution model.

Current submission-error guidance marks website, support, privacy, and terms
URLs optional for skills-only ZIP uploads. Apache-2.0 is the software license;
no separate terms document is currently asserted as a skills-only requirement.
Final-directory validation requires both branding fields to reference readable
square PNG, JPG, JPEG, WebP, or SVG files no larger than 5 MiB. Raster images
must be from 48×48 through 4,096×4,096 pixels; SVG dimensions must be square,
numeric, and at least 48. One original compliant square asset may be considered
for both fields, subject to final visual and portal review.
The prepared metadata, three starter prompts, five positive tests, and three
negative tests live under
[`distribution/openai-skills-only/`](../distribution/openai-skills-only/).

No OpenAI portal login, draft, upload, attestation, identity-verification,
review submission, publication, payment, or remote service occurred during
this preparation.
