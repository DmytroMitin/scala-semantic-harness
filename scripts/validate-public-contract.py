#!/usr/bin/env python3

"""Reject private-controller chronology and machine-local references in tracked text."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


@dataclass(frozen=True)
class Finding:
    path: str
    line: int | None
    code: str

    def render(self) -> str:
        location = self.path if self.line is None else f"{self.path}:{self.line}"
        return f"{location}\t{self.code}"


PATH_PATTERNS = (
    (
        "NUMBERED_CONTROLLER_PATH",
        re.compile(r"(?:task|prompt|review)[-_]?\d{2,3}", re.IGNORECASE),
    ),
    (
        "PRIVATE_CONTROLLER_ROOT",
        re.compile(r"(?:^|/)(?:prompts|tasks|reviews)/\d", re.IGNORECASE),
    ),
)

LINE_PATTERNS = (
    (
        "NUMBERED_CONTROLLER_REFERENCE",
        re.compile(r"\b(?:Task|Prompt|Review)[ \t#:_-]*\d{2,3}\b", re.IGNORECASE),
    ),
    (
        "PRIVATE_CONTROLLER_ROOT",
        re.compile(r"(?:^|[/'\"`])(?:prompts|tasks|reviews)/\d", re.IGNORECASE),
    ),
    (
        "PRIVATE_HANDOFF_OR_REVIEW_IDENTIFIER",
        re.compile(r"\b(?:handoff|review)[-_ ]?\d{2,3}\b", re.IGNORECASE),
    ),
    (
        "PRIVATE_CONTROL_REPOSITORY",
        re.compile(r"scala-semantic-harness-control", re.IGNORECASE),
    ),
    (
        "USER_SPECIFIC_HOME_PATH",
        re.compile(r"/(?:home|Users)/[^/\s]+"),
    ),
    (
        "PRIVATE_SIBLING_REPOSITORY_PATH",
        re.compile(r"(?:^|/)projects_personal/"),
    ),
)

SKILL_LINE_PATTERNS = (
    (
        "SKILL_INTERNAL_DEVELOPMENT_SCOPE",
        re.compile(
            r"\b(?:do not|must not|never)\b.*\b(?:add|implement|create)\b.*"
            r"\b(?:commands?|MCP tools?|hooks?|services?|background work|IDE integrations?)\b",
            re.IGNORECASE,
        ),
    ),
    (
        "SKILL_PRODUCT_EVALUATION_PROTOCOL",
        re.compile(
            r"\b(?:controlled benchmark policy|spontaneous selection|incorrect patches|"
            r"pin (?:the )?(?:client|model|target revision)|benchmark used to justify)\b",
            re.IGNORECASE,
        ),
    ),
    (
        "SKILL_CONTROLLER_OR_HANDOFF",
        re.compile(r"\b(?:controller|handoff)\b", re.IGNORECASE),
    ),
)


def current_paths(root: Path) -> list[str]:
    result = subprocess.run(
        [
            "git",
            "-C",
            root,
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
        ],
        check=True,
        capture_output=True,
    )
    return sorted(path for path in result.stdout.decode("utf-8").split("\0") if path)


def read_tracked_text(path: Path) -> str | None:
    data = path.read_bytes()
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def is_public_contract_text(relative: str) -> bool:
    path = PurePosixPath(relative)
    name = path.name
    suffix = path.suffix.lower()

    if relative in {
        "README.md",
        "ROADMAP.md",
        "AGENTS.md",
        ".mcp.example.json",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "CHANGELOG.md",
        "RELEASE.md",
        "LICENSE",
    }:
        return True
    if relative.startswith("docs/") and suffix == ".md":
        return True
    if relative.startswith("benchmarks/") and suffix in {".md", ".tsv", ".json"}:
        return True
    if relative.startswith("skills/") or relative.startswith(".agents/skills/"):
        return name == "SKILL.md"
    if relative.startswith(".claude/skills/"):
        return name == "SKILL.md"
    if relative.startswith("scripts/benchmark/"):
        return suffix in {".md", ".sh", ".json", ""}
    if relative.startswith("packaging/agent-plugin/"):
        return suffix in {".md", ".json"}
    if relative.startswith("modules/") and name == "README.md":
        return True
    if relative.startswith("modules/cli/src/main/resources/semantic-scala/schemas/"):
        return suffix == ".json"
    return False


def is_public_surface_path(relative: str) -> bool:
    return is_public_contract_text(relative) or (
        relative.startswith("scripts/") and not relative.startswith("scripts/tests/")
    )


def scan(root: Path) -> tuple[list[Finding], int, int]:
    paths = current_paths(root)
    findings: list[Finding] = []
    text_count = 0

    for relative in paths:
        absolute = root / relative
        if not absolute.exists():
            continue

        if is_public_surface_path(relative):
            for code, pattern in PATH_PATTERNS:
                if pattern.search(relative):
                    findings.append(Finding(relative, None, code))

        if not is_public_contract_text(relative):
            continue

        text = read_tracked_text(absolute)
        if text is None:
            continue
        text_count += 1
        for line_number, line in enumerate(text.splitlines(), start=1):
            for code, pattern in LINE_PATTERNS:
                if pattern.search(line):
                    findings.append(Finding(relative, line_number, code))
            if relative == "skills/semantic-scala/SKILL.md":
                for code, pattern in SKILL_LINE_PATTERNS:
                    if pattern.search(line):
                        findings.append(Finding(relative, line_number, code))

    return sorted(findings, key=lambda finding: (finding.path, finding.line or 0, finding.code)), len(paths), text_count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()

    try:
        findings, current_path_count, text_count = scan(root)
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError) as error:
        print(f"PUBLIC_CONTRACT_SCAN_ERROR {error}", file=sys.stderr)
        return 2

    if findings:
        print(
            f"PUBLIC_CONTRACT_SCAN_FAIL findings={len(findings)} "
            f"currentPaths={current_path_count} text={text_count}",
            file=sys.stderr,
        )
        for finding in findings:
            print(finding.render(), file=sys.stderr)
        return 1

    print(
        f"PUBLIC_CONTRACT_SCAN_PASS currentPaths={current_path_count} "
        f"text={text_count}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
