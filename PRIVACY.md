# semantic-scala Privacy Policy

Effective date: 28 September 2026

## 1. Scope

This Privacy Policy describes how the `semantic-scala` open-source project,
including its command-line tools, local Model Context Protocol (MCP) server,
agent skill, Claude Code plugin, and first-start bootstrap, handles information.
It applies to the project software maintained at
<https://github.com/DmytroMitin/scala-semantic-harness>. It does not replace the
privacy policies of Claude, GitHub, build tools, or other software and services
you choose to use with semantic-scala.

## 2. Operator and support

semantic-scala is maintained by Dmytro Mitin as an open-source project. The
project does not operate a hosted semantic-analysis service or require a
project-operated user account. Support and privacy questions can be raised
through the public issue tracker at
<https://github.com/DmytroMitin/scala-semantic-harness/issues>. Do not include
secrets or personal information that you do not want to make public in an
issue.

## 3. Information processed locally

semantic-scala processes information on the machine where it runs. Depending
on the command or MCP tool selected, that information may include:

- Scala source files and other files at paths explicitly supplied to the tool;
- SemanticDB files, build definitions, dependency and classpath information,
  compiler outputs, file metadata, and project structure;
- compiler diagnostics, rendered types, symbols, source ranges, test and build
  results, and bounded provenance information; and
- process arguments and configuration needed to perform the requested
  operation.

Project files, paths, diagnostics, or build output can incidentally contain
names, email addresses, credentials, or other personal or sensitive
information. semantic-scala is not designed to identify or collect that
information as a separate purpose, but it may process it locally when it is
present in the inputs selected by the user or calling client. Users should not
select workspaces or artifacts containing information they do not intend the
local tool and their Claude client to process.

## 4. First-start GitHub download

The thin Claude Code plugin requires network access on first start. Its
bootstrap downloads one fixed public Alpha-3 MCPB runtime from this project's
GitHub Release:

<https://github.com/DmytroMitin/scala-semantic-harness/releases/download/0.1.0-alpha.3/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb>

The request contains a fixed semantic-scala user-agent string and ordinary
HTTPS request metadata. The bootstrap does not deliberately add workspace
files, source code, tool inputs, semantic results, environment values, or
secrets to that request. GitHub and network intermediaries may receive request
metadata such as an IP address, timestamps, headers, and proxy information.
Python's networking can honor user- or system-configured proxies, which may
affect routing and authentication. GitHub handles information under the
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

After a valid runtime has been installed, normal warm starts reuse the local
cache and do not make this download request.

## 5. No semantic-scala backend, telemetry, cookies, or account

The project does not operate a remote semantic-scala backend. The local CLI,
MCP server, skill, and bootstrap do not provide project-operated analytics or
telemetry, do not set cookies, and do not require a semantic-scala account. The
CLI and MCP server return results and diagnostics over local process streams;
semantic-scala does not create its own persistent application log. A host
application, terminal, operating system, proxy, build tool, or other service
may separately retain activity under its own settings and policies.

## 6. Claude and Anthropic

When semantic-scala is used through Claude or Claude Code, the Claude product
may send conversation content, tool inputs, and tool results to Anthropic.
That processing is controlled by the user's Claude product, account,
organization, and settings, not by a semantic-scala-operated service. See
[Anthropic's Privacy Policy](https://www.anthropic.com/legal/privacy) and the
terms and settings applicable to the user's account.

## 7. Build tools and other third parties

Some semantic-scala operations can launch a user-selected local build, plugin,
compiler, or test process. Those processes may execute project code, resolve
dependencies, access repositories or services configured by the user, and
write their normal outputs, caches, logs, or temporary files. Their exact data
handling depends on the target project and the user's configuration.

semantic-scala does not control third-party build tools, dependency
repositories, proxies, operating systems, Claude clients, or other integrations.
Users are responsible for reviewing the configuration, permissions, and
privacy terms of those third parties before invoking them.

## 8. Local storage, retention, and removal

The first-start bootstrap stores the verified runtime below
`$XDG_CACHE_HOME/semantic-scala/runtime/0.1.0-alpha.3/<sha256>` when
`XDG_CACHE_HOME` is an absolute path, or below
`~/.cache/semantic-scala/runtime/0.1.0-alpha.3/<sha256>` otherwise. Its marker
contains runtime provenance and inventory facts, not workspace source,
credentials, environment values, or tool results. The downloaded MCPB and
incomplete installation directories are removed after success or failure.

Explicit semantic-scala classpath-cache modes may store bounded project,
classpath, digest, timestamp, size, count, kind, and validation metadata below
`$XDG_CACHE_HOME/semantic-scala/sbt-classpath/v1` (or `v2` for an explicitly
selected Java home), falling back to the corresponding path under
`~/.cache`. These records do not store Scala source contents, environment
values, secrets, or raw build logs. Some sbt 2 operations may preserve exact
classpath JAR bytes below the selected workspace's
`target/semantic-scala/sbt-materialized-classpath/v1` directory. Build tools
may retain additional data in their own locations.

Local data remains until the user or the relevant build or host tool removes
it. Uninstalling the plugin does not automatically remove runtime or build
caches. While no semantic-scala process is running, users can remove the
applicable versioned `semantic-scala/runtime` directory, optional
`semantic-scala/sbt-classpath` directories, and workspace
`target/semantic-scala` outputs. The next operation may recreate needed data or
download the verified runtime again.

## 9. Sharing and disclosure

semantic-scala does not send workspace content or semantic results to a
project-operated service and does not sell personal information. Information
may nevertheless be disclosed to:

- Anthropic when a Claude client transmits prompts, tool inputs, or tool
  results;
- GitHub and network intermediaries for the fixed first-start runtime request;
- build tools, dependency repositories, proxies, or other services configured
  or invoked by the user; and
- other people when the user chooses to share logs, reports, issues, or other
  output.

These disclosures are initiated through the user's chosen client, command, or
environment and are governed by the applicable third party's terms and privacy
policy.

## 10. Security controls

The thin bootstrap uses HTTPS, accepts only HTTPS redirects, pins the expected
runtime byte count and SHA-256 digest, verifies the package manifest and file
inventory, rejects unsafe archive entries, and installs through owner-only
temporary directories and an atomic rename where the platform supports those
permissions. The runtime URL cannot be changed through an environment
variable. semantic-scala also validates bounded inputs and reports the side
effects and uncertainty of semantic operations. These controls reduce risk but
do not guarantee that local systems, third-party services, or user-selected
projects are secure.

## 11. Children

semantic-scala is a developer tool and is not intended for users under 18. The
project does not knowingly collect children's personal information through a
project-operated backend because it does not operate such a backend.

## 12. Changes to this policy

This policy may be updated when semantic-scala's behavior, distribution model,
or applicable requirements change. Changes will be published in the project
repository, with the effective date above updated when appropriate. The Git
history provides prior versions.

## 13. Contact

For privacy or security questions about semantic-scala, open an issue at
<https://github.com/DmytroMitin/scala-semantic-harness/issues>. Do not post
credentials, private source code, or other sensitive personal information in a
public issue.
