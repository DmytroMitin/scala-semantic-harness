#!/usr/bin/env python3
"""Build and validate the deterministic Linux x86_64 semantic-scala MCPB."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PACKAGING = ROOT / "packaging/mcpb/semantic-scala"
MANIFEST = PACKAGING / "manifest.json"
LAUNCHER_SOURCE = PACKAGING / "launcher.c"
VERSION = "0.1.0-alpha.3"
TOOLS = [
    "semantic_compile",
    "semantic_errors",
    "semantic_test",
    "semantic_effect_summary",
    "semantic_symbol_at",
    "semantic_symbols",
    "semantic_reconcile_symbol",
    "semantic_point_evidence",
]
EXPECTED = {
    "semantic-scala": "semantic.harness.cli.Main",
    "semantic-scala-mcp": "semantic.harness.mcp.Main",
}
RUNTIME_NOTICES = [
    "ADDITIONAL_LICENSE_INFO",
    "ASSEMBLY_EXCEPTION",
    "LICENSE",
    "README.md",
    "THIRD_PARTY_README",
]


class PackageFailure(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_output(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def no_symlinks(root: Path) -> None:
    if root.is_symlink():
        raise PackageFailure(f"symlink is not permitted: {root}")
    for path in root.rglob("*"):
        if path.is_symlink():
            raise PackageFailure(f"symlink is not permitted: {path}")


def materialize_runtime_links(runtime: Path) -> dict[str, int]:
    """Replace jlink's package-internal legal-notice links with regular files."""
    root = runtime.resolve(strict=True)
    links = sorted((path for path in runtime.rglob("*") if path.is_symlink()), key=lambda path: path.as_posix())
    for path in links:
        try:
            target = path.resolve(strict=True)
            target.relative_to(root)
        except (OSError, ValueError) as error:
            raise PackageFailure(f"runtime link escapes or is broken: {path}") from error
        if not target.is_file():
            raise PackageFailure(f"runtime link does not target a regular file: {path}")
        data = target.read_bytes()
        mode = stat.S_IMODE(target.stat().st_mode)
        path.unlink()
        path.write_bytes(data)
        path.chmod(mode)
    return {"materializedLinks": len(links)}


def normalize_modes(root: Path) -> dict[str, int]:
    no_symlinks(root)
    directories = 0
    executables = 0
    data_files = 0
    root.chmod(0o755)
    directories += 1
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_dir():
            path.chmod(0o755)
            directories += 1
        elif path.is_file():
            if stat.S_IMODE(path.stat().st_mode) & 0o111:
                path.chmod(0o755)
                executables += 1
            else:
                path.chmod(0o644)
                data_files += 1
        else:
            raise PackageFailure(f"unsupported filesystem entry while normalizing modes: {path}")
    return {"directories": directories, "executables": executables, "dataFiles": data_files}


def inspect_stage(stage: Path, launcher: str, expected_main: str) -> dict[str, object]:
    launcher_path = stage / "bin" / launcher
    if not launcher_path.is_file():
        raise PackageFailure(f"stage launcher is missing: {launcher_path}")
    no_symlinks(stage)
    text = launcher_path.read_text(encoding="utf-8")
    match = re.search(r'^CLASSPATH="([^"]+)"$', text, re.MULTILINE)
    if match is None:
        raise PackageFailure(f"cannot derive classpath from stage launcher: {launcher_path}")
    entries: list[str] = []
    prefix = "$APP_HOME/"
    for raw in match.group(1).split(":"):
        if not raw.startswith(prefix):
            raise PackageFailure(f"stage classpath is not app-relative: {raw}")
        relative = raw[len(prefix) :]
        path = stage / relative
        if not path.exists():
            raise PackageFailure(f"stage classpath entry is missing: {relative}")
        entries.append(relative)
    main_matches = re.findall(r'exec java[^\n]*-cp "\$CLASSPATH" ([A-Za-z0-9_.$]+) "\$@"', text)
    if len(main_matches) != 1:
        raise PackageFailure(f"cannot derive one main class from stage launcher: {launcher_path}")
    main_class = main_matches[0]
    if main_class != expected_main:
        raise PackageFailure(f"unexpected main class {main_class}; expected {expected_main}")
    return {
        "launcher": launcher,
        "mainClass": main_class,
        "classpath": entries,
        "launcherSha256": sha256(launcher_path),
    }


def run_checked(command: list[str]) -> None:
    try:
        subprocess.run(
            command,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except FileNotFoundError as error:
        raise PackageFailure(f"required command is unavailable: {command[0]}") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        suffix = f": {detail}" if detail else ""
        raise PackageFailure(
            f"command failed with exit {error.returncode}: {' '.join(command)}{suffix}"
        ) from error


def copy_stage(stage: Path, target: Path, contract: dict[str, object]) -> None:
    library = stage / "lib"
    if not library.is_dir():
        raise PackageFailure(f"stage library directory is missing: {library}")
    shutil.copytree(library, target / "lib", symlinks=False)
    entries = contract["classpath"]
    assert isinstance(entries, list)
    (target / "classpath.txt").write_text(
        "".join(f"{target.relative_to(target.parents[1]).as_posix()}/{entry}\n" for entry in entries),
        encoding="utf-8",
    )


def compile_launcher(output: Path, main_class: str, classpath_file: str) -> None:
    run_checked(
        [
            "cc",
            "-static",
            "-O2",
            "-s",
            "-Wl,--build-id=none",
            f'-DMAIN_CLASS="{main_class}"',
            f'-DCLASSPATH_FILE="{classpath_file}"',
            str(LAUNCHER_SOURCE),
            "-o",
            str(output),
        ]
    )
    output.chmod(0o755)


def inventory(root: Path, excluded: set[str] | None = None) -> list[dict[str, object]]:
    excluded = excluded or set()
    result: list[dict[str, object]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        if relative in excluded or path.is_dir():
            continue
        if path.is_symlink() or not path.is_file():
            raise PackageFailure(f"payload contains unsupported filesystem entry: {relative}")
        result.append(
            {
                "path": relative,
                "bytes": path.stat().st_size,
                "mode": format(stat.S_IMODE(path.stat().st_mode), "04o"),
                "sha256": sha256(path),
            }
        )
    return result


def assemble(cli_stage: Path, mcp_stage: Path, jdk_home: Path, output: Path) -> dict[str, object]:
    if output.exists():
        raise PackageFailure(f"output already exists: {output}")
    java = jdk_home / "bin/java"
    jlink = jdk_home / "bin/jlink"
    if not java.is_file() or not jlink.is_file():
        raise PackageFailure(f"JDK home must contain bin/java and bin/jlink: {jdk_home}")
    cli_contract = inspect_stage(cli_stage, "semantic-scala", EXPECTED["semantic-scala"])
    mcp_contract = inspect_stage(mcp_stage, "semantic-scala-mcp", EXPECTED["semantic-scala-mcp"])

    output.mkdir(parents=True)
    shutil.copyfile(MANIFEST, output / "manifest.json")
    (output / "bin").mkdir()
    (output / "app/cli").mkdir(parents=True)
    (output / "app/mcp").mkdir(parents=True)
    (output / "LICENSES/corretto-21").mkdir(parents=True)
    copy_stage(cli_stage, output / "app/cli", cli_contract)
    copy_stage(mcp_stage, output / "app/mcp", mcp_contract)

    run_checked(
        [
            str(jlink),
            "--add-modules",
            "ALL-MODULE-PATH",
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=zip-6",
            "--output",
            str(output / "runtime"),
        ]
    )
    materialize_runtime_links(output / "runtime")
    for notice in RUNTIME_NOTICES:
        source = jdk_home / notice
        if source.is_file():
            shutil.copyfile(source, output / "LICENSES/corretto-21" / notice)
    if not (output / "LICENSES/corretto-21/LICENSE").is_file():
        raise PackageFailure("JDK LICENSE notice was not found")
    shutil.copyfile(ROOT / "LICENSE", output / "LICENSES/semantic-scala-Apache-2.0.txt")

    compile_launcher(
        output / "bin/semantic-scala",
        EXPECTED["semantic-scala"],
        "app/cli/classpath.txt",
    )
    compile_launcher(
        output / "bin/semantic-scala-mcp",
        EXPECTED["semantic-scala-mcp"],
        "app/mcp/classpath.txt",
    )

    normalize_modes(output)

    payload = inventory(output, {"payload-inventory.json"})
    inventory_path = output / "payload-inventory.json"
    inventory_path.write_text(
        json.dumps(
            {
                "schemaVersion": "semantic-scala.mcpb-payload-inventory.v1",
                "version": VERSION,
                "platform": "linux-x86_64",
                "files": payload,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    inventory_path.chmod(0o644)
    validated = validate(output)
    return {
        "packageRoot": str(output),
        "fileCount": len(inventory(output)),
        "payloadBytes": sum(item["bytes"] for item in inventory(output)),
        "cliStage": cli_contract,
        "mcpStage": mcp_contract,
        "validation": validated,
    }


def validate(package_root: Path) -> dict[str, object]:
    no_symlinks(package_root)
    for path in package_root.rglob("*"):
        if path.is_file() and stat.S_IMODE(path.stat().st_mode) & 0o022:
            raise PackageFailure(f"payload file must not be group/world writable: {path.relative_to(package_root)}")
    manifest_path = package_root / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PackageFailure(f"invalid manifest.json: {error}") from error
    server = manifest.get("server", {})
    config = server.get("mcp_config", {}) if isinstance(server, dict) else {}
    tools = manifest.get("tools", [])
    names = [tool.get("name") for tool in tools if isinstance(tool, dict)]
    checks = {
        "manifest_version": "0.3",
        "version": VERSION,
        "server_type": "binary",
        "entry_point": "bin/semantic-scala-mcp",
        "command": "${__dirname}/bin/semantic-scala-mcp",
        "args": ["--cli", "${__dirname}/bin/semantic-scala"],
        "platforms": ["linux"],
    }
    actual = {
        "manifest_version": manifest.get("manifest_version"),
        "version": manifest.get("version"),
        "server_type": server.get("type") if isinstance(server, dict) else None,
        "entry_point": server.get("entry_point") if isinstance(server, dict) else None,
        "command": config.get("command") if isinstance(config, dict) else None,
        "args": config.get("args") if isinstance(config, dict) else None,
        "platforms": manifest.get("compatibility", {}).get("platforms"),
    }
    for key, expected in checks.items():
        if actual[key] != expected:
            raise PackageFailure(f"manifest {key} must be {expected!r}, got {actual[key]!r}")
    if names != TOOLS:
        raise PackageFailure(f"manifest must declare the exact eight tools in order: {names}")
    for relative in [
        "bin/semantic-scala",
        "bin/semantic-scala-mcp",
        "runtime/bin/java",
    ]:
        path = package_root / relative
        if not path.is_file() or not os.access(path, os.X_OK):
            raise PackageFailure(f"required executable is missing or not executable: {relative}")
    for relative in [
        "app/cli/classpath.txt",
        "app/mcp/classpath.txt",
        "LICENSES/semantic-scala-Apache-2.0.txt",
        "payload-inventory.json",
    ]:
        if not (package_root / relative).is_file():
            raise PackageFailure(f"required package file is missing: {relative}")
    return {
        "manifestVersion": manifest["manifest_version"],
        "version": manifest["version"],
        "serverType": server["type"],
        "platforms": manifest["compatibility"]["platforms"],
        "toolNames": names,
        "validationLevel": "repository-contract",
    }


def pack(package_root: Path, output: Path) -> dict[str, object]:
    validate(package_root)
    no_symlinks(package_root)
    if output.exists():
        output.unlink()
    output.parent.mkdir(parents=True, exist_ok=True)
    paths = [path for path in package_root.rglob("*") if path.is_file()]
    paths.sort(key=lambda path: path.relative_to(package_root).as_posix())
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in paths:
            relative = path.relative_to(package_root).as_posix()
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = stat.S_IMODE(path.stat().st_mode)
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            info._compresslevel = 9
            archive.writestr(info, path.read_bytes())
    return {
        "artifact": str(output),
        "bytes": output.stat().st_size,
        "files": len(paths),
        "sha256": sha256(output),
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    inspect = commands.add_parser("inspect-stage")
    inspect.add_argument("--stage", type=Path, required=True)
    inspect.add_argument("--launcher", required=True)
    inspect.add_argument("--expected-main", required=True)
    assemble_parser = commands.add_parser("assemble")
    assemble_parser.add_argument("--cli-stage", type=Path, required=True)
    assemble_parser.add_argument("--mcp-stage", type=Path, required=True)
    assemble_parser.add_argument("--jdk-home", type=Path, required=True)
    assemble_parser.add_argument("--output", type=Path, required=True)
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--package-root", type=Path, required=True)
    pack_parser = commands.add_parser("pack")
    pack_parser.add_argument("--package-root", type=Path, required=True)
    pack_parser.add_argument("--output", type=Path, required=True)
    links_parser = commands.add_parser("materialize-runtime-links")
    links_parser.add_argument("--runtime", type=Path, required=True)
    modes_parser = commands.add_parser("normalize-modes")
    modes_parser.add_argument("--root", type=Path, required=True)
    return result


def run() -> None:
    arguments = parser().parse_args()
    if arguments.command == "inspect-stage":
        value = inspect_stage(arguments.stage, arguments.launcher, arguments.expected_main)
    elif arguments.command == "assemble":
        value = assemble(arguments.cli_stage, arguments.mcp_stage, arguments.jdk_home, arguments.output)
    elif arguments.command == "validate":
        value = validate(arguments.package_root)
    elif arguments.command == "pack":
        value = pack(arguments.package_root, arguments.output)
    elif arguments.command == "materialize-runtime-links":
        value = materialize_runtime_links(arguments.runtime)
    elif arguments.command == "normalize-modes":
        value = normalize_modes(arguments.root)
    else:
        raise AssertionError(arguments.command)
    json_output(value)


if __name__ == "__main__":
    try:
        run()
    except PackageFailure as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
