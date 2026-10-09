# semantic-scala skills-only Claude Directory candidate

This is the public Claude Directory candidate for users who already installed
the `semantic-scala` command independently. The plugin contains guidance only:
one skill and its canonical policy reference. It does not bundle or install the
CLI, declare an MCP server or another runtime, add hooks, pre-approve tools, or
download or execute anything during plugin installation.

## Prerequisite

This plugin distribution is version `0.1.0-alpha.3.2`. That packaging version
does not denote a new semantic-scala runtime release: the required CLI remains
exact `0.1.0-alpha.3`.

Install `semantic-scala` separately before using this plugin. The documented
Coursier channel remains the primary public CLI distribution route:

```text
cs install --default-channels=false --channel https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/main/distribution/coursier/channel.json semantic-scala
```

The skill itself never runs that command, updates the CLI, downloads a runtime,
or switches to an MCP server. It first asks the existing CLI for its version.
If the command is absent or is not exact `0.1.0-alpha.3`, it stops with
prerequisite guidance; no later-version compatibility is claimed by this
candidate.

## Capability and side-effect boundary

The intentionally supported core is the CLI equivalent of the exact eight MCP
capabilities: compile, errors, test, effect-summary, symbol-at, symbols,
reconcile-symbol, and point-evidence. The canonical policy also documents
CLI-only commands. Those commands are available under the same bounded-policy
rules, but Task 268 client qualification covers effect-summary and compile.

`effect-summary` and reads of already-present inputs can be read-only.
`compile`, `errors`, `test`, and other build-backed modes can execute project,
plugin, or test code; resolve dependencies; and write normal outputs or caches.
Claude must use its ordinary shell permission flow for each command. Installing
this plugin grants no shell or build permission.

## Prerequisite matrix

| Independently installed CLI | Separately configured MCP | Result |
| --- | --- | --- |
| Present | Absent | Use the CLI through ordinary shell permissions. |
| Present | Present | This skills-only workflow still uses the CLI; it does not depend on or call MCP. |
| Absent | Present | Report the missing CLI prerequisite; do not substitute MCP. |
| Absent | Absent | Stop with clean independent-install guidance. |

## Claude surfaces

- Claude Code is the primary qualified surface because it can use an existing
  local CLI through the user's normal shell permission flow.
- Cowork can load the skill, but local CLI execution is supported only when the
  particular local desktop/session environment exposes the independently
  installed command. This package does not assume or create that environment.
- Claude chat can load the skill but has no established access to a CLI on the
  user's machine. It must not claim local semantic evidence.

The skills-only Directory package is distinct from the separately maintained
manual/local full MCP package. Coursier remains the runtime-bearing CLI route.

## Privacy

The plugin itself has no telemetry, credentials, automatic network traffic, or
retained state. Invoked CLI and project-build behavior follows the public
[semantic-scala Privacy Policy](https://github.com/DmytroMitin/scala-semantic-harness/blob/main/PRIVACY.md)
and the canonical policy included with this package.
