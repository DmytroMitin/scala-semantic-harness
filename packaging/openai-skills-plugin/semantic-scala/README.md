# semantic-scala OpenAI skills-only candidate

This portable plugin provides one deliberately narrow, read-only workflow for
Codex: inspect the declared effect-like return types in a local Scala source
file. It contains a skill, the canonical semantic-scala policy as an exact
reference, and a standard-library-only Python helper. It contains no MCP
configuration and starts no MCP process.

The package includes one original project-owned square SVG under `assets/`.
The owner approved it after 48-, 128-, and 512-pixel inspection, and both
`interface.logo` and `interface.composerIcon` reference the same mark.

On first approved use, the helper downloads the fixed semantic-scala Alpha-3
MCPB from GitHub Releases, verifies its exact 285,603,142-byte size and SHA-256,
safely extracts it into an owner-only versioned cache, and directly launches
`bin/semantic-scala effect-summary`. Warm use reuses the verified cache. The
download contains the published Linux x86_64 runtime, so no host Java is
required; Python 3.11+, compatible GNU libc, and system zlib are required.

The helper accepts only `status --json` and
`effect-summary --file <relative.scala> --json`. It does not forward arbitrary
arguments, run builds, or accept a configurable executable or download URL.

This source is a locally qualified preparation candidate, not an OpenAI public
listing. Current OpenAI guidance requires partner contact before submitting a
workflow whose core value depends on local execution, local file access, or
offline operation. No official actionable product-review route was discoverable
without owner-authenticated organization context, so eligibility remains
unknown. Ordinary Chat is not a supported execution surface for this Alpha-3
workflow.
