#!/usr/bin/env python3
"""Fail-closed local Maven release-shape and resolver provenance validator."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree


GROUP = "com.github.dmytromitin"
GROUP_PATH = Path("com/github/dmytromitin")
LEGACY_GROUP_PATH = Path("io/github/dmytromitin")
MODULE_EDGES = {
    "semantic-harness-core_3": [],
    "semantic-harness-sbt-runner_3": ["semantic-harness-core_3"],
    "semantic-harness-semanticdb-reader_3": ["semantic-harness-core_3"],
    "semantic-harness-presentation-compiler_3": ["semantic-harness-core_3"],
    "semantic-harness-semantic-reconciliation_3": [
        "semantic-harness-core_3",
        "semantic-harness-semanticdb-reader_3",
        "semantic-harness-presentation-compiler_3",
    ],
    "semantic-harness-fp-analyzers_3": ["semantic-harness-core_3"],
    "semantic-scala-cli_3": [
        "semantic-harness-core_3",
        "semantic-harness-sbt-runner_3",
        "semantic-harness-semanticdb-reader_3",
        "semantic-harness-presentation-compiler_3",
        "semantic-harness-semantic-reconciliation_3",
        "semantic-harness-fp-analyzers_3",
    ],
    "semantic-harness-mcp-server_3": [
        "semantic-harness-core_3",
        "semantic-harness-fp-analyzers_3",
        "semantic-harness-presentation-compiler_3",
        "semantic-harness-semanticdb-reader_3",
        "semantic-harness-semantic-reconciliation_3",
    ],
}
PRIMARY_SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
CHECKSUMS = ("sha256", "sha512")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class CandidateError(ValueError):
    pass


def sha(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def text(root: ElementTree.Element, expression: str) -> str:
    value = root.findtext(expression, default="", namespaces=NS)
    return value.strip()


def parse_pom(path: Path) -> ElementTree.Element:
    try:
        return ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise CandidateError(f"invalid POM: {path.name}") from error


def validate_metadata(root: ElementTree.Element, module: str, version: str) -> None:
    required = {
        "m:groupId": GROUP,
        "m:artifactId": module,
        "m:version": version,
    }
    for expression, expected in required.items():
        if text(root, expression) != expected:
            raise CandidateError(f"POM identity mismatch for {module}: {expression}")
    for expression in (
        "m:description",
        "m:url",
        "m:scm/m:url",
        "m:scm/m:connection",
        "m:developers/m:developer/m:id",
        "m:developers/m:developer/m:name",
        "m:developers/m:developer/m:url",
    ):
        if not text(root, expression):
            raise CandidateError(f"missing Central metadata for {module}: {expression}")
    licenses = [
        (text(license, "m:name"), text(license, "m:url"))
        for license in root.findall("m:licenses/m:license", NS)
    ]
    if not any(name == "Apache-2.0" and "apache.org/licenses/LICENSE-2.0" in url for name, url in licenses):
        raise CandidateError(f"missing Apache-2.0 metadata for {module}")
    internal = sorted(
        text(dependency, "m:artifactId")
        for dependency in root.findall("m:dependencies/m:dependency", NS)
        if text(dependency, "m:groupId") == GROUP
    )
    if internal != sorted(MODULE_EDGES[module]):
        raise CandidateError(f"internal dependency DAG mismatch for {module}")


def repository_report(repository: Path, version: str) -> dict[str, object]:
    if repository.is_symlink() or not repository.is_dir():
        raise CandidateError("repository must be a regular directory")
    legacy_group_root = repository / LEGACY_GROUP_PATH
    if legacy_group_root.exists() or legacy_group_root.is_symlink():
        raise CandidateError("legacy Maven namespace present: io.github.dmytromitin")
    group_root = repository / GROUP_PATH
    if group_root.is_symlink() or not group_root.is_dir():
        raise CandidateError("candidate group is missing")
    found_modules = sorted(path.name for path in group_root.iterdir() if path.is_dir())
    expected_modules = sorted(MODULE_EDGES)
    if found_modules != expected_modules:
        raise CandidateError("publishable module allowlist mismatch")
    if any(name in found_modules for name in ("scala-semantic-harness_3", "semantic-harness-benchmark_3")):
        raise CandidateError("root or benchmark publication detected")

    artifacts: list[dict[str, str]] = []
    for module in MODULE_EDGES:
        version_root = group_root / module / version
        if version_root.is_symlink() or not version_root.is_dir():
            raise CandidateError(f"missing exact version directory for {module}")
        prefix = f"{module}-{version}"
        for suffix in PRIMARY_SUFFIXES:
            primary = version_root / f"{prefix}{suffix}"
            if primary.is_symlink() or not primary.is_file() or primary.stat().st_size == 0:
                raise CandidateError(f"missing primary artifact: {primary.name}")
            signature = Path(f"{primary}.asc")
            if signature.is_symlink() or not signature.is_file() or signature.stat().st_size == 0:
                raise CandidateError(f"missing signature sidecar: {primary.name}")
            for algorithm in CHECKSUMS:
                sidecar = Path(f"{primary}.{algorithm}")
                if sidecar.is_symlink() or not sidecar.is_file():
                    raise CandidateError(f"missing checksum sidecar: {primary.name}.{algorithm}")
                recorded = sidecar.read_text(encoding="utf-8").strip().split()[0]
                if recorded != sha(primary, algorithm):
                    raise CandidateError(f"checksum mismatch: {primary.name}.{algorithm}")
            artifacts.append(
                {
                    "gav": f"{GROUP}:{module}:{version}",
                    "file": primary.name,
                    "sha256": sha(primary, "sha256"),
                    "sha512": sha(primary, "sha512"),
                }
            )
        validate_metadata(parse_pom(version_root / f"{prefix}.pom"), module, version)
    return {
        "schemaVersion": "semantic-scala.local-maven-candidate.v1",
        "group": GROUP,
        "version": version,
        "moduleCount": len(MODULE_EDGES),
        "modules": MODULE_EDGES,
        "legacyNamespaceAbsent": True,
        "artifacts": sorted(artifacts, key=lambda item: (item["gav"], item["file"])),
        "releaseShape": {
            "sources": True,
            "documentation": True,
            "syntheticLocalSignatures": True,
            "sha256": True,
            "sha512": True,
            "externalPublication": False,
        },
    }


def cache_report(cache: Path) -> dict[str, object]:
    if cache.is_symlink() or not cache.is_dir():
        raise CandidateError("Coursier cache must be a regular directory")
    components: list[dict[str, object]] = []
    for jar in sorted(cache.rglob("*.jar")):
        if jar.name.endswith(("-sources.jar", "-javadoc.jar")) or jar.is_symlink():
            continue
        poms = list(jar.parent.glob("*.pom"))
        if len(poms) != 1:
            raise CandidateError(f"runtime jar has ambiguous or missing POM: {jar.name}")
        root = parse_pom(poms[0])
        group = text(root, "m:groupId") or text(root, "m:parent/m:groupId")
        artifact = text(root, "m:artifactId")
        version = text(root, "m:version") or text(root, "m:parent/m:version")
        licenses = [
            {"name": text(license, "m:name"), "url": text(license, "m:url")}
            for license in root.findall("m:licenses/m:license", NS)
        ]
        license_text = " ".join(
            str(value).lower() for license in licenses for value in license.values()
        )
        with zipfile.ZipFile(jar) as archive:
            notice_files = sorted(
                name for name in archive.namelist() if "META-INF/NOTICE" in name.upper()
            )
        relationships = []
        for dependency in root.findall("m:dependencies/m:dependency", NS):
            relationships.append(
                {
                    "to": ":".join(
                        [
                            text(dependency, "m:groupId"),
                            text(dependency, "m:artifactId"),
                            text(dependency, "m:version"),
                        ]
                    ),
                    "declaredScope": text(dependency, "m:scope") or "compile",
                }
            )
        components.append(
            {
                "gav": f"{group}:{artifact}:{version}",
                "sha256": sha(jar, "sha256"),
                "runtimeRelevant": True,
                "origin": "resolver-fetched; project does not redistribute this jar in a bundle",
                "licenses": licenses,
                "flags": {
                    "multipleLicenseMetadata": len(licenses) > 1,
                    "eplFamily": "eclipse public license" in license_text or "epl" in license_text,
                    "missingOrAmbiguousLicenseMetadata": not licenses
                    or any(not license["name"] for license in licenses),
                    "noticeOrAttributionReview": bool(notice_files),
                },
                "noticeFiles": notice_files,
                "declaredRelationships": sorted(relationships, key=lambda item: item["to"]),
            }
        )
    if not components:
        raise CandidateError("no runtime jars found in the isolated Coursier cache")
    return {
        "schemaVersion": "semantic-scala.resolver-runtime-inventory.v1",
        "componentCount": len(components),
        "components": sorted(components, key=lambda item: item["gav"]),
        "reviewBoundary": (
            "POM metadata and packaged NOTICE evidence are a prepublication review input, "
            "not a legal conclusion; resolver-mediated use does not eliminate obligations."
        ),
    }


def write_report(report: dict[str, object], output: Path | None) -> None:
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if output is None:
        print(rendered, end="")
    else:
        if output.exists() or output.is_symlink():
            raise CandidateError("output report must not already exist")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")
        print(f"validated {report['schemaVersion']}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    repository = commands.add_parser("repository")
    repository.add_argument("--repository", required=True, type=Path)
    repository.add_argument("--version", required=True)
    repository.add_argument("--output", type=Path)
    cache = commands.add_parser("cache")
    cache.add_argument("--cache", required=True, type=Path)
    cache.add_argument("--output", type=Path)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        report = (
            repository_report(args.repository, args.version)
            if args.command == "repository"
            else cache_report(args.cache)
        )
        write_report(report, args.output)
        return 0
    except CandidateError as error:
        print(f"Maven candidate validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
