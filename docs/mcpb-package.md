# MCPB package candidate

## Status and boundary

semantic-scala has a locally validated MCPB candidate for exact
`0.1.0-alpha.3` on **Linux x86_64**. It is not a release asset and is not an
official MCP Registry listing. The planned immutable URL in
[`server.alpha3.candidate.json`](../distribution/mcp-registry/server.alpha3.candidate.json)
does not exist until a separately authorized publication attaches the asset.

The MCPB v0.3 manifest can declare `linux` but has no architecture field. The
asset name, this document, and the Registry candidate therefore carry the
x86_64 boundary explicitly. Do not infer macOS, Windows, ARM, or multi-arch
support. The qualified Corretto runtime dynamically uses the GNU/Linux loader,
GNU libc family, and system `libz.so.1`. The candidate therefore requires a
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
host. That operating-system ABI is part of the supported candidate boundary,
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
ZIP writer is used for candidate bytes. The result remains readable by the
official CLI.

## Required qualification before publication

For every future asset build:

1. build twice from clean exact-tag inputs and require byte-identical MCPB
   archives;
2. validate `manifest.json` with the current official MCPB CLI;
3. unpack the archive into a relocated path, including a path with spaces;
4. run initialization, exact-eight tool listing, and a safe read-only semantic
   call with host Java absent from `PATH`;
5. inspect the packaged Java executable's dynamic dependencies and retain an
   explicit libc/system-library compatibility boundary;
6. verify no helper process remains and no external project was modified;
7. update the Registry candidate with the exact archive SHA-256 and validate it
   with the current `mcp-publisher`; and
8. publish only under explicit release and Registry authority, then verify the
   immutable remote asset before submitting the Registry record.

Attaching an asset to the existing `0.1.0-alpha.3` prerelease is the preferred
future identity because the package contains that exact release. Creating a
new tag solely for the packaging format would split one runtime version across
source identities. Either choice remains a release mutation and is outside the
local candidate workflow.
