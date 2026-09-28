# semantic-scala thin Claude directory candidate

This experimental Linux x86_64 plugin contains the canonical semantic-scala
skill and a readable Python bootstrap for the local exact-eight-tool MCP
server. It is a locally qualified candidate, not a public directory listing or
supported public install source.

The first MCP start requires Python 3.11 or later and HTTPS access to one fixed
project-owned GitHub Release URL. The bootstrap downloads the exact
`semantic-scala` `0.1.0-alpha.3` MCPB (285,603,142 bytes), requires SHA-256
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`,
checks its embedded manifest and inventory, safely extracts it, and atomically
installs it in a user-owned cache. It never selects another URL, auto-updates,
uses a package manager, or sends telemetry.

With an absolute `XDG_CACHE_HOME`, cached runtime files live beneath
`$XDG_CACHE_HOME/semantic-scala/runtime/0.1.0-alpha.3/`; otherwise they live
beneath `~/.cache/semantic-scala/runtime/0.1.0-alpha.3/`. Later starts reuse a
valid marker and required entrypoints without a network request. Startup does
not rehash the full extracted runtime, so the verified initial download proves
artifact identity at installation time but is not perpetual tamper protection
against the same local user.

To clear the downloaded runtime, remove only the applicable
`semantic-scala/runtime/0.1.0-alpha.3` directory under that cache root while no
semantic-scala process is running. Plugin uninstall does not remove this cache.

The extracted MCPB includes its own Java 21 runtime, so host Java is not
required. The runtime remains limited to Linux x86_64 with compatible GNU libc
and system zlib. Semantic tools operate on paths supplied to them; build-backed
tools can run project build, plugin, or test code and write normal outputs or
caches. Review the canonical skill's approval and evidence boundaries before
using those tools.

## Privacy

Read the public
[semantic-scala Privacy Policy](https://github.com/DmytroMitin/scala-semantic-harness/blob/main/PRIVACY.md)
for the local processing, first-start GitHub request, Claude boundary, storage,
retention, sharing, removal, and contact details.
