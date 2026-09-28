#!/usr/bin/env python3
"""Assemble and validate the semantic-scala OpenAI skills-only candidate."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
from typing import Any
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "packaging/openai-skills-plugin/semantic-scala"
CANONICAL_POLICY = ROOT / "skills/semantic-scala/SKILL.md"
MANIFEST_SCHEMA = "semantic-scala.openai-skills-only-package.v1"
TEMPLATE_FILES = (
    ".codex-plugin/plugin.json",
    "README.md",
    "plugin.json",
    "skills/semantic-scala/SKILL.md",
    "skills/semantic-scala/scripts/semantic_scala_cli.py",
)
GENERATED_FILES = frozenset(
    (
        *TEMPLATE_FILES,
        "skills/semantic-scala/references/semantic-scala-policy.md",
        "package-manifest.json",
    )
)
ARCHIVE_LIMIT = 50 * 1024 * 1024
UNPACKED_LIMIT = 256 * 1024 * 1024
FILE_LIMIT = 5 * 1024 * 1024
ENTRY_LIMIT = 10_000
TEXT_SUFFIXES = {"", ".json", ".md", ".py", ".txt", ".yaml", ".yml"}
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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackagingError(f"invalid JSON at {path}: {error}") from error
    if not isinstance(value, dict):
        raise PackagingError(f"JSON root must be an object: {path}")
    return value


def validate_tree(root: Path) -> None:
    if root.is_symlink() or not root.is_dir():
        raise PackagingError(f"plugin root must be a non-symlink directory: {root}")
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if "__pycache__" in relative.parts or path.suffix == ".pyc":
            raise PackagingError(f"plugin contains Python bytecode cache: {relative}")
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise PackagingError(f"plugin contains an unsupported entry: {relative}")


def copy_template(output: Path) -> None:
    for relative_text in TEMPLATE_FILES:
        relative = Path(relative_text)
        source = TEMPLATE / relative
        if source.is_symlink() or not source.is_file():
            raise PackagingError(f"template source is not a regular file: {relative}")
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o644)


def inventory(root: Path) -> list[dict[str, Any]]:
    files: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_dir() or path.name == "package-manifest.json":
            continue
        files.append(
            {
                "path": path.relative_to(root).as_posix(),
                "bytes": path.stat().st_size,
                "mode": f"{stat.S_IMODE(path.stat().st_mode):04o}",
                "sha256": sha256(path),
            }
        )
    return files


def expected_manifest(root: Path) -> dict[str, Any]:
    files = inventory(root)
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    manifest: dict[str, Any] = {
        "$schema": MANIFEST_SCHEMA,
        "version": "0.1.0-alpha.3",
        "platform": "linux-x86_64-gnu",
        "skillsOnly": True,
        "mcpServers": 0,
        "bootstrap": {
            "python": ">=3.11",
            "firstRunNetwork": True,
            "artifactUrl": (
                "https://github.com/DmytroMitin/scala-semantic-harness/releases/"
                "download/0.1.0-alpha.3/"
                "semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb"
            ),
            "artifactBytes": 285603142,
            "artifactSha256": (
                "f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d"
            ),
        },
        "inventoryExcludesManifest": True,
        "fileCount": len(files) + 1,
        "totalBytes": 0,
        "largestFileBytes": max((item["bytes"] for item in files), default=0),
        "contentSha256": hashlib.sha256(encoded).hexdigest(),
        "files": files,
    }
    inventoried_bytes = sum(item["bytes"] for item in files)
    while True:
        serialized = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
        total = inventoried_bytes + len(serialized)
        largest = max(
            max((item["bytes"] for item in files), default=0), len(serialized)
        )
        if manifest["totalBytes"] == total and manifest["largestFileBytes"] == largest:
            return manifest
        manifest["totalBytes"] = total
        manifest["largestFileBytes"] = largest


def scan_text(root: Path) -> None:
    for path in root.rglob("*"):
        if path.is_dir() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            value = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if HOME_PATH.search(value):
            raise PackagingError(
                f"machine-specific home path in {path.relative_to(root)}"
            )
        for pattern in STRONG_CREDENTIALS:
            if pattern.search(value):
                raise PackagingError(f"credential marker in {path.relative_to(root)}")


def _interface(manifest: dict[str, Any], *, portable: bool) -> dict[str, Any]:
    if portable:
        return manifest.get("extensions", {}).get("com.openai", {}).get("interface", {})
    return manifest.get("interface", {})


def validate(root: Path) -> dict[str, Any]:
    validate_tree(root)
    scan_text(root)
    actual_files = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file()
    }
    if any(Path(name).name in {"mcp.json", ".mcp.json"} for name in actual_files):
        raise PackagingError("skills-only candidate must not contain MCP configuration")
    if actual_files != GENERATED_FILES:
        raise PackagingError("candidate file set differs from the reviewed allowlist")
    reference = root / "skills/semantic-scala/references/semantic-scala-policy.md"
    if reference.read_bytes() != CANONICAL_POLICY.read_bytes():
        raise PackagingError("canonical semantic-scala policy reference drifted")
    portable = load_json(root / "plugin.json")
    compatibility = load_json(root / ".codex-plugin/plugin.json")
    for manifest, is_portable in [(portable, True), (compatibility, False)]:
        if manifest.get("name") != "semantic-scala" or manifest.get("version") != "0.1.0-alpha.3":
            raise PackagingError("plugin identity differs from exact Alpha-3")
        encoded = json.dumps(manifest, sort_keys=True)
        if "mcpServers" in encoded or '"apps"' in encoded:
            raise PackagingError("skills-only manifest contains MCP or app configuration")
        interface = _interface(manifest, portable=is_portable)
        if len(interface.get("displayName", "")) > 30:
            raise PackagingError("display name exceeds final submission limit")
        if not interface.get("shortDescription") or len(interface["shortDescription"]) > 30:
            raise PackagingError("short description exceeds final submission limit")
        if interface.get("capabilities") != ["Inspect declared Scala effect wrappers"]:
            raise PackagingError("capability list exceeds the reviewed helper scope")
    skill = (root / "skills/semantic-scala/SKILL.md").read_text(encoding="utf-8")
    if "scripts/semantic_scala_cli.py" not in skill or "references/semantic-scala-policy.md" not in skill:
        raise PackagingError("skill does not reference its helper and canonical policy")
    if "semantic-scala-mcp" in skill:
        raise PackagingError("skills-only workflow references an MCP executable")
    manifest = load_json(root / "package-manifest.json")
    expected = expected_manifest(root)
    if manifest != expected:
        raise PackagingError("package manifest differs from deterministic inventory")
    if sum(1 for _ in root.rglob("*")) >= ENTRY_LIMIT:
        raise PackagingError("candidate reaches the archive entry limit")
    if expected["totalBytes"] >= UNPACKED_LIMIT:
        raise PackagingError("candidate reaches the unpacked-size limit")
    if expected["largestFileBytes"] >= FILE_LIMIT:
        raise PackagingError("candidate reaches the per-file limit")
    return expected


def assemble(output: Path) -> dict[str, Any]:
    if output.exists() or output.is_symlink():
        raise PackagingError(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        copy_template(temporary)
        reference = temporary / "skills/semantic-scala/references/semantic-scala-policy.md"
        reference.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(CANONICAL_POLICY, reference)
        reference.chmod(0o644)
        manifest = expected_manifest(temporary)
        package_manifest = temporary / "package-manifest.json"
        package_manifest.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        package_manifest.chmod(0o644)
        validated = validate(temporary)
        temporary.rename(output)
        return validated
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def pack(root: Path, output: Path) -> dict[str, Any]:
    manifest = validate(root)
    if output.exists() or output.is_symlink():
        raise PackagingError(f"archive output already exists: {output}")
    paths = sorted(
        (path for path in root.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(root).as_posix(),
    )
    with zipfile.ZipFile(
        output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
    ) as archive:
        for path in paths:
            info = zipfile.ZipInfo(
                path.relative_to(root).as_posix(), date_time=(1980, 1, 1, 0, 0, 0)
            )
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            info._compresslevel = 9
            archive.writestr(info, path.read_bytes())
    size = output.stat().st_size
    if size >= ARCHIVE_LIMIT:
        output.unlink(missing_ok=True)
        raise PackagingError("candidate reaches the archive-size limit")
    return {
        "archive": str(output),
        "archiveBytes": size,
        "archiveSha256": sha256(output),
        "fileCount": manifest["fileCount"],
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    assemble_parser = commands.add_parser("assemble")
    assemble_parser.add_argument("--output", type=Path, required=True)
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--plugin-root", type=Path, required=True)
    pack_parser = commands.add_parser("pack")
    pack_parser.add_argument("--plugin-root", type=Path, required=True)
    pack_parser.add_argument("--output", type=Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "assemble":
            value = assemble(args.output.absolute())
        elif args.command == "validate":
            value = validate(args.plugin_root.resolve(strict=True))
        else:
            value = pack(args.plugin_root.resolve(strict=True), args.output.absolute())
        print(
            json.dumps(
                {key: item for key, item in value.items() if key != "files"},
                sort_keys=True,
            )
        )
        return 0
    except (OSError, PackagingError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
