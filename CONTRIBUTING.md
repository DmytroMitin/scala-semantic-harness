# Contributing

`scala-semantic-harness` is an experimental Scala 3 semantic evidence layer.
Contributions should keep compiler, build, and test truth authoritative and
preserve the stated bounds and uncertainty of semantic results.

## Setup

Use JDK 21, sbt, Git, and Python 3. From a fresh clone, run:

```bash
python3 scripts/validate-public-contract.py
python3 benchmarks/validate-public-evidence.py
python3 scripts/tests/test_validate_public_contract.py
python3 scripts/tests/test_package_agent_plugin.py
sbt -batch test
sbt -batch "cli/stage; mcpServer/stage"
scripts/mcp/smoke-mcp-tools.py
```

The MCP registry must remain exactly the eight tools documented in the
README. A semantic query is bounded evidence, not a substitute for compilation
or tests.

## Public contract

Public documentation, examples, benchmark evidence, schemas, scripts, and the
canonical agent skill must stand alone. Do not add private controller
chronology, machine-specific paths, credentials, raw model reasoning, or a
dependency on non-public repositories. Run the public-contract and benchmark
validators before proposing a change.

Architecture or public-contract changes must update `README.md`, the relevant
file under `docs/`, and `ROADMAP.md` when outcomes or readiness change. Add
tests before complex integrations and keep modules and interfaces explicit.

## Contribution workflow

1. Create a focused branch from the current default branch.
2. Add or update tests that demonstrate the intended behavior.
3. Run the narrow relevant checks, then the full validation commands above.
4. Open a pull request that explains the user-visible contract, evidence,
   limits, and compatibility impact.

By submitting a contribution, you agree that it is provided under the
project's [Apache-2.0 license](LICENSE).
