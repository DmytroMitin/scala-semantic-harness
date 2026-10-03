# Claude embedded-MCPB local candidate

## Status

The Claude Directory reviewer rejected the published thin plugin because it
downloads and executes the MCP server from a GitHub Release after installation.
The candidate documented here removes that behavior: it embeds the complete
local `semantic-scala.mcpb` and has no runtime downloader or updater.

The local engineering treatment passes, but this is not a compliant public
Directory source. The current
[plugin pre-submission checklist](https://claude.com/docs/plugins/pre-submission-checklist)
requires a GitHub archive under 50 MiB and every plugin file under 5 MiB. The
candidate ZIP is 144,399,347 bytes and its embedded MCPB is 145,197,017 bytes,
so Directory validation would stop before review. Do not publish or resubmit
this candidate without a materially different architecture or an explicit
reviewer-approved source mechanism.

The plugin identity is `0.1.0-alpha.3.1`; the embedded semantic-scala runtime
remains `0.1.0-alpha.3`. Neither identity is a public release or portal version.

## Current Claude contracts

First-party Claude documentation was rechecked on 3 October 2026:

- package-relative `.mcpb` syntax is valid and Claude Code can extract and run
  the local bundle;
- local MCP servers load in Claude Code and local Cowork but not regular
  Claude chat;
- the claude.ai organization upload API permits nested MCPB files and allows
  request body and expanded upload up to 200 MB;
- public Directory plugin bundles come from GitHub and use stricter validation:
  repository archive under 50 MiB, unpacked repository under 256 MiB, fewer
  than 10,000 files/folders, and every plugin file under 5 MiB;
- a local `.mcpb` is held for reviewer inspection; it is not exempt from the
  repository and per-file gates; and
- standalone MCPB/Desktop Extension Directory listings are deprecated and no
  longer accepted for local MCP submission.

The 200 MB organization-upload contract does not override the public Directory
GitHub-source limits.

## Size reduction and local qualification

The published Alpha-3 MCPB is unchanged at 285,603,142 bytes with SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`.
Its expanded payload duplicates nearly the full CLI and MCP classpaths and uses
an all-modules Java image.

The replacement assembler:

- stores identical staged classpath entries once by content digest while
  preserving each application classpath order;
- retains every distinct application class and dependency JAR byte;
- builds the Java image from the modules reported by `jdeps` for both
  applications, plus EC cryptography and ZIP filesystem support; and
- retains the static launchers, package-local Amazon Corretto 21.0.11 runtime,
  notices, exact-eight manifest, and payload inventory.

Because the Java module set changed, the candidate was exercised across all
eight public MCP tools. It remains Linux x86_64 only and requires compatible
GNU libc and system zlib; it does not require host Java.

## Candidate identity

Deterministic compact MCPB:

- 145,197,017 archive bytes;
- 181,937,037 expanded bytes;
- 1,952 files; and
- SHA-256 `6c1c698eaa73b78b9d005cbba4fccffd2e357e721e7f64fbeccdb13bb723d4ed`.

Deterministic six-file plugin ZIP:

- 144,399,347 archive bytes;
- 145,233,144 expanded bytes; and
- SHA-256 `9004bfa94a3e60618430092bd253ac6354aa64290c3ebad41c2a8f3a6999f2d6`.

Three full build comparisons were byte-identical. Official MCPB validation,
Claude plugin validation, clean relocation, archive safety checks, the full
exact-eight compatibility suite, and one isolated installed-client
`semantic_effect_summary` call passed. The call returned
`Fixture.value: Option[Int]` with effect category `option`, and fixture bytes
were unchanged. These results establish a local no-download package, not
Directory eligibility.

## Build and validate locally

Use Amazon Corretto 21.0.11 or another separately qualified JDK 21:

    sbt -batch cli/stage mcpServer/stage

    python3 scripts/package-mcpb.py assemble \
      --cli-stage modules/cli/target/stage \
      --mcp-stage modules/mcp-server/target/stage \
      --jdk-home /path/to/jdk-21 \
      --output target/claude-reviewed/semantic-scala-mcpb

    python3 scripts/package-mcpb.py pack \
      --package-root target/claude-reviewed/semantic-scala-mcpb \
      --output target/claude-reviewed/semantic-scala.mcpb

    python3 scripts/package-claude-embedded-plugin.py assemble \
      --mcpb target/claude-reviewed/semantic-scala.mcpb \
      --output target/claude-reviewed/semantic-scala

    python3 scripts/package-claude-embedded-plugin.py pack \
      --plugin-root target/claude-reviewed/semantic-scala \
      --output target/claude-reviewed/semantic-scala.zip

Keep this route local/private unless the Directory blocker is resolved. The
live distribution repository and existing Needs changes submission remain
untouched.
