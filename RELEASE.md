# Release and versioning policy

`scala-semantic-harness` is an experimental alpha licensed under Apache-2.0.
The published `0.1.0-alpha.1` release concerns source-repository availability.
The immutable `0.1.0-alpha.2` lightweight tag and GitHub prerelease establish
source identity for the exact application implementation artifacts published
on Maven Central and independently supported through the project-owned
Coursier URL route under JDK 21.

## Versioning

`0.1.0-alpha.1` is the first published source-only prerelease. Tagged releases
use Semantic Versioning. Released `0.1.0-alpha.2` identifies the exact source
that reproduced the 32 primary Maven Central files byte-for-byte. The current
tree reports exact release-candidate version `0.1.0-alpha.3`; its exact eight
modules are public on Maven Central and the checked-in channel selects them,
but public raw-URL qualification, a tag, and a GitHub Release remain absent.
Before 1.0, minor releases may make breaking changes when the changelog and migration
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

The published first-alpha channel is source-only: the `0.1.0-alpha.1` Git tag
and GitHub Release expose the repository source and GitHub-generated source
archives. They include no project-built CLI/MCP tree, JAR, binary archive,
generated Agent Plugin bundle, package-manager coordinate, container, or
marketplace upload.

Future binary and package channels still require separate reproducibility,
dependency-attribution and license, installation, and runtime gates.

The exact `0.1.0-alpha.3` candidate preserves the same eight application
implementation modules. Those modules are public on Maven Central, and the
checked-in Coursier channel selects Alpha 3 after this product change is
published. Its local channel-to-Central runtime proof does not establish the
later actual-public-URL qualification or authorize a Git tag, GitHub Release,
or announcement. Those transitions require separate gates and authority.

The `com.github.dmytromitin` exact-eight Maven/Coursier route implements those
gates, including explicit provisional-group rejection,
source/doc/signature/checksum shape, byte-identical builds, and disposable
install/update/uninstall. The immutable `0.1.0-alpha.2` lightweight tag and
GitHub prerelease identify the source that reproduces the alpha-2 Maven Central
primaries byte-for-byte. Technical review dispositioned all 16 unique flagged
runtime rows without establishing a bundled NOTICE action, and the owner
selected Apache-2.0 for resolver-fetched JNA 5.14.0. The immutable Alpha 2
channel at its release tag is independently qualified from fresh JDK 21 states
against Maven Central only. Exact CLI version, no-override MCP,
ordered eight tools, bounded read-only runtime, retained-channel update, and
uninstall passed. The checked-in Alpha 3 channel candidate has the same local
runtime gates but remains outside that READY claim until its actual public URL
is independently qualified. Published Central coordinates are immutable:
corrections roll forward to a new reviewed version and are never overwritten
or deleted.

The `0.1.0-alpha.2` GitHub Release provides source identity, release notes, and
the normal GitHub-generated source archives. It has zero uploaded project
assets: Maven Central remains the immutable distribution channel for the
signed JVM implementation artifacts.

Release readiness remains separate from evidence that the harness improves an
agent decision or outperforms mature IDE/LSP tooling.
