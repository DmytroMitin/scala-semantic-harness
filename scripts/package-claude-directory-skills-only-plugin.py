#!/usr/bin/env python3
"""Assemble and validate the Claude Directory skills-only CLI candidate."""

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
SOURCE = ROOT / "packaging/claude-directory-skills-only-plugin/semantic-scala"
CANONICAL_POLICY = ROOT / "skills/semantic-scala/SKILL.md"
ROOT_LICENSE = ROOT / "LICENSE"
MANIFEST_SCHEMA = "semantic-scala.claude-directory-skills-only-package.v1"
PLUGIN_VERSION = "0.1.0-alpha.3.2"
CLI_VERSION = "0.1.0-alpha.3"
PACKAGE_FILES = frozenset(
    {
        ".claude-plugin/plugin.json",
        "LICENSE",
        "README.md",
        "package-manifest.json",
        "skills/semantic-scala/SKILL.md",
        "skills/semantic-scala/references/semantic-scala-policy.md",
    }
)
ARCHIVE_LIMIT = 50 * 1024 * 1024
UNPACKED_LIMIT = 256 * 1024 * 1024
FILE_LIMIT = 5 * 1024 * 1024
ENTRY_LIMIT = 10_000
HOME_PATH = re.compile(r"/home/[^/\s\"']+")
STRONG_CREDENTIALS = (
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(
        r"(?i)(?:api[_-]?key|access[_-]?token|password|client[_-]?secret)"
        r"\s*[:=]\s*['\"]?[A-Za-z0-9+/_.-]{12,}"
    ),
)
FORBIDDEN_PLUGIN_KEYS = {
    "mcpservers",
    "hooks",
    "lspservers",
    "commands",
    "agents",
}


class PackagingError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
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
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise PackagingError(f"plugin contains an unsupported entry: {relative}")
        if stat.S_ISREG(mode) and stat.S_IMODE(mode) & 0o111:
            raise PackagingError(f"plugin contains an executable file: {relative}")


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
        "version": PLUGIN_VERSION,
        "route": "skills-only-external-cli",
        "skillsOnly": True,
        "bundlesCli": False,
        "runtimeDeclarations": [],
        "cliPrerequisite": {
            "command": "semantic-scala",
            "installation": "independent",
            "requiredVersion": CLI_VERSION,
        },
        "networkDuringPluginInstall": False,
        "automaticNetworkDuringPluginUse": False,
        "inventoryExcludesManifest": True,
        "fileCount": len(files) + 1,
        "archiveEntryCount": len(files) + 1,
        "filesystemEntryCount": sum(1 for _ in root.rglob("*"))
        + (0 if (root / "package-manifest.json").exists() else 1),
        "totalBytes": 0,
        "largestFileBytes": max((item["bytes"] for item in files), default=0),
        "contentSha256": hashlib.sha256(encoded).hexdigest(),
        "files": files,
    }
    inventoried_bytes = sum(item["bytes"] for item in files)
    while True:
        serialized = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
        total = inventoried_bytes + len(serialized)
        largest = max(max((item["bytes"] for item in files), default=0), len(serialized))
        if manifest["totalBytes"] == total and manifest["largestFileBytes"] == largest:
            return manifest
        manifest["totalBytes"] = total
        manifest["largestFileBytes"] = largest


def write_manifest(root: Path) -> dict[str, Any]:
    manifest = expected_manifest(root)
    target = root / "package-manifest.json"
    target.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    target.chmod(0o644)
    return manifest


def refresh_source() -> dict[str, Any]:
    reference = SOURCE / "skills/semantic-scala/references/semantic-scala-policy.md"
    reference.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(CANONICAL_POLICY, reference)
    reference.chmod(0o644)
    license_path = SOURCE / "LICENSE"
    shutil.copyfile(ROOT_LICENSE, license_path)
    license_path.chmod(0o644)
    for relative_text in PACKAGE_FILES - {"package-manifest.json"}:
        (SOURCE / relative_text).chmod(0o644)
    write_manifest(SOURCE)
    return validate(SOURCE)


def _frontmatter(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise PackagingError(f"Markdown skill has no YAML frontmatter: {path}")
    pieces = text.split("---", 2)
    if len(pieces) != 3:
        raise PackagingError(f"Markdown skill has invalid YAML frontmatter: {path}")
    return pieces[1]


def _walk_keys(value: object) -> set[str]:
    if isinstance(value, dict):
        result = {str(key).lower() for key in value}
        for child in value.values():
            result.update(_walk_keys(child))
        return result
    if isinstance(value, list):
        result: set[str] = set()
        for child in value:
            result.update(_walk_keys(child))
        return result
    return set()


def scan_text(root: Path) -> None:
    for path in root.rglob("*"):
        if path.is_dir():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if HOME_PATH.search(text):
            raise PackagingError(f"machine-specific home path in {path.relative_to(root)}")
        for pattern in STRONG_CREDENTIALS:
            if pattern.search(text):
                raise PackagingError(f"credential marker in {path.relative_to(root)}")


def validate(root: Path) -> dict[str, Any]:
    validate_tree(root)
    actual_files = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file()
    }
    if actual_files != PACKAGE_FILES:
        raise PackagingError("candidate file set differs from the reviewed allowlist")

    skill_path = root / "skills/semantic-scala/SKILL.md"
    frontmatter = _frontmatter(skill_path).lower()
    if "allowed-tools" in frontmatter:
        raise PackagingError("contained skill must not declare allowed-tools")
    if "disallowed-tools" in frontmatter:
        raise PackagingError("contained skill must not declare disallowed-tools")
    skill = skill_path.read_text(encoding="utf-8")
    if "references/semantic-scala-policy.md" not in skill:
        raise PackagingError("skill does not reference the canonical policy")

    reference = root / "skills/semantic-scala/references/semantic-scala-policy.md"
    if reference.read_bytes() != CANONICAL_POLICY.read_bytes():
        raise PackagingError("canonical semantic-scala policy reference drifted")

    plugin = load_json(root / ".claude-plugin/plugin.json")
    if plugin.get("name") != "semantic-scala" or plugin.get("version") != PLUGIN_VERSION:
        raise PackagingError(f"plugin identity differs from exact {PLUGIN_VERSION}")
    forbidden = _walk_keys(plugin) & FORBIDDEN_PLUGIN_KEYS
    if forbidden:
        raise PackagingError(f"contained plugin manifest declares runtime components: {sorted(forbidden)}")

    scan_text(root)
    manifest = load_json(root / "package-manifest.json")
    expected = expected_manifest(root)
    if manifest != expected:
        raise PackagingError("package manifest differs from deterministic inventory")
    if expected["filesystemEntryCount"] >= ENTRY_LIMIT:
        raise PackagingError("candidate reaches the directory entry limit")
    if expected["totalBytes"] >= UNPACKED_LIMIT:
        raise PackagingError("candidate reaches the directory unpacked-size limit")
    if expected["largestFileBytes"] >= FILE_LIMIT:
        raise PackagingError("candidate reaches the directory per-file limit")
    return expected


def assemble(output: Path) -> dict[str, Any]:
    validate(SOURCE)
    if output.exists() or output.is_symlink():
        raise PackagingError(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        for relative_text in sorted(PACKAGE_FILES):
            relative = Path(relative_text)
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE / relative, target)
            target.chmod(0o644)
        result = validate(temporary)
        temporary.rename(output)
        return result
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
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
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
        raise PackagingError("candidate reaches the directory archive-size limit")
    return {
        "archive": str(output),
        "archiveBytes": size,
        "archiveSha256": sha256(output),
        "fileCount": manifest["fileCount"],
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("refresh-source")
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
        if args.command == "refresh-source":
            value = refresh_source()
        elif args.command == "assemble":
            value = assemble(args.output.absolute())
        elif args.command == "validate":
            value = validate(args.plugin_root.resolve(strict=True))
        else:
            value = pack(args.plugin_root.resolve(strict=True), args.output.absolute())
        print(json.dumps({key: item for key, item in value.items() if key != "files"}, sort_keys=True))
        return 0
    except (OSError, PackagingError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
