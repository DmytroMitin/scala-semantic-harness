#!/usr/bin/env python3
"""Assemble and validate native OpenAI/Codex and Claude Code plugin candidates."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
from typing import Any
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CANONICAL_SKILL = ROOT / "skills/semantic-scala/SKILL.md"
DISCOVERY_METADATA = ROOT / "distribution/discovery/metadata.json"
REGISTRY_RECORD = ROOT / "distribution/mcp-registry/server.alpha3.json"
TEMPLATE_ROOTS = {
    "openai": ROOT / "packaging/openai-plugin/semantic-scala",
    "claude": ROOT / "packaging/claude-plugin/semantic-scala",
}
EXPECTED_MCPB_BYTES = 285_603_142
EXPECTED_MCPB_SHA256 = "f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d"
PACKAGE_MANIFEST_SCHEMA = "semantic-scala.native-plugin-package-manifest.v1"
ALLOWED_PAYLOAD_ROOTS = ("LICENSES/", "app/", "bin/", "runtime/")
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
FORBIDDEN_TEXT = (ROOT.name + "-" + "control", str(ROOT))
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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bytes_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def source_contract() -> dict[str, Any]:
    metadata = load_json(DISCOVERY_METADATA)
    registry = load_json(REGISTRY_RECORD)
    packages = registry.get("packages")
    if not isinstance(packages, list) or len(packages) != 1 or not isinstance(packages[0], dict):
        raise PackagingError("official Registry record must contain exactly one package")
    package = packages[0]
    version = metadata.get("supportedVersion")
    if version != registry.get("version") or version != package.get("version"):
        raise PackagingError("discovery and Registry versions do not agree")
    digest = package.get("fileSha256")
    if digest != EXPECTED_MCPB_SHA256:
        raise PackagingError("official Registry digest differs from the frozen Alpha-3 package")
    return {
        "version": version,
        "description": metadata.get("description"),
        "repository": metadata.get("repository"),
        "documentation": metadata.get("documentation"),
        "license": metadata.get("license"),
        "author": metadata.get("author"),
        "displayName": metadata.get("displayName"),
        "categories": metadata.get("categories"),
        "tags": metadata.get("tags"),
        "mcpbUrl": package.get("identifier"),
        "mcpbSha256": digest,
        "mcpbBytes": EXPECTED_MCPB_BYTES,
    }


def require_regular(root: Path, relative: str, executable: bool = False) -> Path:
    candidate = root / relative
    try:
        resolved_root = root.resolve(strict=True)
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise PackagingError(f"required package path is missing: {relative}") from error
    if not is_relative_to(resolved, resolved_root):
        raise PackagingError(f"package path escapes root: {relative}")
    if candidate.is_symlink() or not candidate.is_file():
        raise PackagingError(f"package path is not a regular non-symlink file: {relative}")
    if executable and not os.access(candidate, os.X_OK):
        raise PackagingError(f"package launcher is not executable: {relative}")
    return candidate


def validate_tree(root: Path) -> None:
    if root.is_symlink() or not root.is_dir():
        raise PackagingError(f"package root must be a non-symlink directory: {root}")
    resolved_root = root.resolve(strict=True)
    for entry in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if entry.is_symlink():
            raise PackagingError(f"package contains a symlink: {entry}")
        resolved = entry.resolve(strict=True)
        if not is_relative_to(resolved, resolved_root):
            raise PackagingError(f"package path escapes root: {entry}")
        mode = entry.stat(follow_symlinks=False).st_mode
        if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise PackagingError(f"package contains a special file: {entry}")


def copy_template(source: Path, destination: Path) -> None:
    validate_tree(source)
    for entry in sorted(source.rglob("*"), key=lambda item: item.as_posix()):
        relative = entry.relative_to(source)
        target = destination / relative
        if entry.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            target.chmod(0o755)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(entry, target)
            target.chmod(0o644)


def safe_archive_path(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise PackagingError(f"unsafe MCPB archive path: {name!r}")
    return path


def archive_mode(info: zipfile.ZipInfo) -> int:
    raw = info.external_attr >> 16
    kind = stat.S_IFMT(raw)
    if kind not in {0, stat.S_IFREG}:
        raise PackagingError(f"MCPB entry is not a regular file: {info.filename}")
    mode = stat.S_IMODE(raw) or 0o644
    if mode not in {0o644, 0o755}:
        raise PackagingError(f"unexpected MCPB file mode {mode:04o}: {info.filename}")
    return mode


def inspect_source_archive(
    archive_path: Path,
    expected_sha256: str,
    expected_bytes: int,
    expected_version: str,
) -> tuple[zipfile.ZipFile, dict[str, dict[str, Any]]]:
    if archive_path.is_symlink() or not archive_path.is_file():
        raise PackagingError(f"MCPB must be a regular non-symlink file: {archive_path}")
    actual_bytes = archive_path.stat().st_size
    actual_sha = sha256(archive_path)
    if actual_bytes != expected_bytes or actual_sha != expected_sha256:
        raise PackagingError(
            f"MCPB identity mismatch: bytes={actual_bytes}, sha256={actual_sha}"
        )
    try:
        archive = zipfile.ZipFile(archive_path)
        archive.testzip()
        source_manifest = json.loads(archive.read("manifest.json"))
        payload_manifest = json.loads(archive.read("payload-inventory.json"))
    except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise PackagingError(f"invalid MCPB archive: {error}") from error
    if (
        not isinstance(source_manifest, dict)
        or source_manifest.get("name") != "semantic-scala"
        or source_manifest.get("version") != expected_version
    ):
        archive.close()
        raise PackagingError("MCPB manifest name or version differs from the expected Alpha-3 source")
    files = payload_manifest.get("files") if isinstance(payload_manifest, dict) else None
    if not isinstance(files, list):
        archive.close()
        raise PackagingError("MCPB payload inventory is missing its files array")
    records: dict[str, dict[str, Any]] = {}
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            archive.close()
            raise PackagingError("MCPB payload inventory contains an invalid row")
        path = item["path"]
        if path in records:
            archive.close()
            raise PackagingError(f"duplicate MCPB payload inventory path: {path}")
        records[path] = item
    return archive, records


def extract_runtime_payload(
    archive_path: Path,
    destination: Path,
    expected_sha256: str,
    expected_bytes: int,
    expected_version: str,
) -> int:
    archive, records = inspect_source_archive(
        archive_path, expected_sha256, expected_bytes, expected_version
    )
    extracted = 0
    seen: set[str] = set()
    try:
        for info in sorted(archive.infolist(), key=lambda item: item.filename):
            name = info.filename
            if name in seen:
                raise PackagingError(f"duplicate MCPB archive path: {name}")
            seen.add(name)
            safe_archive_path(name)
            if not name.startswith(ALLOWED_PAYLOAD_ROOTS):
                continue
            if info.is_dir():
                raise PackagingError(f"MCPB unexpectedly contains a directory entry: {name}")
            mode = archive_mode(info)
            data = archive.read(info)
            record = records.get(name)
            expected_record = {
                "path": name,
                "bytes": len(data),
                "mode": f"{mode:04o}",
                "sha256": bytes_sha256(data),
            }
            if record != expected_record:
                raise PackagingError(f"MCPB payload inventory mismatch: {name}")
            target = destination.joinpath(*PurePosixPath(name).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            target.chmod(mode)
            extracted += 1
    finally:
        archive.close()
    if extracted == 0:
        raise PackagingError("MCPB contained no reusable runtime payload")
    return extracted


def file_inventory(root: Path) -> list[dict[str, Any]]:
    files: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_dir() or path.name == "package-manifest.json":
            continue
        relative = path.relative_to(root).as_posix()
        mode = stat.S_IMODE(path.stat(follow_symlinks=False).st_mode)
        files.append(
            {
                "path": relative,
                "bytes": path.stat(follow_symlinks=False).st_size,
                "mode": f"{mode:04o}",
                "sha256": sha256(path),
            }
        )
    return files


def content_hash(files: list[dict[str, Any]]) -> str:
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def expected_package_manifest(
    platform: str, root: Path, contract: dict[str, Any]
) -> dict[str, Any]:
    files = file_inventory(root)
    return {
        "$schema": PACKAGE_MANIFEST_SCHEMA,
        "platform": platform,
        "version": contract["version"],
        "sourceMcpb": {
            "url": contract["mcpbUrl"],
            "bytes": contract["mcpbBytes"],
            "sha256": contract["mcpbSha256"],
        },
        "validationLevel": "structural",
        "fileCount": len(files),
        "totalBytes": sum(item["bytes"] for item in files),
        "contentSha256": content_hash(files),
        "files": files,
    }


def write_package_manifest(platform: str, root: Path, contract: dict[str, Any]) -> dict[str, Any]:
    manifest = expected_package_manifest(platform, root, contract)
    (root / "package-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def scan_generated_text(root: Path) -> None:
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_dir() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for marker in FORBIDDEN_TEXT:
            if marker and marker in text:
                raise PackagingError(
                    f"machine-specific or private marker in generated text: {path.relative_to(root)}"
                )
        if HOME_PATH.search(text):
            raise PackagingError(
                f"machine-specific home path in generated text: {path.relative_to(root)}"
            )
        for pattern in STRONG_CREDENTIALS:
            if pattern.search(text):
                raise PackagingError(f"credential marker in generated text: {path.relative_to(root)}")


def validate_common(root: Path, contract: dict[str, Any]) -> None:
    validate_tree(root)
    scan_generated_text(root)
    skill = require_regular(root, "skills/semantic-scala/SKILL.md")
    if skill.read_bytes() != CANONICAL_SKILL.read_bytes():
        raise PackagingError("generated skill is not byte-identical to the canonical skill")
    require_regular(root, "bin/semantic-scala", executable=True)
    require_regular(root, "bin/semantic-scala-mcp", executable=True)
    require_regular(root, "runtime/bin/java", executable=True)
    require_regular(root, "app/cli/classpath.txt")
    require_regular(root, "app/mcp/classpath.txt")
    manifest = load_json(require_regular(root, "package-manifest.json"))
    if manifest != expected_package_manifest(manifest.get("platform"), root, contract):
        raise PackagingError("package-manifest.json does not match the generated candidate")


def require_metadata(value: dict[str, Any], contract: dict[str, Any]) -> None:
    if (
        value.get("name") != "semantic-scala"
        or value.get("version") != contract["version"]
        or value.get("description") != contract["description"]
        or value.get("repository") != contract["repository"]
        or value.get("license") != contract["license"]
        or value.get("author") != contract["author"]
        or value.get("keywords") != contract["tags"]
    ):
        raise PackagingError("plugin identity differs from canonical discovery metadata")


def validate_openai(root: Path, contract: dict[str, Any]) -> None:
    plugin = load_json(require_regular(root, "plugin.json"))
    require_metadata(plugin, contract)
    if plugin.get("$schema") != "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json":
        raise PackagingError("OpenAI portable plugin schema differs from Agent Plugins 1.0.0")
    overlay = load_json(require_regular(root, ".codex-plugin/plugin.json"))
    require_metadata(overlay, contract)
    if overlay.get("skills") != "./skills/" or "mcpServers" in overlay:
        raise PackagingError(
            "Codex compatibility overlay must defer MCP discovery to portable root mcp.json"
        )
    mcp = load_json(require_regular(root, "mcp.json"))
    if mcp.get("$schema") != "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json":
        raise PackagingError("OpenAI MCP schema differs from Agent Plugins 1.0.0")
    server = mcp.get("mcpServers", {}).get("semantic-scala")
    if server != {
        "type": "stdio",
        "command": "./bin/semantic-scala-mcp",
        "args": ["--cli", "${PLUGIN_ROOT}/bin/semantic-scala"],
        "cwd": "${PLUGIN_ROOT}",
    }:
        raise PackagingError("OpenAI local stdio MCP configuration differs from the candidate contract")


def validate_claude(root: Path, contract: dict[str, Any]) -> None:
    plugin = load_json(require_regular(root, ".claude-plugin/plugin.json"))
    require_metadata(plugin, contract)
    mcp = load_json(require_regular(root, ".mcp.json"))
    server = mcp.get("mcpServers", {}).get("semantic-scala")
    if server != {
        "command": "${CLAUDE_PLUGIN_ROOT}/bin/semantic-scala-mcp",
        "args": ["--cli", "${CLAUDE_PLUGIN_ROOT}/bin/semantic-scala"],
    }:
        raise PackagingError("Claude local stdio MCP configuration differs from the candidate contract")


def validate_package(platform: str, root: Path, contract: dict[str, Any] | None = None) -> dict[str, Any]:
    contract = contract or source_contract()
    validate_common(root, contract)
    manifest = load_json(root / "package-manifest.json")
    if manifest.get("platform") != platform:
        raise PackagingError("package manifest platform differs from the selected validator")
    if platform == "openai":
        validate_openai(root, contract)
    elif platform == "claude":
        validate_claude(root, contract)
    else:
        raise PackagingError(f"unsupported native plugin platform: {platform}")
    return expected_package_manifest(platform, root, contract)


def assemble(
    platform: str,
    archive_path: Path,
    output: Path,
    contract: dict[str, Any] | None = None,
) -> dict[str, Any]:
    contract = contract or source_contract()
    template = TEMPLATE_ROOTS.get(platform)
    if template is None:
        raise PackagingError(f"unsupported native plugin platform: {platform}")
    if output.exists() or output.is_symlink():
        raise PackagingError(f"output path already exists; use a fresh path: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        copy_template(template, temporary)
        skill = temporary / "skills/semantic-scala/SKILL.md"
        skill.parent.mkdir(parents=True)
        shutil.copyfile(CANONICAL_SKILL, skill)
        skill.chmod(0o644)
        extract_runtime_payload(
            archive_path,
            temporary,
            contract["mcpbSha256"],
            contract["mcpbBytes"],
            contract["version"],
        )
        write_package_manifest(platform, temporary, contract)
        manifest = validate_package(platform, temporary, contract)
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
        raise PackagingError(f"MCP process exited before response {request_id}: {process.returncode}")
    try:
        response = json.loads(line)
    except json.JSONDecodeError as error:
        raise PackagingError(f"MCP response {request_id} was not JSON") from error
    if not isinstance(response, dict) or response.get("id") != request_id:
        raise PackagingError(f"unexpected MCP response id for request {request_id}")
    if "error" in response or not isinstance(response.get("result"), dict):
        raise PackagingError(f"MCP request {request_id} failed")
    return response["result"]


def expand(value: str, platform: str, root: Path, data: Path) -> str:
    replacements = {
        "${PLUGIN_ROOT}": str(root),
        "${PLUGIN_DATA}": str(data),
        "${CLAUDE_PLUGIN_ROOT}": str(root),
        "${CLAUDE_PLUGIN_DATA}": str(data),
    }
    result = value
    for marker, replacement in replacements.items():
        result = result.replace(marker, replacement)
    if "${" in result:
        raise PackagingError(f"unexpanded {platform} plugin placeholder: {value}")
    return result


def plugin_server(platform: str, root: Path) -> dict[str, Any]:
    config = load_json(root / ("mcp.json" if platform == "openai" else ".mcp.json"))
    server = config.get("mcpServers", {}).get("semantic-scala")
    if not isinstance(server, dict):
        raise PackagingError("native plugin MCP configuration lacks semantic-scala")
    return server


def project_snapshot(root: Path) -> list[dict[str, Any]]:
    """Capture every project entry relevant to a read-only smoke assertion."""
    entries: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        relative = path.relative_to(root).as_posix()
        metadata = path.lstat()
        mode = f"{stat.S_IMODE(metadata.st_mode):04o}"
        if stat.S_ISLNK(metadata.st_mode):
            raise PackagingError(f"smoke project contains a symbolic link: {relative}")
        if stat.S_ISDIR(metadata.st_mode):
            entries.append({"path": relative, "type": "directory", "mode": mode})
        elif stat.S_ISREG(metadata.st_mode):
            entries.append(
                {
                    "path": relative,
                    "type": "file",
                    "bytes": metadata.st_size,
                    "mode": mode,
                    "sha256": sha256(path),
                }
            )
        else:
            raise PackagingError(f"smoke project contains a special entry: {relative}")
    return entries


def process_group_exists(group: int) -> bool:
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def smoke_package(
    platform: str,
    root: Path,
    data: Path,
    contract: dict[str, Any] | None = None,
) -> dict[str, Any]:
    validate_package(platform, root, contract)
    root = root.resolve(strict=True)
    if data.exists() or data.is_symlink():
        raise PackagingError(f"smoke data path must be fresh: {data}")
    data.mkdir(parents=True)
    data = data.resolve(strict=True)
    source = data / "semantic-scala-native-plugin-smoke.scala"
    source.write_text(
        "object NativePluginSmoke { def value: Option[Int] = Some(1) }\n",
        encoding="utf-8",
    )
    empty_path = data / "empty-path"
    empty_path.mkdir()
    before = project_snapshot(data)
    server = plugin_server(platform, root)
    command_value = server.get("command")
    args_value = server.get("args", [])
    if not isinstance(command_value, str) or not isinstance(args_value, list) or not all(
        isinstance(item, str) for item in args_value
    ):
        raise PackagingError("native plugin MCP command or args are invalid")
    expanded_command = expand(command_value, platform, root, data)
    if expanded_command.startswith("./"):
        executable = root / expanded_command[2:]
    else:
        executable = Path(expanded_command)
    executable = require_regular(root, executable.relative_to(root).as_posix(), executable=True)
    args = [expand(item, platform, root, data) for item in args_value]
    cwd_value = server.get("cwd")
    cwd = Path(expand(cwd_value, platform, root, data)) if isinstance(cwd_value, str) else data
    if not cwd.is_dir():
        raise PackagingError(f"expanded MCP cwd is not a directory: {cwd}")
    environment = os.environ.copy()
    environment.update(
        {
            "PLUGIN_ROOT": str(root),
            "PLUGIN_DATA": str(data),
            "CLAUDE_PLUGIN_ROOT": str(root),
            "CLAUDE_PLUGIN_DATA": str(data),
        }
    )
    environment["PATH"] = str(empty_path)
    process = subprocess.Popen(
        [str(executable), *args],
        cwd=cwd,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    process_group = process.pid
    smoke_result: dict[str, Any] | None = None
    selector: selectors.BaseSelector | None = None
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
                    "clientInfo": {"name": "semantic-scala-native-plugin-smoke", "version": "1"},
                },
            },
        )
        initialized = rpc_read(process, selector, 1)
        if not isinstance(initialized.get("capabilities", {}).get("tools"), dict):
            raise PackagingError("MCP initialize did not advertise tools")
        rpc_send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
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
                    "arguments": {
                        "workspace": str(data),
                        "file": source.name,
                        "line": 1,
                        "col": 8,
                    },
                },
            },
        )
        structured = rpc_read(process, selector, 3).get("structuredContent")
        if not isinstance(structured, dict) or structured.get("ok") is not True:
            raise PackagingError("representative CLI-backed MCP call did not return ok=true")
        payload = structured.get("payload")
        if not isinstance(payload, dict) or payload.get("schemaVersion") != "semantic-scala.point-evidence-result.v2":
            raise PackagingError("representative MCP call returned an unexpected schema")
        if project_snapshot(data) != before:
            raise PackagingError("read-only relocated smoke modified its disposable target project")
        smoke_result = {
            "platform": platform,
            "relocatedPathContainedSpaces": " " in str(root),
            "hostJavaOnPath": False,
            "toolCount": len(names),
            "toolNames": names,
            "representativeTool": "semantic_point_evidence",
            "representativeSchema": payload["schemaVersion"],
            "externalProjectModified": False,
        }
    finally:
        if process.poll() is None:
            os.killpg(process_group, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process_group, signal.SIGKILL)
                process.wait(timeout=10)
        if process_group_exists(process_group):
            os.killpg(process_group, signal.SIGKILL)
            raise PackagingError("MCP smoke left a helper process running")
        if selector is not None:
            selector.close()
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None:
                stream.close()
    assert smoke_result is not None
    smoke_result["helperProcessesRemaining"] = 0
    return smoke_result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    assemble_parser = subparsers.add_parser("assemble")
    assemble_parser.add_argument("--platform", choices=sorted(TEMPLATE_ROOTS), required=True)
    assemble_parser.add_argument("--mcpb", type=Path, required=True)
    assemble_parser.add_argument("--output", type=Path, required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--platform", choices=sorted(TEMPLATE_ROOTS), required=True)
    validate_parser.add_argument("--plugin-root", type=Path, required=True)
    smoke_parser = subparsers.add_parser("smoke")
    smoke_parser.add_argument("--platform", choices=sorted(TEMPLATE_ROOTS), required=True)
    smoke_parser.add_argument("--plugin-root", type=Path, required=True)
    smoke_parser.add_argument("--plugin-data", type=Path, required=True)
    return parser.parse_args()


def public_result(result: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in result.items() if key != "files"}


def main() -> int:
    args = parse_args()
    try:
        if args.command == "assemble":
            result = assemble(
                args.platform,
                args.mcpb.resolve(strict=True),
                args.output.absolute(),
            )
        elif args.command == "validate":
            result = validate_package(args.platform, args.plugin_root.resolve(strict=True))
        else:
            result = smoke_package(
                args.platform,
                args.plugin_root.resolve(strict=True),
                args.plugin_data.absolute(),
            )
        print(json.dumps(public_result(result), sort_keys=True))
        return 0
    except (OSError, PackagingError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
