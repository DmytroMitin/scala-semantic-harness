# semantic-scala embedded Claude plugin

This Linux x86_64 Claude plugin contains the canonical semantic-scala skill and
the complete local MCP server in semantic-scala.mcpb. Installation and first
start do not download a runtime, run an updater, or require host Java. The
embedded MCPB contains its own reduced Amazon Corretto 21 runtime and the exact
eight-tool semantic-scala Alpha-3 server.

Local MCP tools load in Claude Code and local Cowork. Regular Claude chat does
not load local MCP servers, although the plugin skill remains available there.
The runtime supports Linux x86_64 with compatible GNU libc and system zlib.

Semantic queries inspect paths supplied to them. Build-backed tools can execute
project build definitions, plugins, tests, and their transitive code and can
write normal build outputs or caches. Review the canonical skill's approval,
resource, and evidence boundaries before using those tools.

The plugin distribution version is 0.1.0-alpha.3.1; the embedded runtime
remains 0.1.0-alpha.3. The final numeric prerelease component records the
reviewer-requested packaging replacement without claiming a new runtime
release.

See the public
[semantic-scala Privacy Policy](https://github.com/DmytroMitin/scala-semantic-harness/blob/main/PRIVACY.md)
for local processing, Claude boundaries, storage, retention, sharing, removal,
and contact details.
