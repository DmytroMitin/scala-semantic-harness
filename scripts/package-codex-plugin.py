#!/usr/bin/env python3
"""Generate and verify the lightweight semantic-scala Codex plugin."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CANONICAL_SKILL = ROOT / "skills/semantic-scala/SKILL.md"
GENERATOR = Path(__file__).resolve()
PLUGIN_ROOT = Path("distribution/codex-plugin/semantic-scala")
MARKETPLACE_PATH = Path(".agents/plugins/marketplace.json")
VERSION = "0.1.0-alpha.3"
PRE_CHANGE_BASELINE = "5fdcdb11c4c3612bd56e94f49d953cde40718faa"
REPOSITORY = "https://github.com/DmytroMitin/scala-semantic-harness"
CHANNEL_URL = (
    "https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/"
    "0.1.0-alpha.3/distribution/coursier/channel.json"
)
PLUGIN_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
MCP_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
PROVENANCE_SCHEMA = "semantic-scala.codex-plugin-provenance.v1"
GENERATOR_FORMAT = "semantic-scala.codex-plugin.v1"
EXPECTED_TOOLS = [
    "semantic_compile",
    "semantic_errors",
    "semantic_test",
    "semantic_effect_summary",
    "semantic_symbol_at",
    "semantic_symbols",
    "semantic_reconcile_symbol",
    "semantic_point_evidence",
]
HOME_PATH = re.compile(r"/home/[^/\s\"']+")
STRONG_CREDENTIALS = (
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(
        r"(?i)(?:api[_-]?key|access[_-]?token|password|client[_-]?secret)"
        r"\s*[:=]\s*['\"]?[A-Za-z0-9+/_.-]{12,}"
    ),
)


class PackagingError(Exception):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def plugin_metadata() -> dict[str, Any]:
    return {
        "$schema": PLUGIN_SCHEMA,
        "name": "semantic-scala",
        "version": VERSION,
        "description": (
            "Bounded Scala compiler, build, test, type, effect, symbol, "
            "reconciliation, and SemanticDB evidence for coding agents."
        ),
        "author": {
            "name": "Dmytro Mitin",
            "url": "https://github.com/DmytroMitin",
        },
        "homepage": REPOSITORY + "#agent-quick-start",
        "repository": REPOSITORY,
        "license": "Apache-2.0",
        "keywords": [
            "scala",
            "semanticdb",
            "compiler",
            "build-tools",
            "model-context-protocol",
            "agent-skill",
        ],
    }


def marketplace() -> dict[str, Any]:
    return {
        "name": "scala-semantic-harness",
        "interface": {"displayName": "scala-semantic-harness"},
        "plugins": [
            {
                "name": "semantic-scala",
                "source": {
                    "source": "local",
                    "path": "./distribution/codex-plugin/semantic-scala",
                },
                "policy": {
                    "installation": "AVAILABLE",
                    "authentication": "ON_INSTALL",
                },
                "category": "Developer Tools",
            }
        ],
    }


def overlay() -> dict[str, Any]:
    value = {key: item for key, item in plugin_metadata().items() if key != "$schema"}
    value["skills"] = "./skills/"
    value["interface"] = {
        "displayName": "semantic-scala",
        "shortDescription": "Bounded Scala semantic evidence for coding agents.",
        "longDescription": plugin_metadata()["description"],
        "developerName": "Dmytro Mitin",
        "category": "Developer Tools",
        "capabilities": ["Read", "Write"],
        "defaultPrompt": ["Use semantic-scala for bounded Scala semantic evidence."],
    }
    return value


def mcp_config() -> dict[str, Any]:
    return {
        "$schema": MCP_SCHEMA,
        "mcpServers": {
            "semantic-scala": {
                "type": "stdio",
                "command": "semantic-scala-mcp",
                "args": [],
            }
        },
    }


def provenance(skill: bytes) -> dict[str, Any]:
    return {
        "$schema": PROVENANCE_SCHEMA,
        "pluginVersion": VERSION,
        "runtimeVersion": VERSION,
        "runtimeCoursierChannel": CHANNEL_URL,
        "canonicalSkill": {
            "path": "skills/semantic-scala/SKILL.md",
            "sha256": sha256_bytes(skill),
        },
        "generator": {
            "format": GENERATOR_FORMAT,
            "path": "scripts/package-codex-plugin.py",
            "sha256": sha256_bytes(GENERATOR.read_bytes()),
        },
        "mcpToolContract": {
            "count": len(EXPECTED_TOOLS),
            "orderedNames": EXPECTED_TOOLS,
            "source": "modules/mcp-server/src/main/scala/semantic/harness/mcp/SemanticScalaMcpServer.scala",
        },
        "source": {
            "repository": REPOSITORY,
            "identityKind": "pre-change-generator-baseline",
            "preChangeBaselineCommit": PRE_CHANGE_BASELINE,
            "note": (
                "This is the clean source baseline used to add the generator, not the future "
                "publication commit that will contain this generated file."
            ),
        },
    }


README = f"""# semantic-scala for Codex

This lightweight plugin registers the separately installed exact `{VERSION}`
`semantic-scala-mcp` application and packages the canonical semantic-scala
agent skill. It contains no Java runtime, server JAR, installer, updater, or
hidden download.

## Prerequisites

Install JDK 21 and Coursier, then explicitly install the exact released
applications from the immutable Alpha-3 channel:

```bash
cs install --default-channels=false \\
  --channel {CHANNEL_URL} \\
  semantic-scala semantic-scala-mcp
semantic-scala version
```

The version command must report `{VERSION}`, and `semantic-scala-mcp` must be
visible on the `PATH` inherited by Codex. Desktop GUI PATH inheritance is not
yet qualified.

## Install from the Git marketplace

The lightweight marketplace is published in the public product repository at
the immutable Task 278 source commit below. This Codex source pin is separate
from the Alpha-3 runtime tag used above:

```bash
codex plugin marketplace add DmytroMitin/scala-semantic-harness \\
  --ref 7577f9e429505926eab8728626836ea1cd7ec083
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
""".encode("utf-8")


def expected_files() -> dict[Path, bytes]:
    skill = CANONICAL_SKILL.read_bytes()
    return {
        MARKETPLACE_PATH: json_bytes(marketplace()),
        PLUGIN_ROOT / "plugin.json": json_bytes(plugin_metadata()),
        PLUGIN_ROOT / ".codex-plugin/plugin.json": json_bytes(overlay()),
        PLUGIN_ROOT / "mcp.json": json_bytes(mcp_config()),
        PLUGIN_ROOT / "README.md": README,
        PLUGIN_ROOT / "provenance.json": json_bytes(provenance(skill)),
        PLUGIN_ROOT / "skills/semantic-scala/SKILL.md": skill,
    }


def scoped_files(root: Path) -> set[Path]:
    result: set[Path] = set()
    for directory in (root / ".agents/plugins", root / PLUGIN_ROOT):
        if not directory.exists():
            continue
        if directory.is_symlink() or not directory.is_dir():
            raise PackagingError(f"generated path is not a regular directory: {directory}")
        for path in directory.rglob("*"):
            if path.is_symlink():
                raise PackagingError(f"generated output contains a symbolic link: {path}")
            mode = path.stat(follow_symlinks=False).st_mode
            if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
                raise PackagingError(f"generated output contains a special file: {path}")
            if path.is_file():
                result.add(path.relative_to(root))
    return result


def scan_text(relative: Path, data: bytes) -> None:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PackagingError(f"generated file is not UTF-8 text: {relative}") from error
    if "scala-semantic-harness-control" in text or HOME_PATH.search(text):
        raise PackagingError(f"machine-private path or controller marker in generated file: {relative}")
    for pattern in STRONG_CREDENTIALS:
        if pattern.search(text):
            raise PackagingError(f"credential marker in generated file: {relative}")


def validate_contract(root: Path) -> None:
    plugin_root = root / PLUGIN_ROOT
    plugin = json.loads((plugin_root / "plugin.json").read_text(encoding="utf-8"))
    if plugin.get("$schema") != PLUGIN_SCHEMA or plugin.get("version") != VERSION:
        raise PackagingError("portable plugin schema or version differs from the contract")
    compatibility = json.loads(
        (plugin_root / ".codex-plugin/plugin.json").read_text(encoding="utf-8")
    )
    if compatibility.get("skills") != "./skills/" or any(
        key in compatibility for key in ("mcp", "mcpServers")
    ):
        raise PackagingError("compatibility overlay must not duplicate the MCP declaration")
    if json.loads((plugin_root / "mcp.json").read_text(encoding="utf-8")) != mcp_config():
        raise PackagingError("portable MCP declaration differs from the local stdio contract")
    if json.loads((root / MARKETPLACE_PATH).read_text(encoding="utf-8")) != marketplace():
        raise PackagingError("marketplace declaration differs from the repo-local contract")
    actual_provenance = json.loads(
        (plugin_root / "provenance.json").read_text(encoding="utf-8")
    )
    if actual_provenance != provenance(CANONICAL_SKILL.read_bytes()):
        raise PackagingError("provenance differs from the deterministic source contract")


def check(root: Path) -> None:
    expected = expected_files()
    actual = scoped_files(root)
    expected_paths = set(expected)
    for unexpected in sorted(actual - expected_paths, key=lambda path: path.as_posix()):
        raise PackagingError(f"unexpected generated file: {unexpected.as_posix()}")
    for missing in sorted(expected_paths - actual, key=lambda path: path.as_posix()):
        raise PackagingError(f"missing generated file: {missing.as_posix()}")
    for relative, wanted in expected.items():
        path = root / relative
        mode = stat.S_IMODE(path.stat(follow_symlinks=False).st_mode)
        if not mode & stat.S_IRUSR or mode & 0o111:
            raise PackagingError(
                "generated file must be owner-readable and non-executable "
                f"({mode:04o}): {relative.as_posix()}"
            )
        actual_bytes = path.read_bytes()
        scan_text(relative, actual_bytes)
        if actual_bytes != wanted:
            raise PackagingError(f"stale generated file: {relative.as_posix()}")
    validate_contract(root)


def assemble(output: Path) -> None:
    if output.exists() or output.is_symlink():
        raise PackagingError(f"output path already exists; use a fresh path: {output}")
    for relative, data in expected_files().items():
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(0o644)
    check(output)


def generate(root: Path) -> None:
    unexpected = scoped_files(root) - set(expected_files())
    if unexpected:
        first = min(unexpected, key=lambda path: path.as_posix())
        raise PackagingError(f"unexpected generated file: {first.as_posix()}")
    for relative, data in expected_files().items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(0o644)
    check(root)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    assemble_parser = commands.add_parser("assemble", help="assemble into a fresh root")
    assemble_parser.add_argument("--output", required=True, type=Path)
    generate_parser = commands.add_parser("generate", help="regenerate checked output")
    generate_parser.add_argument("--root", type=Path, default=ROOT)
    check_parser = commands.add_parser("check", help="verify checked output is current")
    check_parser.add_argument("--root", type=Path, default=ROOT)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "assemble":
            assemble(arguments.output.resolve())
        elif arguments.command == "generate":
            generate(arguments.root.resolve())
        else:
            check(arguments.root.resolve())
        return 0
    except (OSError, json.JSONDecodeError, PackagingError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
