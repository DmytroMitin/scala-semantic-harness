# Technical data-handling companion to the privacy policy

This implementation note supplements the owner-approved public
[semantic-scala Privacy Policy](../../PRIVACY.md). It describes plugin version
`0.1.0-alpha.3.2`, public at commit
`f28fe7a61ff449c9775d554d5bc9a11e2a9cfda1` in
<https://github.com/DmytroMitin/semantic-scala-claude-plugin>.

The existing Directory submission remains `Needs changes` and still resolves
rejected commit `05d4f0d`. No resubmission, new submission, or listing occurred
when the skills-only source was published.

## Skills-only package behavior

The Directory package contains instructions only: plugin metadata, one skill,
the canonical semantic-scala policy reference, documentation, license, and a
deterministic inventory. It declares and bundles no MCP server, CLI runtime,
executable, hook, LSP server, installer, updater, bootstrap, runtime download,
or pre-approved tool.

Installing the plugin does not install, download, or execute semantic-scala and
does not create a semantic-scala runtime cache. The plugin has no automatic
network behavior during installation or use.

Claude Code may invoke an independently installed exact `semantic-scala`
`0.1.0-alpha.3` CLI through its ordinary shell tool and permission flow. If the
command is absent, the skill instructs Claude to report the missing prerequisite
rather than install software, download a runtime, or use an MCP fallback.

## CLI inputs and outputs

When a user permits a CLI invocation, semantic-scala may read Scala source
files, explicit SemanticDB files, build metadata, classpath entries, compiler
outputs, and file metadata under the named workspace. Results can include
compiler diagnostics, rendered types, symbols, source ranges, build/test
outcomes, and bounded provenance. Callers should not name workspaces or
artifacts they do not intend Claude Code to process.

Read-only syntax or existing-artifact queries can operate without a build.
`compile`, `errors`, `test`, and documented sbt-backed queries can load the
project build, resolve dependencies, execute build/plugin/test code, and write
ordinary build outputs or caches. The skill discloses these boundaries;
installing the plugin grants no permission for them.

The CLI emits results and diagnostics on its process streams and does not create
a persistent application log. Sbt-backed operations can create request-owned
temporary paths and normal workspace, dependency-cache, log, or build output.
Optional semantic-scala classpath caches and materialized classpath artifacts
remain governed by the main product documentation and privacy policy. Exact
side effects depend on the selected command and target build.

## Network and third-party boundary

The plugin itself operates no semantic-scala remote service and does not upload
workspace data to one. Installing the CLI separately through Coursier, or later
resolving project dependencies in a build-backed command, can use the network
under those tools' own commands and configuration. Those actions are separate
from plugin installation and require the user's ordinary tool permission.

Using Claude Code may send conversation content, tool inputs, and tool results
to Anthropic according to the user's Claude product, account, organization, and
policy settings. That Claude product behavior is separate from the absence of a
semantic-scala-operated remote service. Refer to Anthropic's current policies
and the settings applicable to the account.

## Removal and support

Removing the plugin removes its skill package. Because this package installs no
runtime and creates no package-owned runtime cache, there is no plugin runtime
cache to remove. A separately installed semantic-scala CLI, project build
outputs, optional semantic-scala classpath-cache records, materialized classpath
artifacts, and third-party build caches are separate and are not deleted by
plugin removal.

Support requests belong at
<https://github.com/DmytroMitin/scala-semantic-harness/issues>.
