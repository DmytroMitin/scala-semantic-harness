# Maven and Coursier distribution candidate

## Current state

The modular Maven/Coursier primary runtime candidate is implemented and locally
validated. It is not externally published or a supported package channel.
The supported and externally verified route remains a JDK 21 source build.
The source-only `0.1.0-alpha.1` tag and GitHub prerelease are unchanged;
current `main` remains development version `0.1.0-alpha.2-SNAPSHOT`.

No Maven Central namespace, Sonatype deployment, public Coursier channel,
Coursier contrib entry, MCPB package, MCP Registry entry, native binary,
container, npm/PyPI wrapper, Agent Plugin runtime distribution, or skill
distribution is claimed by this work.

## Exact implementation graph

The candidate group metadata is `io.github.dmytromitin`, pending any separately
authorized external namespace verification. Exactly these Scala 3 application
implementation artifacts are publishable:

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

These coordinates package application implementation. They do not define a
supported embeddable-library API or promise binary compatibility among the
internal modules.

## Intended future install shape

After an exact version has been separately released to Maven Central and the
project-owned descriptors have been made available through an admitted public
channel, the intended applications are:

```text
semantic-scala
semantic-scala-mcp
```

JDK 21 and Coursier are prerequisites for this route. `semantic-scala-mcp`
delegates to the installed `semantic-scala` command. With no `--cli` or
`SEMANTIC_SCALA_CLI` override, it performs a bounded PATH search and accepts
only a regular executable without following symbolic links. Explicit CLI and
environment overrides retain precedence and receive the same early validation.
Reported tool commands use the sanitized `semantic-scala ...` form.

Target-workspace sbt remains a separate prerequisite for build-oracle commands
such as `compile`, `errors`, and `test`. Installation and syntax-first/read-only
operations do not inherently require target-workspace sbt.

Coursier update is intended to use `cs update semantic-scala
semantic-scala-mcp`; uninstall uses `cs uninstall semantic-scala
semantic-scala-mcp`. Immutable published coordinates must never be overwritten
or deleted. A correction rolls forward to a newly reviewed version.

## Maintained local gates

`distribution/coursier/templates/` contains exact-version application
descriptor source for the two commands. The generator rejects snapshots,
moving version selectors, descriptor drift, extra applications, and nonempty
output directories. A local repository can be injected without editing the
templates; Maven Central remains the explicit fallback for third-party
dependencies.

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

The admitted local proof used Coursier 2.1.25-M25. It produced 32
primary files across eight modules and a byte-identical two-build manifest
digest of
`c9b94da5303e63418a7396ac568f9bd1fc042dbd30e7ef4ba0b55fef2c470a82`.
Those hashes describe a disposable local version, not a published release.

## Dependency and attribution review

The proof inventories resolver-fetched runtime JARs with GAV, SHA-256, runtime
relevance, declared POM relationships/scopes, POM-declared licenses, and
packaged NOTICE names. Resolver-fetched bytes are not project-redistributed
bundle contents, but that distinction does not eliminate license, NOTICE, or
attribution obligations and is not a legal conclusion.

The admitted local inventory contained 57 runtime components. Automated review
flagged six EPL-family records, one multiple-license record, nine
missing/ambiguous POM-license records, and zero packaged NOTICE-name hits.
These are human prepublication review items. Before any Central release, a
reviewer must resolve each flag against authoritative upstream license and
NOTICE sources, decide required attribution, confirm the exact release graph,
and record approval. The automated gate must not be weakened to infer legal
clearance.

## Readiness boundary

`SUPPORTED_DISTRIBUTION_USABILITY` remains `NOT_ASSESSED`. Local installation
proves implementation shape and cleanup behavior; it does not prove an
available public package, independent installation through the intended public
route, long-term support, discovery, or user adoption.
