# Release and versioning policy

`scala-semantic-harness` is an experimental alpha licensed under Apache-2.0.
The published `0.1.0-alpha.1` release concerns source-repository availability,
while exact `0.1.0-alpha.2` application implementation artifacts are published
on Maven Central and independently supported through the project-owned
Coursier URL route under JDK 21.

## Versioning

`0.1.0-alpha.1` is the first published source-only prerelease. Tagged releases
use Semantic Versioning. The current source candidate reports exact
`0.1.0-alpha.2` after reproducing the 32 primary Maven Central files
byte-for-byte. No `0.1.0-alpha.2` Git tag or GitHub Release exists yet. Mutable
post-release development heads use a distinct `*-SNAPSHOT` identity. Before
1.0, minor releases may make breaking changes when the changelog and migration
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

The `com.github.dmytromitin` exact-eight Maven/Coursier route implements those
gates, including explicit provisional-group rejection,
source/doc/signature/checksum shape, byte-identical builds, and disposable
install/update/uninstall. The exact `0.1.0-alpha.2` source candidate reproduces
the immutable `0.1.0-alpha.2` Maven Central primaries byte-for-byte. No
`0.1.0-alpha.2` Git tag or GitHub Release has been created. Technical review
dispositioned all 16 unique flagged runtime rows without establishing a
bundled NOTICE action, and the owner selected Apache-2.0 for resolver-fetched
JNA 5.14.0. The deterministic project-owned Coursier URL channel is public and
independently qualified from both live-main and commit-pinned URLs in fresh JDK
21 states against Maven Central only. Exact CLI version, no-override MCP,
ordered eight tools, bounded read-only runtime, retained-channel update, and
uninstall passed. This does not create or imply an `0.1.0-alpha.2` Git tag or
GitHub Release. Published Central coordinates are immutable: corrections roll
forward to a new reviewed version and are never overwritten or deleted.

If separately authorized, the `0.1.0-alpha.2` GitHub Release should provide
source identity, release notes, and the normal GitHub-generated source
archives. It should not duplicate the Maven implementation artifacts as
uploaded binary assets; Maven Central remains their immutable distribution
channel.

Release readiness remains separate from evidence that the harness improves an
agent decision or outperforms mature IDE/LSP tooling.
