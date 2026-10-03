#!/usr/bin/env python3
"""Build the reviewer-compliant Claude plugin with an embedded local MCPB."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tempfile
from typing import Any
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "packaging/claude-embedded-plugin/semantic-scala"
CANONICAL_SKILL = ROOT / "skills/semantic-scala/SKILL.md"
PLUGIN_VERSION = "0.1.0-alpha.3.1"
RUNTIME_VERSION = "0.1.0-alpha.3"
MCPB_NAME = "semantic-scala.mcpb"
UPLOAD_LIMIT = 200_000_000
TARGET_UNPACKED_LIMIT = 180 * 1024 * 1024
ENTRY_LIMIT = 5_000
GENERATED_FILES = {
    ".claude-plugin/plugin.json",
    "LICENSE",
    "README.md",
    MCPB_NAME,
    "package-manifest.json",
    "skills/semantic-scala/SKILL.md",
}


class PackagingError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackagingError(f"invalid JSON at {path}: {error}") from error
    if not isinstance(value, dict):
        raise PackagingError(f"JSON root must be an object: {path}")
    return value


def safe_archive_name(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if (
        not name
        or "\\" in name
        or "\x00" in name
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
        or len(name) > 472
        or len(path.parts) > 12
        or any(len(part) > 255 for part in path.parts)
    ):
        raise PackagingError(f"unsafe or unsupported MCPB path: {name!r}")
    return path


def inspect_mcpb(path: Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise PackagingError(f"MCPB input is not a regular file: {path}")
    names: set[str] = set()
    unpacked = 0
    try:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                safe_archive_name(info.filename)
                if info.filename in names:
                    raise PackagingError(f"duplicate MCPB entry: {info.filename}")
                names.add(info.filename)
                if info.is_dir():
                    raise PackagingError(f"MCPB contains a directory entry: {info.filename}")
                if info.flag_bits & 0x1:
                    raise PackagingError(f"MCPB contains an encrypted entry: {info.filename}")
                if info.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
                    raise PackagingError(f"MCPB uses unsupported compression: {info.filename}")
                kind = stat.S_IFMT(info.external_attr >> 16)
                if kind not in {0, stat.S_IFREG}:
                    raise PackagingError(f"MCPB contains a non-regular entry: {info.filename}")
                unpacked += info.file_size
            try:
                manifest = json.loads(archive.read("manifest.json"))
            except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
                raise PackagingError(f"MCPB manifest is invalid: {error}") from error
    except zipfile.BadZipFile as error:
        raise PackagingError(f"MCPB is not a valid ZIP archive: {error}") from error
    if not isinstance(manifest, dict):
        raise PackagingError("MCPB manifest root must be an object")
    if manifest.get("name") != "semantic-scala" or manifest.get("version") != RUNTIME_VERSION:
        raise PackagingError("MCPB identity differs from exact semantic-scala Alpha-3")
    if manifest.get("server", {}).get("type") != "binary":
        raise PackagingError("MCPB server must use the self-contained binary route")
    if len(names) > ENTRY_LIMIT:
        raise PackagingError("MCPB exceeds the 5,000-file upload contract")
    if unpacked > TARGET_UNPACKED_LIMIT:
        raise PackagingError("MCPB exceeds the 180 MiB reviewer headroom target")
    return {
        "path": MCPB_NAME,
        "version": RUNTIME_VERSION,
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
        "fileCount": len(names),
        "unpackedBytes": unpacked,
    }


def validate_tree(root: Path) -> None:
    if root.is_symlink() or not root.is_dir():
        raise PackagingError(f"plugin root is not a regular directory: {root}")
    for path in root.rglob("*"):
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise PackagingError(f"plugin contains an unsupported entry: {path}")


def inventory(root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()):
        if path.is_dir() or path.name == "package-manifest.json":
            continue
        result.append(
            {
                "path": path.relative_to(root).as_posix(),
                "bytes": path.stat().st_size,
                "mode": f"{stat.S_IMODE(path.stat().st_mode):04o}",
                "sha256": sha256(path),
            }
        )
    return result


def expected_manifest(root: Path) -> dict[str, Any]:
    files = inventory(root)
    embedded = inspect_mcpb(root / MCPB_NAME)
    result: dict[str, Any] = {
        "$schema": "semantic-scala.claude-embedded-package.v1",
        "pluginVersion": PLUGIN_VERSION,
        "runtimeVersion": RUNTIME_VERSION,
        "firstRunNetwork": False,
        "embeddedMcpb": embedded,
        "inventoryExcludesManifest": True,
        "fileCount": len(files) + 1,
        "unpackedBytes": 0,
        "largestFileBytes": max((item["bytes"] for item in files), default=0),
        "files": files,
    }
    inventoried = sum(item["bytes"] for item in files)
    while True:
        encoded = (json.dumps(result, indent=2, sort_keys=True) + "\n").encode()
        total = inventoried + len(encoded)
        largest = max(result["largestFileBytes"], len(encoded))
        if result["unpackedBytes"] == total and result["largestFileBytes"] == largest:
            return result
        result["unpackedBytes"] = total
        result["largestFileBytes"] = largest


def validate(root: Path) -> dict[str, Any]:
    validate_tree(root)
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
    if actual != GENERATED_FILES:
        raise PackagingError("plugin file set differs from the embedded-route allowlist")
    if (root / "skills/semantic-scala/SKILL.md").read_bytes() != CANONICAL_SKILL.read_bytes():
        raise PackagingError("plugin skill is not byte-identical to the canonical skill")
    plugin = load_json(root / ".claude-plugin/plugin.json")
    if (
        plugin.get("name") != "semantic-scala"
        or plugin.get("version") != PLUGIN_VERSION
        or plugin.get("mcpServers") != f"./{MCPB_NAME}"
    ):
        raise PackagingError("plugin identity or local MCPB route is invalid")
    if (root / ".mcp.json").exists() or (root / "bootstrap").exists():
        raise PackagingError("rejected thin bootstrap route is present")
    words = (root / "README.md").read_text(encoding="utf-8").split()
    if len(words) < 40:
        raise PackagingError("README has fewer than 40 words")
    manifest = load_json(root / "package-manifest.json")
    expected = expected_manifest(root)
    if manifest != expected:
        raise PackagingError("package manifest differs from the embedded payload")
    if expected["unpackedBytes"] > TARGET_UNPACKED_LIMIT:
        raise PackagingError("plugin exceeds the 180 MiB reviewer headroom target")
    if expected["fileCount"] > ENTRY_LIMIT:
        raise PackagingError("plugin exceeds the 5,000-file upload contract")
    if expected["largestFileBytes"] > UPLOAD_LIMIT:
        raise PackagingError("plugin contains a file over the 200 MB upload limit")
    return expected


def assemble(mcpb: Path, output: Path) -> dict[str, Any]:
    inspect_mcpb(mcpb)
    if output.exists() or output.is_symlink():
        raise PackagingError(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        for relative in (".claude-plugin/plugin.json", "README.md"):
            source = TEMPLATE / relative
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            target.chmod(0o644)
        shutil.copyfile(ROOT / "LICENSE", temporary / "LICENSE")
        (temporary / "LICENSE").chmod(0o644)
        shutil.copyfile(mcpb, temporary / MCPB_NAME)
        (temporary / MCPB_NAME).chmod(0o644)
        skill = temporary / "skills/semantic-scala/SKILL.md"
        skill.parent.mkdir(parents=True)
        shutil.copyfile(CANONICAL_SKILL, skill)
        skill.chmod(0o644)
        manifest = expected_manifest(temporary)
        (temporary / "package-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        (temporary / "package-manifest.json").chmod(0o644)
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
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in paths:
            info = zipfile.ZipInfo(
                path.relative_to(root).as_posix(),
                date_time=(1980, 1, 1, 0, 0, 0),
            )
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            info._compresslevel = 9
            archive.writestr(info, path.read_bytes())
    size = output.stat().st_size
    if size > UPLOAD_LIMIT:
        output.unlink(missing_ok=True)
        raise PackagingError("plugin archive exceeds the 200 MB upload limit")
    return {
        "archive": str(output),
        "archiveBytes": size,
        "archiveSha256": sha256(output),
        "fileCount": manifest["fileCount"],
        "unpackedBytes": manifest["unpackedBytes"],
        "embeddedMcpb": manifest["embeddedMcpb"],
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    assemble_parser = commands.add_parser("assemble")
    assemble_parser.add_argument("--mcpb", type=Path, required=True)
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
            value = assemble(args.mcpb.resolve(strict=True), args.output.absolute())
        elif args.command == "validate":
            value = validate(args.plugin_root.resolve(strict=True))
        else:
            value = pack(args.plugin_root.resolve(strict=True), args.output.absolute())
        print(json.dumps(value, indent=2, sort_keys=True))
        return 0
    except (OSError, PackagingError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
