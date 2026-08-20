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

No public project-owned Coursier channel, Coursier contrib entry, MCPB package,
MCP Registry entry, native binary, container, npm/PyPI wrapper, Agent Plugin
runtime distribution, or skill distribution is claimed by this work.

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

## Intended future install shape

The exact Maven version is now public. After the project-owned descriptors
have been made available through an admitted public channel, the intended
applications are:

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

The admitted local proof used Coursier 2.1.25-M25 and exact candidate version
`0.1.0-alpha.2`. It produced 32 primary files across eight modules and a
byte-identical two-build manifest digest of
`a9d1e1ff0aabb7596b82aff27036fe63b345838200b6665ba226b43eb5f92028`.
It also verified 32 detached test-only signatures and the complete 192-file
artifact/signature/checksum shape. Those hashes describe a locally frozen
candidate, not a published release.

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
proves implementation shape and cleanup behavior; it does not prove an
available public package, independent installation through the intended public
route, long-term support, discovery, or user adoption.
