#!/usr/bin/env python3
"""Generate and validate the exact-version semantic-scala Coursier channel."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_ROOT = ROOT / "distribution/coursier/templates"
APPLICATIONS = {
    "semantic-scala": {
        "artifact": "semantic-scala-cli_3",
        "mainClass": "semantic.harness.cli.Main",
    },
    "semantic-scala-mcp": {
        "artifact": "semantic-harness-mcp-server_3",
        "mainClass": "semantic.harness.mcp.Main",
    },
}
ALLOWED_KEYS = {"repositories", "dependencies", "mainClass", "launcherType", "javaOptions"}
EXACT_VERSION = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")


class ChannelError(ValueError):
    pass


def load_json(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ChannelError(f"required descriptor is not a regular file: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ChannelError(f"invalid descriptor JSON: {path.name}") from error
    if not isinstance(value, dict):
        raise ChannelError(f"descriptor must be an object: {path.name}")
    return value


def validate_version(version: str) -> None:
    lowered = version.lower()
    if (
        not EXACT_VERSION.fullmatch(version)
        or "snapshot" in lowered
        or "latest" in lowered
        or "release" == lowered
    ):
        raise ChannelError("an exact non-SNAPSHOT product version is required")


def expected_descriptor(name: str, version: str, repository: str) -> dict[str, object]:
    application = APPLICATIONS[name]
    repositories = ["central"] if repository == "central" else [repository, "central"]
    return {
        "repositories": repositories,
        "dependencies": [
            f"com.github.dmytromitin:{application['artifact']}:{version}"
        ],
        "mainClass": application["mainClass"],
        "launcherType": "bootstrap",
        "javaOptions": ["-XX:+PerfDisableSharedMem"],
    }


def validate_templates() -> None:
    actual_names = sorted(path.name for path in TEMPLATE_ROOT.glob("*.json"))
    expected_names = sorted(f"{name}.json" for name in APPLICATIONS)
    if actual_names != expected_names:
        raise ChannelError("template allowlist must contain exactly the two applications")
    for name, application in APPLICATIONS.items():
        descriptor = load_json(TEMPLATE_ROOT / f"{name}.json")
        if set(descriptor) != ALLOWED_KEYS:
            raise ChannelError(f"unexpected descriptor fields: {name}")
        expected = expected_descriptor(name, "${VERSION}", "${REPOSITORY}")
        if descriptor != expected:
            raise ChannelError(f"template contract mismatch: {name}")
        dependency = descriptor["dependencies"][0]
        if f":{application['artifact']}:" not in dependency:
            raise ChannelError(f"artifact mismatch: {name}")


def validate_generated(channel: Path, version: str, repository: str) -> None:
    validate_version(version)
    if not repository or "${" in repository:
        raise ChannelError("a concrete repository is required")
    if channel.is_symlink() or not channel.is_dir():
        raise ChannelError("channel must be a regular directory")
    actual_names = sorted(path.name for path in channel.iterdir())
    expected_names = sorted(f"{name}.json" for name in APPLICATIONS)
    if actual_names != expected_names:
        raise ChannelError("generated channel must contain exactly the two descriptors")
    for name in APPLICATIONS:
        actual = load_json(channel / f"{name}.json")
        if actual != expected_descriptor(name, version, repository):
            raise ChannelError(f"generated descriptor mismatch: {name}")


def generate(output: Path, version: str, repository: str) -> None:
    validate_templates()
    validate_version(version)
    if not repository or "${" in repository:
        raise ChannelError("a concrete repository is required")
    if output.exists() and (output.is_symlink() or not output.is_dir() or any(output.iterdir())):
        raise ChannelError("output must be absent or an empty regular directory")
    output.mkdir(parents=True, exist_ok=True)
    for name in APPLICATIONS:
        destination = output / f"{name}.json"
        destination.write_text(
            json.dumps(expected_descriptor(name, version, repository), indent=2, sort_keys=True)
            + "\n",
            encoding="utf-8",
        )
    validate_generated(output, version, repository)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subcommands = result.add_subparsers(dest="command", required=True)
    subcommands.add_parser("validate-templates")
    generate_parser = subcommands.add_parser("generate")
    generate_parser.add_argument("--version", required=True)
    generate_parser.add_argument("--repository", required=True)
    generate_parser.add_argument("--output", required=True, type=Path)
    validate_parser = subcommands.add_parser("validate-generated")
    validate_parser.add_argument("--version", required=True)
    validate_parser.add_argument("--repository", required=True)
    validate_parser.add_argument("--channel", required=True, type=Path)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "validate-templates":
            validate_templates()
        elif args.command == "generate":
            generate(args.output, args.version, args.repository)
        else:
            validate_generated(args.channel, args.version, args.repository)
        print("validated Coursier applications: semantic-scala, semantic-scala-mcp")
        return 0
    except ChannelError as error:
        print(f"Coursier channel validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
