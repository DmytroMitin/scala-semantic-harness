#!/usr/bin/env python3
"""Assemble and structurally validate the semantic-scala Agent Plugin."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import stat
import subprocess
import sys
import tempfile
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE_ROOT = ROOT / "packaging/agent-plugin/semantic-scala"
CANONICAL_SKILL = ROOT / "skills/semantic-scala/SKILL.md"
PLUGIN_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
MCP_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
MANIFEST_SCHEMA = "semantic-scala.agent-plugin-package-manifest.v1"
EXPECTED_PLUGIN_FIELDS = {"$schema", "name", "description", "repository"}
EXPECTED_MCP_FIELDS = {"$schema", "mcpServers"}
EXPECTED_SERVER_FIELDS = {"type", "command", "args", "cwd"}
EXPECTED_COMMAND = "./mcp/bin/semantic-scala-mcp"
EXPECTED_ARGS = ["--cli", "${PLUGIN_ROOT}/cli/bin/semantic-scala"]
EXPECTED_CWD = "${PLUGIN_ROOT}"
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
TEXT_SUFFIXES = {"", ".json", ".md", ".sh", ".txt", ".properties"}
FORBIDDEN_TEXT = (
    ROOT.name + "-" + "control",
    str(ROOT),
)
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


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackagingError(f"invalid JSON at {path}: {error}") from error
    if not isinstance(value, dict):
        raise PackagingError(f"JSON root must be an object: {path}")
    return value


def is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def require_contained_regular_file(root: Path, relative: str, executable: bool = False) -> Path:
    candidate = root / relative
    try:
        resolved_root = root.resolve(strict=True)
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise PackagingError(f"required package path is missing: {relative}") from error
    if not is_relative_to(resolved, resolved_root):
        raise PackagingError(f"package path escapes plugin root: {relative}")
    if candidate.is_symlink() or not candidate.is_file():
        raise PackagingError(f"package path is not a regular non-symlink file: {relative}")
    if executable and not os.access(candidate, os.X_OK):
        raise PackagingError(f"package launcher is not executable: {relative}")
    return candidate


def validate_source_tree(source: Path, label: str) -> None:
    if source.is_symlink() or not source.is_dir():
        raise PackagingError(f"{label} stage root must be a non-symlink directory: {source}")
    for entry in sorted(source.rglob("*"), key=lambda path: path.as_posix()):
        if entry.is_symlink():
            raise PackagingError(f"{label} stage contains a symlink: {entry}")
        mode = entry.stat(follow_symlinks=False).st_mode
        if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise PackagingError(f"{label} stage contains a special file: {entry}")


def copy_tree(source: Path, destination: Path) -> None:
    validate_source_tree(source, destination.name)
    destination.mkdir(parents=True)
    for entry in sorted(source.rglob("*"), key=lambda path: path.as_posix()):
        relative = entry.relative_to(source)
        target = destination / relative
        if entry.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(entry, target)
            target.chmod(stat.S_IMODE(entry.stat(follow_symlinks=False).st_mode))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def file_inventory(plugin_root: Path) -> list[dict[str, Any]]:
    inventory: list[dict[str, Any]] = []
    for path in sorted(plugin_root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_dir() or path.name == "package-manifest.json":
            continue
        relative = path.relative_to(plugin_root).as_posix()
        mode = stat.S_IMODE(path.stat(follow_symlinks=False).st_mode)
        inventory.append(
            {
                "path": relative,
                "bytes": path.stat(follow_symlinks=False).st_size,
                "mode": f"{mode:04o}",
                "sha256": sha256(path),
            }
        )
    return inventory


def content_hash(files: list[dict[str, Any]]) -> str:
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def write_package_manifest(plugin_root: Path) -> dict[str, Any]:
    files = file_inventory(plugin_root)
    manifest = {
        "$schema": MANIFEST_SCHEMA,
        "agentPluginsVersion": "1.0.0",
        "validationLevel": "structural",
        "fileCount": len(files),
        "totalBytes": sum(item["bytes"] for item in files),
        "contentSha256": content_hash(files),
        "files": files,
    }
    (plugin_root / "package-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def scan_generated_text(plugin_root: Path) -> None:
    for path in sorted(plugin_root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_dir() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for marker in FORBIDDEN_TEXT:
            if marker and marker in text:
                raise PackagingError(
                    f"machine-specific or private path marker in generated text: "
                    f"{path.relative_to(plugin_root)}"
                )
        if HOME_PATH.search(text):
            raise PackagingError(
                f"machine-specific or private path marker in generated text: "
                f"{path.relative_to(plugin_root)}"
            )
        for pattern in STRONG_CREDENTIALS:
            if pattern.search(text):
                raise PackagingError(
                    f"credential marker in generated text: {path.relative_to(plugin_root)}"
                )


def validate_package(plugin_root: Path) -> dict[str, Any]:
    if plugin_root.is_symlink() or not plugin_root.is_dir():
        raise PackagingError(f"plugin root must be a non-symlink directory: {plugin_root}")
    resolved_root = plugin_root.resolve(strict=True)
    for entry in sorted(plugin_root.rglob("*"), key=lambda item: item.as_posix()):
        if entry.is_symlink():
            raise PackagingError(f"plugin package contains a symlink: {entry}")
        resolved = entry.resolve(strict=True)
        if not is_relative_to(resolved, resolved_root):
            raise PackagingError(f"plugin package path escapes root: {entry}")
        mode = entry.stat(follow_symlinks=False).st_mode
        if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise PackagingError(f"plugin package contains a special file: {entry}")

    scan_generated_text(plugin_root)
    plugin_path = require_contained_regular_file(plugin_root, "plugin.json")
    mcp_path = require_contained_regular_file(plugin_root, "mcp.json")
    skill_path = require_contained_regular_file(
        plugin_root, "skills/semantic-scala/SKILL.md"
    )
    require_contained_regular_file(plugin_root, "cli/bin/semantic-scala", executable=True)
    require_contained_regular_file(
        plugin_root, "mcp/bin/semantic-scala-mcp", executable=True
    )

    plugin = load_json(plugin_path)
    if set(plugin) != EXPECTED_PLUGIN_FIELDS:
        raise PackagingError(f"plugin.json fields differ from frozen package contract: {sorted(plugin)}")
    if plugin.get("$schema") != PLUGIN_SCHEMA or plugin.get("name") != "semantic-scala":
        raise PackagingError("plugin.json schema or name differs from frozen package contract")
    if not all(isinstance(plugin.get(field), str) and plugin[field] for field in EXPECTED_PLUGIN_FIELDS):
        raise PackagingError("plugin.json fields must be non-empty strings")

    mcp = load_json(mcp_path)
    if set(mcp) != EXPECTED_MCP_FIELDS or mcp.get("$schema") != MCP_SCHEMA:
        raise PackagingError("mcp.json top-level structure or schema differs from frozen contract")
    servers = mcp.get("mcpServers")
    if not isinstance(servers, dict) or set(servers) != {"semantic-scala"}:
        raise PackagingError("mcp.json must contain exactly the semantic-scala server")
    server = servers["semantic-scala"]
    if not isinstance(server, dict) or set(server) != EXPECTED_SERVER_FIELDS:
        raise PackagingError("semantic-scala MCP server fields differ from frozen contract")
    if (
        server.get("type") != "stdio"
        or server.get("command") != EXPECTED_COMMAND
        or server.get("args") != EXPECTED_ARGS
        or server.get("cwd") != EXPECTED_CWD
    ):
        raise PackagingError("semantic-scala MCP stdio configuration differs from frozen contract")
    command = server["command"]
    if any(character.isspace() for character in command) or not command.startswith("./"):
        raise PackagingError("MCP command must be one plugin-relative executable token")
    require_contained_regular_file(plugin_root, command[2:], executable=True)

    if skill_path.read_bytes() != CANONICAL_SKILL.read_bytes():
        raise PackagingError("generated skill is not byte-identical to the canonical skill")

    manifest_path = require_contained_regular_file(plugin_root, "package-manifest.json")
    manifest = load_json(manifest_path)
    files = file_inventory(plugin_root)
    expected_manifest = {
        "$schema": MANIFEST_SCHEMA,
        "agentPluginsVersion": "1.0.0",
        "validationLevel": "structural",
        "fileCount": len(files),
        "totalBytes": sum(item["bytes"] for item in files),
        "contentSha256": content_hash(files),
        "files": files,
    }
    if manifest != expected_manifest:
        raise PackagingError("package-manifest.json does not match generated package bytes")
    return expected_manifest


def assemble(cli_stage: Path, mcp_stage: Path, output: Path) -> dict[str, Any]:
    validate_source_tree(cli_stage, "CLI")
    validate_source_tree(mcp_stage, "MCP")
    require_contained_regular_file(cli_stage, "bin/semantic-scala", executable=True)
    require_contained_regular_file(mcp_stage, "bin/semantic-scala-mcp", executable=True)
    if output.exists() or output.is_symlink():
        raise PackagingError(f"output path already exists; use a fresh generated path: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        shutil.copyfile(TEMPLATE_ROOT / "plugin.json", temporary / "plugin.json")
        shutil.copyfile(TEMPLATE_ROOT / "mcp.json", temporary / "mcp.json")
        (temporary / "plugin.json").chmod(0o644)
        (temporary / "mcp.json").chmod(0o644)
        skill_destination = temporary / "skills/semantic-scala/SKILL.md"
        skill_destination.parent.mkdir(parents=True)
        shutil.copyfile(CANONICAL_SKILL, skill_destination)
        skill_destination.chmod(0o644)
        copy_tree(cli_stage, temporary / "cli")
        copy_tree(mcp_stage, temporary / "mcp")
        write_package_manifest(temporary)
        manifest = validate_package(temporary)
        temporary.rename(output)
        return manifest
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def rpc_send(process: subprocess.Popen[str], message: dict[str, Any]) -> None:
    if process.stdin is None:
        raise PackagingError("MCP stdin is unavailable")
    process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
    process.stdin.flush()


def rpc_read(
    process: subprocess.Popen[str], selector: selectors.BaseSelector, request_id: int
) -> dict[str, Any]:
    if not selector.select(timeout=120):
        raise PackagingError(f"timed out waiting for MCP response {request_id}")
    if process.stdout is None:
        raise PackagingError("MCP stdout is unavailable")
    line = process.stdout.readline()
    if not line:
        raise PackagingError(
            f"MCP process exited before response {request_id}: {process.returncode}"
        )
    try:
        response = json.loads(line)
    except json.JSONDecodeError as error:
        raise PackagingError(f"MCP response {request_id} was not JSON") from error
    if not isinstance(response, dict) or response.get("id") != request_id:
        raise PackagingError(f"unexpected MCP response id for request {request_id}")
    if "error" in response or not isinstance(response.get("result"), dict):
        raise PackagingError(f"MCP request {request_id} failed")
    return response["result"]


def expand_runtime_value(value: str, plugin_root: Path, plugin_data: Path) -> str:
    return value.replace("${PLUGIN_ROOT}", str(plugin_root)).replace(
        "${PLUGIN_DATA}", str(plugin_data)
    )


def smoke_package(plugin_root: Path, plugin_data: Path) -> dict[str, Any]:
    validate_package(plugin_root)
    plugin_root = plugin_root.resolve(strict=True)
    plugin_data.mkdir(parents=True, exist_ok=True)
    plugin_data = plugin_data.resolve(strict=True)
    source = plugin_data / "semantic-scala-agent-plugin-smoke.scala"
    if source.exists() or source.is_symlink():
        raise PackagingError(f"representative smoke file already exists: {source}")
    source.write_text(
        "object AgentPluginSmoke { def value: Option[Int] = Some(1) }\n",
        encoding="utf-8",
    )

    mcp = load_json(plugin_root / "mcp.json")
    server = mcp["mcpServers"]["semantic-scala"]
    executable = require_contained_regular_file(
        plugin_root, server["command"][2:], executable=True
    )
    args = [
        expand_runtime_value(value, plugin_root, plugin_data)
        for value in server.get("args", [])
    ]
    cwd = Path(expand_runtime_value(server.get("cwd", "${PLUGIN_ROOT}"), plugin_root, plugin_data))
    if not cwd.is_dir():
        raise PackagingError(f"expanded MCP cwd is not a directory: {cwd}")
    environment = os.environ.copy()
    environment["PLUGIN_ROOT"] = str(plugin_root)
    environment["PLUGIN_DATA"] = str(plugin_data)
    process = subprocess.Popen(
        [str(executable), *args],
        cwd=cwd,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    try:
        if process.stdout is None:
            raise PackagingError("MCP stdout is unavailable")
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        rpc_send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "semantic-scala-package-smoke", "version": "1"},
                },
            },
        )
        initialized = rpc_read(process, selector, 1)
        if not isinstance(initialized.get("capabilities", {}).get("tools"), dict):
            raise PackagingError("MCP initialize did not advertise tools")
        rpc_send(
            process,
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        )
        rpc_send(process, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        listed = rpc_read(process, selector, 2).get("tools")
        if not isinstance(listed, list):
            raise PackagingError("MCP tools/list did not return a tool array")
        names = [tool.get("name") for tool in listed if isinstance(tool, dict)]
        if names != EXPECTED_TOOLS:
            raise PackagingError(f"MCP tools/list differs from exact-eight contract: {names}")
        rpc_send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "semantic_point_evidence",
                    "arguments": {"workspace": str(plugin_data), "file": source.name, "line": 1, "col": 8},
                },
            },
        )
        structured = rpc_read(process, selector, 3).get("structuredContent")
        if not isinstance(structured, dict) or structured.get("ok") is not True:
            detail = json.dumps(structured, sort_keys=True) if isinstance(structured, dict) else repr(structured)
            raise PackagingError(
                f"representative CLI-backed MCP smoke did not return ok=true: {detail}"
            )
        payload = structured.get("payload")
        if not isinstance(payload, dict):
            raise PackagingError("representative CLI-backed MCP smoke omitted its payload")
        schema = payload.get("schemaVersion")
        if schema != "semantic-scala.point-evidence-result.v1":
            raise PackagingError(f"representative CLI-backed MCP smoke returned schema {schema!r}")
        return {
            "pluginRoot": str(plugin_root),
            "pluginData": str(plugin_data),
            "runtimeCommand": server["command"],
            "runtimeCwdBasis": server["cwd"],
            "toolCount": len(names),
            "toolNames": names,
            "representativeTool": "semantic_point_evidence",
            "representativeSchema": schema,
        }
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Assemble or structurally validate the semantic-scala Agent Plugin"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    assemble_parser = subparsers.add_parser("assemble")
    assemble_parser.add_argument(
        "--cli-stage",
        type=Path,
        default=ROOT / "modules/cli/target/stage",
    )
    assemble_parser.add_argument(
        "--mcp-stage",
        type=Path,
        default=ROOT / "modules/mcp-server/target/stage",
    )
    assemble_parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "target/agent-plugin/semantic-scala",
    )
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--plugin-root", type=Path, required=True)
    smoke_parser = subparsers.add_parser("smoke")
    smoke_parser.add_argument("--plugin-root", type=Path, required=True)
    smoke_parser.add_argument("--plugin-data", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "assemble":
            result = assemble(
                args.cli_stage.resolve(strict=True),
                args.mcp_stage.resolve(strict=True),
                args.output.absolute(),
            )
        elif args.command == "validate":
            result = validate_package(args.plugin_root.resolve(strict=True))
        else:
            result = smoke_package(args.plugin_root.resolve(strict=True), args.plugin_data.absolute())
        if "files" in result:
            result = {key: value for key, value in result.items() if key != "files"}
        print(json.dumps(result, sort_keys=True))
        return 0
    except (OSError, PackagingError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
