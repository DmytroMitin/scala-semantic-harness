# Technical data-handling companion to the privacy policy

This factual implementation note supplements the owner-approved public
[semantic-scala Privacy Policy](../../PRIVACY.md). The current plugin source is
the repository root of
<https://github.com/DmytroMitin/semantic-scala-claude-plugin> on `main`,
immutably qualified at commit
`05d4f0de35a02916504bf29156bc42270cf77a23`.

On 2026-09-28 the authenticated portal revalidated the existing exact draft at
`main @ 05d4f0d`, passed seven checks with one missing-icon warning, and saved
the owner-approved answers `Reads only`, `No`, `Not retained`, and `No`. The
owner approved all four compliance acknowledgements and exactly one review
submission was created. The security scan completed and sent the qualified
`05d4f0d` version to content-policy review because shipped code could not be
cleared automatically. The status is In review and no public listing exists.
Versions history retains an earlier `fc3a6c8` detection event; no duplicate
submission or corrective portal action was attempted. The private contact value is not retained in this repository.

## Local plugin behavior

`semantic-scala` is a local Claude Code plugin containing one agent skill and a
local stdio MCP server. The thin directory candidate launches a package-local
Python bootstrap, which starts a verified cached `semantic-scala` CLI and MCP
server against the workspace and paths explicitly supplied to a tool
invocation. The project does not operate a semantic-scala remote service, and
the local MCP server does not independently upload workspace data to one.

Depending on the selected tool, semantic-scala may read Scala source files,
explicit SemanticDB files, build metadata, classpath entries, compiler outputs,
and file metadata under the named workspace. Its results can include compiler
diagnostics, rendered types, symbols, source ranges, build/test outcomes, and
bounded provenance. Callers should avoid naming workspaces or artifacts that
contain information they do not intend Claude Code to process.

## Commands and side effects

Read-only syntax or existing-artifact queries can operate without a build.
`compile`, `errors`, `test`, and documented sbt-backed queries can load the
project build, resolve dependencies, execute build/plugin/test code, and write
ordinary build outputs or caches. The agent skill instructs clients to preserve
these boundaries and obtain approval for the relevant operation.

### Bootstrap download and runtime cache

The thin candidate requires Python 3.11 or newer and network access on first
start. It downloads only the fixed public Alpha-3 MCPB URL documented in the
plugin, with expected size 285,603,142 bytes and SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`.
The bootstrap deliberately adds only its fixed user-agent header to the
artifact request. It does not deliberately add workspace source, tool input,
semantic results, environment values, or secrets. Python's standard networking
may honor host proxy configuration, which can affect routing and can add proxy
authentication. GitHub and network intermediaries can observe normal request
metadata such as the source IP address and request headers.

After size and digest verification, the bootstrap safely extracts the MCPB and
atomically stores the runtime below
`$XDG_CACHE_HOME/semantic-scala/runtime/0.1.0-alpha.3/<sha256>` when the XDG root
is absolute, or below `~/.cache/semantic-scala/runtime/0.1.0-alpha.3/<sha256>`
otherwise. Cache directories are owner-only where supported. The marker stores
only runtime provenance and verified inventory facts; it stores no workspace
source, environment values, credentials, or tool results. The downloaded MCPB
and incomplete temporary directories are removed after success or failure.
Warm starts reuse the verified cache without a network fetch.

The CLI and MCP server emit results and diagnostics on their process streams;
semantic-scala does not create a persistent application log. Its sbt-backed
operations create request-owned temporary global settings, protocol, runtime,
and socket paths and remove them after the request. If sbt 2 presents a
classpath JAR only through its temporary content-addressed store,
semantic-scala can preserve those JAR bytes under the selected workspace's
`target/semantic-scala/sbt-materialized-classpath/v1` directory before removing
the request-owned temporary base.

The optional sbt classpath cache is semantic-scala-owned persistent state.
`fresh` neither reads nor writes it. Explicit `refresh` writes an atomically
replaced record, while explicit `reuse` reads and validates that record without
silently refreshing. The per-user roots are
`$XDG_CACHE_HOME/semantic-scala/sbt-classpath/v1` (or
`~/.cache/semantic-scala/sbt-classpath/v1` when an absolute XDG cache root is
unavailable) and the isolated sibling `v2` root for an explicitly selected Java
home. Records and locks are owner-only where supported. Records contain bounded
identity and validation metadata: digests, project/configuration identity,
timestamps, classpath paths, sizes, counts, kinds, and content hashes. They do
not store Scala source contents, environment values, secrets, or raw sbt logs.

Build tools and the packaged JVM may additionally create their normal
workspace, dependency-cache, log, or temporary files. Exact side effects depend
on the selected semantic tool and target build; the plugin does not promise a
side-effect-free sandbox.

## Claude product boundary

Using Claude Code may send conversation content, tool inputs, and tool results
to Anthropic according to the user's Claude product, account, organization, and
policy settings. That Claude product behavior is separate from the absence of a
semantic-scala-operated remote service. Refer to Anthropic's current policies
and the settings applicable to the user's account.

## Removal and support

Users can remove a marketplace installation with Claude Code's plugin uninstall
command and remove the marketplace separately if they added it themselves.
The downloaded runtime cache is not automatically deleted by plugin uninstall;
users can remove the versioned `semantic-scala/runtime` cache directory to
reclaim it, after which the next start performs a new verified download.
Project build outputs, semantic-scala's optional classpath-cache records,
materialized classpath JARs, and third-party caches created by invoked build
tools are also not automatically deleted. Support requests belong at
<https://github.com/DmytroMitin/scala-semantic-harness/issues>.
