#!/usr/bin/env python3
"""Contract tests for native plugin candidate assembly."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER_PATH = ROOT / "scripts/package-native-plugins.py"
SPEC = importlib.util.spec_from_file_location("package_native_plugins", PACKAGER_PATH)
assert SPEC is not None and SPEC.loader is not None
PACKAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGER)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class PackageNativePluginsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.archive = self.base / "alpha3.mcpb"
        self._write_archive(self.archive)
        self.contract = PACKAGER.source_contract()
        self.contract["mcpbBytes"] = self.archive.stat().st_size
        self.contract["mcpbSha256"] = PACKAGER.sha256(self.archive)
        self.contract["mcpbUrl"] = "https://example.invalid/semantic-scala-alpha3.mcpb"

    def _write_archive(self, output: Path) -> None:
        server = b"""#!/usr/bin/python3
import json
import sys

names = [
    "semantic_compile", "semantic_errors", "semantic_test",
    "semantic_effect_summary", "semantic_symbol_at", "semantic_symbols",
    "semantic_reconcile_symbol", "semantic_point_evidence",
]
for line in sys.stdin:
    request = json.loads(line)
    if "id" not in request:
        continue
    method = request.get("method")
    if method == "initialize":
        result = {"capabilities": {"tools": {}}}
    elif method == "tools/list":
        result = {"tools": [{"name": name, "inputSchema": {}} for name in names]}
    elif method == "tools/call":
        result = {
            "structuredContent": {
                "ok": True,
                "payload": {"schemaVersion": "semantic-scala.point-evidence-result.v2"},
            }
        }
    else:
        result = {}
    print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)
"""
        files: dict[str, tuple[bytes, int]] = {
            "LICENSES/semantic-scala-Apache-2.0.txt": (b"Apache-2.0\n", 0o644),
            "app/cli/classpath.txt": (b"app/cli/lib/classes\n", 0o644),
            "app/mcp/classpath.txt": (b"app/mcp/lib/classes\n", 0o644),
            "bin/semantic-scala": (b"#!/bin/sh\nexit 0\n", 0o755),
            "bin/semantic-scala-mcp": (server, 0o755),
            "runtime/bin/java": (b"fake-java\n", 0o755),
        }
        manifest = json.dumps(
            {"name": "semantic-scala", "version": "0.1.0-alpha.3"},
            sort_keys=True,
        ).encode()
        files["manifest.json"] = (manifest, 0o644)
        inventory = {
            "files": [
                {
                    "path": path,
                    "bytes": len(data),
                    "mode": f"{mode:04o}",
                    "sha256": digest(data),
                }
                for path, (data, mode) in sorted(files.items())
            ]
        }
        files["payload-inventory.json"] = (
            (json.dumps(inventory, indent=2, sort_keys=True) + "\n").encode(),
            0o644,
        )
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path, (data, mode) in sorted(files.items()):
                info = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)

    def test_both_candidates_are_deterministic_and_copy_the_canonical_skill(self) -> None:
        for platform in ("openai", "claude"):
            with self.subTest(platform=platform):
                first = self.base / f"{platform}-first"
                second = self.base / f"{platform}-second"
                first_result = PACKAGER.assemble(platform, self.archive, first, self.contract)
                second_result = PACKAGER.assemble(platform, self.archive, second, self.contract)
                self.assertEqual(first_result["contentSha256"], second_result["contentSha256"])
                self.assertEqual(
                    (first / "package-manifest.json").read_bytes(),
                    (second / "package-manifest.json").read_bytes(),
                )
                self.assertEqual(
                    PACKAGER.CANONICAL_SKILL.read_bytes(),
                    (first / "skills/semantic-scala/SKILL.md").read_bytes(),
                )
                self.assertTrue(os.access(first / "bin/semantic-scala-mcp", os.X_OK))
                validated = PACKAGER.validate_package(platform, first, self.contract)
                self.assertEqual(validated["platform"], platform)

    def test_relocated_smoke_uses_exact_eight_tools_without_project_mutation(self) -> None:
        for platform in ("openai", "claude"):
            with self.subTest(platform=platform):
                plugin = self.base / f"relocated {platform} plugin"
                data = self.base / f"relocated {platform} data"
                PACKAGER.assemble(platform, self.archive, plugin, self.contract)
                result = PACKAGER.smoke_package(platform, plugin, data, self.contract)
                self.assertEqual(result["toolNames"], PACKAGER.EXPECTED_TOOLS)
                self.assertEqual(result["helperProcessesRemaining"], 0)
                self.assertFalse(result["externalProjectModified"])
                self.assertTrue(result["relocatedPathContainedSpaces"])

    def test_project_snapshot_covers_directories_modes_and_all_file_names(self) -> None:
        project = self.base / "snapshot-project"
        nested = project / "nested"
        nested.mkdir(parents=True)
        nested.chmod(0o755)
        manifest = nested / "package-manifest.json"
        manifest.write_text("fixture\n", encoding="utf-8")
        manifest.chmod(0o640)

        snapshot = PACKAGER.project_snapshot(project)
        self.assertIn(
            {"path": "nested", "type": "directory", "mode": "0755"},
            snapshot,
        )
        self.assertIn(
            {
                "path": "nested/package-manifest.json",
                "type": "file",
                "bytes": 8,
                "mode": "0640",
                "sha256": digest(b"fixture\n"),
            },
            snapshot,
        )

        (project / "link").symlink_to(manifest)
        with self.assertRaisesRegex(PACKAGER.PackagingError, "symbolic link"):
            PACKAGER.project_snapshot(project)

    def test_assemble_rejects_wrong_source_digest(self) -> None:
        rejected = dict(self.contract)
        rejected["mcpbSha256"] = "0" * 64
        with self.assertRaisesRegex(PACKAGER.PackagingError, "identity mismatch"):
            PACKAGER.assemble("openai", self.archive, self.base / "rejected", rejected)

    def test_validate_rejects_noncanonical_skill(self) -> None:
        plugin = self.base / "tampered"
        PACKAGER.assemble("claude", self.archive, plugin, self.contract)
        (plugin / "skills/semantic-scala/SKILL.md").write_text("changed\n", encoding="utf-8")
        with self.assertRaisesRegex(PACKAGER.PackagingError, "byte-identical"):
            PACKAGER.validate_package("claude", plugin, self.contract)


if __name__ == "__main__":
    unittest.main()
