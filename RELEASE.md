# Release and versioning policy

`scala-semantic-harness` is an experimental alpha licensed under Apache-2.0.
The current release-candidate work concerns source-repository availability,
not a supported binary, package-manager, marketplace, or client-installation
channel.

## Versioning

Future tagged releases are expected to use Semantic Versioning. Before 1.0,
minor releases may make breaking changes when the changelog and migration
notes identify them; patch releases should remain backward compatible within
the documented public contract.

Public CLI commands, flags, exit semantics, and JSON schemas are compatibility
surfaces. Schema identifiers and typed result states must not be silently
redefined. Breaking changes require an explicit version transition and
documentation.

The MCP registry is exactly eight tools. Adding, removing, or incompatibly
changing a tool is a compatibility change and requires an explicit release
decision, updated protocol tests, and documentation. The MCP adapter remains a
thin CLI-backed surface rather than an independent semantic authority.

## Release channels

A public source repository permits users to build the experimental project
themselves. It does not establish supported artifacts, dependency coordinates,
signed binaries, plugin-marketplace publication, compatibility guarantees for
generated Agent Plugin binaries, or long-term support. Each future binary or
package channel requires its own reproducible build, dependency-attribution,
installation, and runtime gates.

Release readiness remains separate from evidence that the harness improves an
agent decision or outperforms mature IDE/LSP tooling.
