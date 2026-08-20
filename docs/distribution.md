# Maven and Coursier distribution candidate

## Current state

The modular Maven/Coursier primary runtime candidate is implemented, locally
validated, and published on Maven Central at exact version `0.1.0-alpha.2`.
All eight public module POM/main/source/documentation artifacts, signatures,
and checksum sidecars were verified against the reviewed release bytes. This
does not yet establish a supported application package channel.
The supported and externally verified route remains a JDK 21 source build.
The source-only `0.1.0-alpha.1` tag and GitHub prerelease are unchanged;
current `main` remains development version `0.1.0-alpha.2-SNAPSHOT`.

A project-owned single-file Coursier URL-channel candidate is checked in and
locally qualified, but it is not available from public `main` until these
repository changes are published. No independent public-URL install, Coursier
contrib entry, MCPB package, MCP Registry entry, native binary, container,
npm/PyPI wrapper, Agent Plugin runtime distribution, or skill distribution is
claimed by this work.

## Exact implementation graph

The owner-selected candidate group metadata is `com.github.dmytromitin`, the
historical Maven Central publisher identity already used for the owner's JVM
artifacts. [Current Sonatype namespace guidance](https://central.sonatype.org/register/namespace/)
distinguishes newly provisioned personal GitHub groups such as
`io.github.<username>` from existing OSSRH namespaces, which
[were migrated into the Central Publisher Portal](https://central.sonatype.org/pages/ossrh-eol/)
after OSSRH shut down.
The exact candidate under this group has been published on Maven Central.
Exactly these Scala 3 application implementation artifacts are public:

```text
semantic-harness-core_3
semantic-harness-sbt-runner_3
semantic-harness-semanticdb-reader_3
semantic-harness-presentation-compiler_3
semantic-harness-semantic-reconciliation_3
semantic-harness-fp-analyzers_3
semantic-scala-cli_3
semantic-harness-mcp-server_3
```

The root aggregate and benchmark are explicitly non-publishable. Each module
has Maven-style POM metadata, Apache-2.0 license metadata, homepage, SCM,
developer, description, source JAR, and documentation JAR configuration. The
build uses sbt 1.12.14 and sbt-ci-release 1.11.2, which provides the maintained
release/signing architecture without affecting credential-free developer
builds. No legacy OSSRH/Nexus staging endpoint is configured.

The local candidate validator rejects any `io.github.dmytromitin` Maven
subtree, so a provisional coordinate cannot coexist with the selected group.

These coordinates package application implementation. They do not define a
supported embeddable-library API or promise binary compatibility among the
internal modules.

## Prepared URL channel and install shape

The exact Maven version is public. `distribution/coursier/channel.json` is the
canonical deterministic URL channel with exactly these two applications:

```text
semantic-scala
semantic-scala-mcp
```

The baseline is JDK 21. Install Coursier by following its
[authoritative installation guidance](https://get-coursier.io/docs/cli-installation),
then, once this channel file is present on public `main`, install only the CLI:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json \
  semantic-scala
```

Or install the CLI and MCP server together:

```bash
cs install --default-channels=false \
  --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json \
  semantic-scala semantic-scala-mcp
```

This syntax follows Coursier's
[URL-channel contract](https://get-coursier.io/docs/cli-appdescriptors): a
single JSON object maps application names to descriptors, and the raw URL is
passed with `--channel`. Disabling default channels isolates application-name
attribution to this project-owned file.

`semantic-scala-mcp` is the generic stdio MCP command. Start it with the target
workspace as the process working directory. It delegates to the installed
`semantic-scala` command. With no `--cli` or
`SEMANTIC_SCALA_CLI` override, it performs a bounded PATH search and accepts
only a regular executable without following symbolic links. Explicit CLI and
environment overrides retain precedence and receive the same early validation.
Reported tool commands use the sanitized `semantic-scala ...` form.

Target-workspace sbt remains a separate prerequisite for build-oracle commands
such as `compile`, `errors`, and `test`. Installation and syntax-first/read-only
operations do not inherently require target-workspace sbt.

Update both installed applications with:

```bash
cs update semantic-scala semantic-scala-mcp
```

Uninstall both with:

```bash
cs uninstall semantic-scala semantic-scala-mcp
```

The `main` URL may advance to a later separately reviewed release. For an
auditable historical channel, replace `main` with the full commit SHA that
published the desired bytes:

```text
https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/<published-commit-sha>/distribution/coursier/channel.json
```

The alpha channel names exact `0.1.0-alpha.2` dependencies. Published Maven
coordinates are immutable and must never be overwritten or deleted; a
correction rolls forward to a newly reviewed version.

## Maintained local gates

`distribution/coursier/channel.json` is generated and validated by
`scripts/distribution/coursier-channel.py`. Its URL-channel mode requires the
exact two keys and exact Central-only descriptors, rejects unknown fields,
moving or wrong versions, wrong namespaces/artifacts/main classes, extra or
missing applications, non-Central repositories, and unsafe output reuse. The
focused suite reproduces and byte-compares the checked file as a drift gate.

`distribution/coursier/templates/` and the directory generator remain only for
isolated local Maven proofs. A local repository can be injected into that
debugging shape without editing templates; it is not the public URL-channel
contract.

`scripts/distribution/prove-local-maven-coursier.sh` is a fail-closed disposable
proof. Given a clean source tree and JDK 21, it:

1. tests the product and publishes only the eight modules to a temporary Maven
   repository;
2. publishes the same primary artifacts from a second independent clean build
   and requires byte-identical POM, main, source, and documentation artifacts;
3. creates a temporary synthetic OpenPGP identity, verifies local detached
   signatures and SHA-256/SHA-512 sidecars, and deletes all key material;
4. validates the exact GAV allowlist, internal dependency DAG, Central metadata,
   and absence of root/benchmark artifacts;
5. generates exact-version local descriptors and installs both applications
   through Coursier into an empty temporary install root and cache;
6. outside the checkout, verifies the exact CLI/MCP version, initializes MCP
   without either CLI override, checks the ordered eight tools, and runs a
   bounded `semantic_effect_summary` call on a copied fixture;
7. checks launcher bytes for checkout/control coupling, performs Coursier
   update, repeats the installed smoke, uninstalls both commands, and confirms
   their launchers are gone; and
8. deletes repositories, channel, cache, install root, fixture, reports, logs,
   and synthetic keyring through its temporary-root cleanup.

The admitted local proof used Coursier 2.1.25-M25 and exact candidate version
`0.1.0-alpha.2`. It produced 32 primary files across eight modules and a
byte-identical two-build manifest digest of
`a9d1e1ff0aabb7596b82aff27036fe63b345838200b6665ba226b43eb5f92028`.
It also verified 32 detached test-only signatures and the complete 192-file
artifact/signature/checksum shape. Those hashes describe the locally frozen
candidate; a later public verification proved the published release bytes.

The URL-channel preparation proof used JDK 21.0.12 and Coursier 2.1.25-M25.
A disposable loopback HTTP server served bytes identical to the canonical
channel while a fresh outside-checkout cache resolved only the channel URL and
public Maven Central. Initial install and update both passed exact CLI version,
no-override MCP initialization, ordered eight-tool registry, and bounded
`semantic_effect_summary` schema checks. Both applications uninstalled, no
checkout path appeared in launchers or cache, and all disposable state was
deleted. This qualifies the soon-to-be-public bytes, not the unavailable public
GitHub URL.

## Dependency and attribution review

The proof inventories resolver-fetched runtime JARs with GAV, SHA-256, runtime
relevance, declared POM relationships/scopes, POM-declared licenses, and
packaged NOTICE names. Resolver-fetched bytes are not project-redistributed
bundle contents, but that distinction does not eliminate license, NOTICE, or
attribution obligations and is not a legal conclusion.

The admitted local inventory contained 57 runtime components. Automated review
flagged six EPL-family records, one multiple-license record, nine
missing/ambiguous POM-license records, and zero packaged NOTICE-name hits.
Technical review against authoritative upstream sources dispositioned all 16
unique flagged component rows without requiring project-bundled NOTICE text in
the current resolver-fetched packaging model. For JNA 5.14.0, upstream offers
Apache-2.0 or LGPL-2.1-or-later and the project owner selected Apache-2.0. The
review is technical evidence, not legal advice; the automated gate must not be
weakened to infer legal clearance or publication authority.

## Readiness boundary

`SUPPORTED_DISTRIBUTION_USABILITY` remains `NOT_ASSESSED`. Local installation
through a loopback-served copy proves the checked channel semantics and cleanup
behavior. It does not prove independent installation from the actually public
project URL, long-term support, discovery, or user adoption.
