# MCPB package

## Status and boundary

semantic-scala publishes an MCPB for exact `0.1.0-alpha.3` on **Linux
x86_64**. The byte-qualified bundle is attached to the existing Alpha-3
prerelease and its exact record is active in the official MCP Registry under
`io.github.DmytroMitin/semantic-scala`. The maintained publication record is
[`server.alpha3.json`](../distribution/mcp-registry/server.alpha3.json); the
adjacent candidate file is retained as historical prepublication input.

The public asset is
[`semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb`](https://github.com/DmytroMitin/scala-semantic-harness/releases/download/0.1.0-alpha.3/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb),
285,603,142 bytes with SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`.
The unchanged release notes predate this attachment and still contain a stale
statement that uploaded project assets are zero. The live release asset list,
digest-pinned publication record, and this document describe the current state.

The MCPB v0.3 manifest can declare `linux` but has no architecture field. The
asset name, this document, and the publication record therefore carry the
x86_64 boundary explicitly. Do not infer macOS, Windows, ARM, or multi-arch
support. The qualified Corretto runtime dynamically uses the GNU/Linux loader,
GNU libc family, and system `libz.so.1`. The package therefore requires a
compatible GNU-libc environment and system zlib; it requires no host Java but
is not claimed for every Linux libc or distribution.

## Execution model

The package contains:

- the exact Alpha-3 staged CLI and exact-eight-tool MCP server libraries;
- an Amazon Corretto 21 runtime produced with all standard JDK modules;
- static Linux x86_64 launchers for `semantic-scala` and
  `semantic-scala-mcp`;
- the semantic-scala Apache-2.0 license and Corretto runtime notices; and
- a per-file SHA-256, size, and mode inventory.

The launchers resolve their own package root through `/proc/self/exe` and
invoke only `runtime/bin/java`. They do not search `PATH` for Java. The MCP
server receives the packaged CLI path explicitly. Server and CLI remain
ordinary host processes, so they retain the supported access to a user's local
Scala workspace and build tools. Operations that invoke a project build still
require that project's build tool and configured toolchain; the package does
not replace them with a container-only environment.

The two entry-point launchers are static. The package-local Java executable is
not static: direct dependency inspection in the admitted environment resolved
`libz.so.1`, the GNU libc family, and `/lib64/ld-linux-x86-64.so.2` from the
host. That operating-system ABI is part of the supported package boundary,
not an undeclared host-Java dependency.

## Reproducible local build

Use a clean disposable checkout or archive of tag `0.1.0-alpha.3` (commit
`075a60bfb7d7677d7fdfcc2369c9ffe41c8b32a8`) and a maintained JDK 21:

```bash
sbt -batch cli/stage mcpServer/stage

python3 scripts/package-mcpb.py assemble \
  --cli-stage modules/cli/target/stage \
  --mcp-stage modules/mcp-server/target/stage \
  --jdk-home "$JAVA_HOME" \
  --output target/mcpb/semantic-scala-package

python3 scripts/package-mcpb.py pack \
  --package-root target/mcpb/semantic-scala-package \
  --output target/mcpb/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb
```

The assembler verifies the staged main classes and ordered classpaths, rejects
symlinks, materializes only `jlink`'s package-internal legal-notice links,
normalizes modes, and records a payload inventory. It uses
`jlink --add-modules ALL-MODULE-PATH` rather than claiming an unproved minimal
module closure. The deterministic packer sorts paths and normalizes ZIP
timestamps and modes.

The official MCPB 2.1.2 CLI is still authoritative for v0.3 manifest
validation and bundle inspection. Its `pack` command was not byte-deterministic
in the local two-run qualification, so the repository's small deterministic
ZIP writer is used for the published bytes. The result remains readable by the
official CLI.

## Qualification and publication evidence

The published asset completed these gates:

1. build twice from clean exact-tag inputs and require byte-identical MCPB
   archives;
2. validate `manifest.json` with the current official MCPB CLI;
3. unpack the archive into a relocated path, including a path with spaces;
4. run initialization, exact-eight tool listing, and a safe read-only semantic
   call with host Java absent from `PATH`;
5. inspect the packaged Java executable's dynamic dependencies and retain an
   explicit libc/system-library compatibility boundary;
6. verify no helper process remains and no external project was modified;
7. validate the final Registry record and exact archive SHA-256 with current
   `mcp-publisher`;
8. upload once to the existing exact `0.1.0-alpha.3` prerelease and anonymously
   read back byte-identical content; and
9. publish once to the official Registry, then verify the active exact public
   record through its anonymous API.

The release title/body, tag target, draft/prerelease state, Maven Central
artifacts, and Coursier channel were not changed. A later package or platform
requires its own exact build, compatibility qualification, release authority,
and immutable record rather than widening this bundle's claims.
