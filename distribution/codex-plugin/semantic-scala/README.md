# semantic-scala for Codex

This lightweight plugin registers the separately installed exact `0.1.0-alpha.3`
`semantic-scala-mcp` application and packages the canonical semantic-scala
agent skill. It contains no Java runtime, server JAR, installer, updater, or
hidden download.

## Prerequisites

Install JDK 21 and Coursier, then explicitly install the exact released
applications from the immutable Alpha-3 channel:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/0.1.0-alpha.3/distribution/coursier/channel.json \
  semantic-scala semantic-scala-mcp
semantic-scala version
```

The version command must report `0.1.0-alpha.3`, and `semantic-scala-mcp` must be
visible on the `PATH` inherited by Codex. Desktop GUI PATH inheritance is not
yet qualified.

## Install from the Git marketplace

PREPUBLICATION: this marketplace is not yet available from a reviewed public
commit. After the reviewed product commit is published, replace the placeholder
below with that immutable full commit SHA:

```bash
codex plugin marketplace add DmytroMitin/scala-semantic-harness \
  --ref <IMMUTABLE_REVIEWED_PRODUCT_COMMIT>
codex plugin add semantic-scala@scala-semantic-harness
codex plugin list
codex mcp list
```

In a trusted Scala workspace, make one read-only `semantic_effect_summary`
query against a single known file before authorizing broader work. The
`semantic_compile`, `semantic_errors`, and `semantic_test` tools may run project
build, plugin, or test code, resolve dependencies, and write project outputs or
caches. Installing or registering the plugin does not grant permission to run
untrusted builds.

## Remove

```bash
codex plugin remove semantic-scala@scala-semantic-harness
codex plugin marketplace remove scala-semantic-harness
```

These commands remove the Codex plugin and marketplace separately. They do not
remove the independently installed Coursier applications or caches; use an
explicit `cs uninstall semantic-scala semantic-scala-mcp` only when that is
also intended.

Qualification is limited to the Linux Codex CLI path actually tested. This
package does not claim ChatGPT cloud use, universal Plugins Directory listing,
or qualified macOS, Windows, ARM, musl, or Desktop execution.
