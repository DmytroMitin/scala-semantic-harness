# Agent Plugin package

The repository can generate a self-contained directory containing the
canonical semantic-scala skill, staged CLI, and staged MCP server. The package
targets [Agent Plugins 1.0.0](https://agent-plugins.org/specification), whose
official status was `Working Draft` when this contract was checked on
2026-08-13. The [official author guide](https://agent-plugins.org/plugin-authors)
defines the fixed portable layout used here.

This is bounded packaging evidence, not a release, marketplace listing, or
claim that multiple clients already consume the same package.

## Build and validate

Run the package tests, stage both applications, and assemble into a fresh
output path:

```bash
python3 -m unittest scripts.tests.test_package_agent_plugin
sbt cli/stage mcpServer/stage
python3 scripts/package-agent-plugin.py assemble \
  --output target/agent-plugin/semantic-scala
python3 scripts/package-agent-plugin.py validate \
  --plugin-root target/agent-plugin/semantic-scala
```

The assembler refuses an existing output path. Remove only the generated path
or choose another fresh ignored path before rebuilding. Generated stage trees
under `target/` are not source artifacts and must not be committed.

The structural validator uses only the Python standard library. It checks the
frozen fields and schema identifiers, package containment, symlink absence,
launcher presence and executable modes, canonical-skill byte identity,
machine-specific text markers, and a deterministic path/size/mode/SHA-256
inventory. It reports `validationLevel: structural`; that label is deliberate.
It does not present its hand-written checks as general JSON Schema conformance.

The generated `plugin.json` and `mcp.json` have also been validated against the
official 1.0.0 schemas using an already installed local JSON Schema validator.
Runtime clients still select locally supported schemas; the package does not
fetch a schema while loading.

## Generated layout

```text
semantic-scala/
├── plugin.json
├── mcp.json
├── package-manifest.json
├── skills/
│   └── semantic-scala/
│       └── SKILL.md
├── cli/
│   ├── bin/
│   └── lib/
└── mcp/
    ├── bin/
    └── lib/
```

`skills/semantic-scala/SKILL.md` is copied byte-for-byte from the canonical
repository skill. The complete existing stage trees are copied so their
relative `bin`/`lib` launch contracts remain intact. The package manifest
describes all other package files without including itself in its content hash.

Root `mcp.json` configures one stdio server with the single executable token
`./mcp/bin/semantic-scala-mcp`. It passes the CLI as two arguments, `--cli` and
`${PLUGIN_ROOT}/cli/bin/semantic-scala`, and uses `${PLUGIN_ROOT}` as its
working directory. A conformant client supplies `PLUGIN_ROOT` and `PLUGIN_DATA`
and applies the specification's placeholder rules. Installation UI,
permissions, storage, and distribution remain client-owned.

## Relocated runtime smoke

For a disposable package-data directory, the helper interprets the portable
MCP configuration, initializes the relocated server, requires the exact eight
tools, and calls the read-only point-evidence composition through the bundled
CLI:

```bash
python3 scripts/package-agent-plugin.py smoke \
  --plugin-root '/tmp/relocated semantic scala' \
  --plugin-data '/tmp/relocated semantic scala data'
```

The smoke creates one fixed Scala source in the supplied package-data
directory and refuses to overwrite it. Use a fresh disposable data directory.
It does not approve or invoke arbitrary project builds.

The MCP surface remains exactly:

```text
semantic_compile
semantic_errors
semantic_test
semantic_effect_summary
semantic_symbol_at
semantic_symbols
semantic_reconcile_symbol
semantic_point_evidence
```

## Portable core and compatibility surfaces

The generated root `plugin.json`, `skills/`, and `mcp.json` are the Agent
Plugins portable core. The repository's `.agents` and `.claude` skill wrappers
remain thin client-specific compatibility surfaces and are not copied into the
package or made canonical.

The installed clients examined during packaging validation exposed native
skills, MCP, or client-specific plugin formats, but none documented and exposed
a safe nonpersistent route for loading the Agent Plugins 1.0 root-manifest
format. Consequently, no live agent-selection screen ran. Structural
validation and a direct protocol smoke do not establish client adoption or
cross-client portability.

The project source is licensed under Apache-2.0. No supported binary/package
version or client-installation channel is selected here; generated binary
redistribution remains a later, independently audited gate.
