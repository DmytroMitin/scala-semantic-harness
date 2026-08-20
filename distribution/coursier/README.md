# Coursier application channel

`channel.json` is the deterministic public URL-channel candidate for
`semantic-scala` and `semantic-scala-mcp`. It points only to the immutable
`com.github.dmytromitin:*:0.1.0-alpha.2` Maven Central artifacts. The `_3`
modules are application implementation artifacts, not a stable embeddable API.

Validate the checked release channel with:

```text
python3 scripts/distribution/coursier-channel.py validate-url \
  --version 0.1.0-alpha.2 \
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

Independent installation from the actual public raw GitHub URL remains a
separate qualification gate. Preparing and locally serving these exact bytes
does not make `SUPPORTED_DISTRIBUTION_USABILITY` ready.
