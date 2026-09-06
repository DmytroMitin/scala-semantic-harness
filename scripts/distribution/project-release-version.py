#!/usr/bin/env python3
"""Read one exact non-SNAPSHOT release version from a project's build.sbt."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


VERSION_ASSIGNMENT = re.compile(
    r'^\s*ThisBuild\s*/\s*version\s*:=\s*"([^"]+)"\s*$'
)
EXACT_VERSION = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")


class VersionError(ValueError):
    pass


def project_release_version(source: Path) -> str:
    build = source / "build.sbt"
    if source.is_symlink() or not source.is_dir() or build.is_symlink() or not build.is_file():
        raise VersionError("source must contain a regular build.sbt")
    matches = [
        match.group(1)
        for line in build.read_text(encoding="utf-8").splitlines()
        if (match := VERSION_ASSIGNMENT.fullmatch(line)) is not None
    ]
    if len(matches) != 1:
        raise VersionError("build.sbt must contain exactly one literal ThisBuild / version assignment")
    version = matches[0]
    lowered = version.lower()
    if (
        not EXACT_VERSION.fullmatch(version)
        or "snapshot" in lowered
        or lowered in {"latest", "latest.release", "release"}
    ):
        raise VersionError("build.sbt must select an exact non-SNAPSHOT release version")
    return version


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(project_release_version(args.source))
        return 0
    except (OSError, UnicodeError, VersionError) as error:
        print(f"Release version validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
