#!/usr/bin/env python3
"""Validate the bounded public benchmark evidence manifest."""

from __future__ import annotations

import csv
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "benchmarks/public-evidence-manifest.tsv"
FORBIDDEN = {
    "user-home": re.compile(r"/" + r"home/[^/\s]+/"),
    "control-repository": re.compile(r"scala-semantic-harness" + r"-control"),
    "private-review-root": re.compile(r"(?:^|[/'\"`])reviews/\d", re.MULTILINE),
    "private-task-root": re.compile(r"(?:^|[/'\"`])tasks/\d", re.MULTILINE),
    "numbered-prompt-root": re.compile(r"(?:^|[/'\"`])prompts/\d", re.MULTILINE),
}


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def fail(message: str) -> None:
    print(f"public-evidence validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


with MANIFEST.open(newline="", encoding="utf-8") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))

expected_header = {"path", "bytes", "sha256", "role"}
if not rows or set(rows[0]) != expected_header:
    fail("manifest header or rows are missing")

seen: set[str] = set()
for row in rows:
    relative = row["path"]
    if relative in seen:
        fail(f"duplicate path: {relative}")
    seen.add(relative)
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        fail(f"unsafe path: {relative}")
    path = ROOT / relative
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular file: {relative}")
    if path.stat().st_size != int(row["bytes"]):
        fail(f"byte count mismatch: {relative}")
    if digest(path) != row["sha256"]:
        fail(f"SHA-256 mismatch: {relative}")
    if not row["role"]:
        fail(f"missing role: {relative}")
    if path.suffix in {".md", ".json", ".tsv", ".py", ".sh"} or path.name == "semantic-scala-packaged":
        text = path.read_text(encoding="utf-8")
        for label, pattern in FORBIDDEN.items():
            if pattern.search(text):
                fail(f"{label} reference in {relative}")

required = {
    "benchmarks/README.md",
    "benchmarks/results/v0-screening-summary.json",
    "scripts/benchmark/README.md",
    "scripts/benchmark/semantic-scala-packaged",
}
missing = required - seen
if missing:
    fail(f"required paths absent: {', '.join(sorted(missing))}")

print(f"public-evidence validation passed: {len(rows)} files")
