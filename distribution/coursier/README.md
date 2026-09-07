# Coursier application channel

`channel.json` is the deterministic public URL-channel candidate for
`semantic-scala` and `semantic-scala-mcp`. It points only to the immutable
`com.github.dmytromitin:*:0.1.0-alpha.3` Maven Central artifacts. The `_3`
modules are application implementation artifacts, not a stable embeddable API.

Validate the checked release channel with:

```text
python3 scripts/distribution/coursier-channel.py validate-url \
  --version 0.1.0-alpha.3 \
  --channel distribution/coursier/channel.json
```

`generate-url --version <exact-version> --output <absent-file>` reproduces the
single-file shape and refuses to overwrite an existing file or follow a
symbolic-link output. The focused test suite compares fresh generation with
the checked bytes, so the release channel is not hand-maintained without a
drift gate.

`templates/` and the `generate` / `validate-generated` commands preserve the
directory-channel mode used by isolated local Maven proofs. Official Coursier
guidance treats directory channels as local/debugging surfaces; they are not
the public channel contract.

The runtime baseline is JDK 21 and Coursier is required. Target-workspace sbt
is separate: build-oracle commands such as `compile`, `errors`, and `test`
require it, while installation and syntax-first/read-only operations do not
inherently require target-workspace sbt.

Independent Alpha 3 installation from the actual public raw GitHub `main` URL
passed fresh JDK 21 runtime/update/uninstall checks and commit-pinned
reproduction. `SUPPORTED_DISTRIBUTION_USABILITY = READY` covers both exact
Alpha 2 and Alpha 3 application routes. Alpha 2 retains its immutable tag URL;
Alpha 3 now has an immutable lightweight tag and GitHub prerelease. Its
[tag-pinned channel](https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/0.1.0-alpha.3/distribution/coursier/channel.json)
has the same released bytes. Mutable source development is
`0.1.0-alpha.4-SNAPSHOT`; this channel remains pinned to Alpha 3 and does not
establish an Alpha 4 packaged route. See the
[distribution qualification](../../docs/distribution.md#readiness-boundary).
