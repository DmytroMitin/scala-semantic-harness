# Technical data-handling note for owner review

This is a factual technical note, not a legal privacy policy or approved terms.
It is prepared in case the post-login Claude community submission flow asks for
data-handling information.

## Local plugin behavior

`semantic-scala` is a local Claude Code plugin containing one agent skill and a
local stdio MCP server. The server launches the packaged `semantic-scala` CLI
against the workspace and paths explicitly supplied to a tool invocation. The
project does not operate a semantic-scala remote service, and the local MCP
server does not independently upload workspace data to one.

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
Project build outputs, semantic-scala's optional classpath-cache records or
materialized classpath JARs, and third-party caches created by invoked build
tools are not automatically deleted by plugin uninstall. Support requests belong at
<https://github.com/DmytroMitin/scala-semantic-harness/issues>.
