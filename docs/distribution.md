# Maven and Coursier distribution

## Current state

The modular Maven/Coursier primary runtime is implemented and published on
Maven Central at exact versions `0.1.0-alpha.2` and `0.1.0-alpha.3`. For Alpha
3, all eight public module POM/main/source/documentation artifacts, signatures,
and checksum sidecars were verified against the reviewed release bytes. The
public `main` two-application channel selects Alpha 3. Fresh outsider-like
JDK 21 install/runtime/update/uninstall through the actual public raw-GitHub
URL and Maven Central passed, together with commit-pinned reproduction.
Both exact Alpha 2 and Alpha 3 application routes are independently qualified.
For client-specific MCP and skill setup, start with
[`agent-onboarding.md`](agent-onboarding.md).
The source-only `0.1.0-alpha.1` tag and GitHub prerelease are unchanged. The
immutable `0.1.0-alpha.2` lightweight tag and GitHub prerelease identify the
source that reproduced all 32 Maven Central primaries byte-for-byte. The
current tree reports exact release-candidate version `0.1.0-alpha.3`; its
Central artifacts and actual public channel route are independently qualified,
but no Alpha 3 tag or GitHub Release exists. The
alpha-3 candidate source
build uses Scala 3.9.0 for the harness and its
linked Presentation Compiler. This does not alter the immutable alpha-2 Maven
or Coursier bytes, and it does not make live queries dynamically select a
target compiler.

The alpha-2 GitHub Release provides source identity, release notes, and normal
GitHub-generated source archives with zero uploaded project assets. Maven
Central remains the immutable channel for the signed implementation files.

A project-owned single-file Coursier URL channel is available from public
`main`. No Coursier contrib entry, MCPB package, MCP Registry entry, native
binary, container, npm/PyPI wrapper, Agent Plugin runtime distribution, or
skill distribution is claimed by this support result.

## Exact implementation graph

The owner-selected candidate group metadata is `com.github.dmytromitin`, the
historical Maven Central publisher identity already used for the owner's JVM
artifacts. [Current Sonatype namespace guidance](https://central.sonatype.org/register/namespace/)
distinguishes newly provisioned personal GitHub groups such as
`io.github.<username>` from existing OSSRH namespaces, which
[were migrated into the Central Publisher Portal](https://central.sonatype.org/pages/ossrh-eol/)
after OSSRH shut down.
The exact Alpha 2 and Alpha 3 releases under this group have been published on
Maven Central. Exactly these Scala 3 application implementation artifacts are
public for Alpha 3:

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

## URL channel and install shape

The exact Alpha 3 Maven version is public. `distribution/coursier/channel.json`
is the canonical deterministic public channel with exactly these two
applications:

```text
semantic-scala
semantic-scala-mcp
```

The baseline is JDK 21. The current supported Alpha 3 route uses public `main`;
the independently qualified Alpha 2 route remains at its immutable tag URL.
Install Coursier by following its
[authoritative installation guidance](https://get-coursier.io/docs/cli-installation),
then install only the CLI:

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

Some Coursier versions can print `No modified time in response` while checking
the URL channel. This reports missing remote `Last-Modified` metadata; by
itself it is not an application-update failure. Judge the operation by the
`cs update` exit status, then re-run `semantic-scala version` and a smoke call.
The missing timestamp may cause Coursier to refresh or re-download while
checking the descriptor; it does not change the success criteria.
Fresh Coursier 2.1.25-M26 qualification completed successfully and preserved
the exact alpha-2 CLI plus ordered eight-tool MCP behavior. The message was
source-verified for that Coursier version but was not emitted in the non-TTY
qualification run.

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

The checked-in channel names exact `0.1.0-alpha.3` dependencies. The immutable
[Alpha 2 tag-pinned channel](https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/0.1.0-alpha.2/distribution/coursier/channel.json)
retains exact `0.1.0-alpha.2`. Published Maven
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
3. optionally creates a temporary synthetic OpenPGP identity for a full local
   release-shape proof; `--primaries-only` performs no signing and validates
   only the 32 POM/main/source/documentation candidate files;
4. validates the exact GAV allowlist, internal dependency DAG, Central metadata,
   and absence of root/benchmark artifacts;
5. generates exact-version local descriptors and installs both applications
   through Coursier into an empty temporary install root and cache;
6. outside the checkout, verifies the exact CLI/MCP version, runs a bounded
   CLI `effect-summary`, initializes MCP without either CLI override, checks
   the ordered eight tools, and runs a bounded `semantic_effect_summary` call
   on a copied fixture;
7. checks launcher bytes for checkout/control coupling, performs Coursier
   update, repeats the installed smoke, uninstalls both commands, and confirms
   their launchers are gone; and
8. optionally writes sanitized manifests/reports to a new `--evidence-dir`,
   then deletes repositories, channel, cache, install root, fixture, logs, and
   any synthetic keyring through its temporary-root cleanup.

The proof derives exactly one non-SNAPSHOT version from the candidate
`build.sbt`; missing, ambiguous, moving, and SNAPSHOT versions fail closed.

The admitted local proof used Coursier 2.1.25-M25 and exact candidate version
`0.1.0-alpha.2`. It produced 32 primary files across eight modules and a
byte-identical two-build manifest digest of
`a9d1e1ff0aabb7596b82aff27036fe63b345838200b6665ba226b43eb5f92028`.
It also verified 32 detached test-only signatures and the complete 192-file
artifact/signature/checksum shape. Those hashes describe the locally frozen
candidate; a later public verification proved the published release bytes.

The exact source-release candidate was then rebuilt from the current public
tree under Amazon Corretto 21.0.11 and sbt 1.12.14. Its exact eight-module,
32-primary manifest retained the same SHA-256 and every file matched freshly
downloaded Maven Central bytes. Root and benchmark remained non-publishable,
the internal POM DAG remained byte-identical, and no legacy namespace appeared.

The Alpha 2 URL-channel preparation proof used JDK 21.0.12 and Coursier 2.1.25-M25.
A disposable loopback HTTP server served bytes identical to the canonical
channel while a fresh outside-checkout cache resolved only the channel URL and
public Maven Central. Initial install and update both passed exact CLI version,
no-override MCP initialization, ordered eight-tool registry, and bounded
`semantic_effect_summary` schema checks. Both applications uninstalled, no
checkout path appeared in launchers or cache, and all disposable state was
deleted.

The independent public qualification then fetched both live `main` and commit-
pinned channel URLs at SHA-256
`f5d3d638ee46107f11f0e288462440a6017d89bc667c910f1df63a44cdcaa453`.
Two separate empty Coursier caches resolved the channel only from
`raw.githubusercontent.com` and the application graph only from Maven Central.
The live-main lane passed exact CLI version, no-override ordered eight-tool MCP,
one bounded `semantic_effect_summary`, retained-channel update, repeated smoke,
and uninstall. The commit-pinned lane independently repeated install, CLI/MCP
runtime, read-only smoke, and uninstall. Neither lane required credentials,
local Maven state, a checkout launcher, or private/control material.

The independently qualified Alpha 3 public `main` channel has SHA-256
`4180d2ac9018ae4eb2a84a5096f4d52d5136458d11322c3e72c844e163fe03fc`.
Its [immutable commit-pinned channel](https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/92a5a40df0a8728e47125ec76bc4d481fcb736f9/distribution/coursier/channel.json)
returned identical bytes and independently passed installation of both apps,
exact versions, ordered eight-tool MCP, read-only smoke, and uninstall.
Both lanes used Corretto 21.0.11, Coursier 2.1.25-M26, separate empty caches and
install roots, and explicit `COURSIER_REPOSITORIES=central` isolation of
Coursier's defaults. Coursier fetched each actual raw-GitHub URL and the
application graph only from public Central. CLI/MCP reported exact
`0.1.0-alpha.3`; both effect-summary operations returned
`semantic-scala.effect-summary.v1` with non-empty evidence. No-build
point-evidence returned `semantic-scala.point-evidence-result.v2`,
`NotSelectedUnavailable`, and typed reconciliation `NotAttempted`.
The main lane updated through retained public-channel metadata, re-fetched the
public URL, repeated runtime smoke, and uninstalled both apps. Both states were
removed. No credentials, local repository, CLI override, checkout launcher,
or private path participated in the admitted qualification lanes.

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

The exact alpha-3 candidate isolates strict-v6 incremental
analysis in an unpublished, build-only Scala 2.13.18 worker. Its product-owned
worker JAR is embedded in the existing semantic-reconciliation artifact, but
its exact Zinc 1.12.1/JNA 5.14.0 dependency graph is not declared on normal
published-module runtime POMs or staged CLI/MCP process classpaths. A strict-v6
request resolves that 42-artifact, 38,241,533-byte graph cache-first from Maven
Central and launches one bounded JDK 21 child; other routes do not. A first
uncached use can increase the user's Coursier cache, so this design reduces the
normal distribution/classpath surface rather than total post-v6 disk use.
The alpha-3 candidate audit content-bound all 42 worker artifacts to the frozen
inventory and reviewed their POM metadata, inherited parent metadata, and
packaged legal files. Its flags were the dual-license JNA row, Apache-family
Log4j NOTICE/LICENSE/DEPENDENCIES files, and child POMs whose licenses are
inherited from reviewed parent POMs. No unresolved technical disposition or
project-bundled NOTICE action remained. The existing JNA Apache-2.0 owner
selection is retained as technical history, not legal clearance or publication
authority. No public GAV or Coursier application was added.

## Readiness boundary

`SUPPORTED_DISTRIBUTION_USABILITY = READY` for the exact
`0.1.0-alpha.2` immutable tag-pinned route and exact `0.1.0-alpha.3` public
`main` / commit-pinned route, using Maven Central and the project-owned Coursier
URL under JDK 21. This means the CLI and generic stdio MCP applications install,
run, update, and uninstall independently through the qualified release routes. It does not imply Coursier contrib or registry discovery, MCPB/MCP
Registry availability, native/container/npm/PyPI packaging, Agent Plugin or
skill adoption, a stable embeddable-library API, broad Scala compatibility,
semantic superiority, or 1.0 stability. Alpha 2 source/Maven byte coherence is
proven and published at its lightweight tag and GitHub prerelease. Alpha 3 has
no Git tag or GitHub Release yet; READY does not imply that release coherence.
The next release gate must reproduce all 32 published Alpha 3 Central primaries
byte-for-byte from the then-current source tree before tagging.
