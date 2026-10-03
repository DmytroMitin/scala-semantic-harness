#!/usr/bin/env python3
"""Contract tests for the reviewer-compliant embedded MCPB Claude plugin."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER_PATH = ROOT / "scripts/package-claude-embedded-plugin.py"


def load_packager():
    spec = importlib.util.spec_from_file_location("package_claude_embedded_plugin", PACKAGER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load embedded plugin packager")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PackageClaudeEmbeddedPluginTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.mcpb = self.base / "semantic-scala.mcpb"
        self._write_mcpb(self.mcpb)

    def _write_mcpb(self, output: Path) -> None:
        files = {
            "manifest.json": json.dumps(
                {
                    "manifest_version": "0.3",
                    "name": "semantic-scala",
                    "version": "0.1.0-alpha.3",
                    "server": {"type": "binary", "entry_point": "bin/semantic-scala-mcp"},
                },
                sort_keys=True,
            ).encode(),
            "bin/semantic-scala": b"cli",
            "bin/semantic-scala-mcp": b"mcp",
            "runtime/bin/java": b"java",
        }
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(files.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)

    def test_assemble_embeds_exact_mcpb_without_bootstrap_or_first_run_network(self) -> None:
        packager = load_packager()
        output = self.base / "plugin"
        result = packager.assemble(self.mcpb, output)

        plugin = json.loads((output / ".claude-plugin/plugin.json").read_text(encoding="utf-8"))
        self.assertEqual(plugin["version"], "0.1.0-alpha.3.1")
        self.assertEqual(plugin["mcpServers"], "./semantic-scala.mcpb")
        self.assertEqual(
            (output / "semantic-scala.mcpb").read_bytes(),
            self.mcpb.read_bytes(),
        )
        self.assertEqual(
            (output / "skills/semantic-scala/SKILL.md").read_bytes(),
            (ROOT / "skills/semantic-scala/SKILL.md").read_bytes(),
        )
        self.assertFalse((output / ".mcp.json").exists())
        self.assertFalse((output / "bootstrap").exists())
        self.assertFalse(result["firstRunNetwork"])
        self.assertEqual(result["embeddedMcpb"]["version"], "0.1.0-alpha.3")
        self.assertEqual(result["embeddedMcpb"]["bytes"], self.mcpb.stat().st_size)

    def test_pack_is_deterministic_and_within_upload_contract(self) -> None:
        packager = load_packager()
        plugin = self.base / "plugin"
        packager.assemble(self.mcpb, plugin)
        first = self.base / "first.zip"
        second = self.base / "second.zip"

        first_result = packager.pack(plugin, first)
        second_result = packager.pack(plugin, second)

        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertLess(first_result["archiveBytes"], 200_000_000)
        self.assertLess(first_result["unpackedBytes"], 180 * 1024 * 1024)
        with zipfile.ZipFile(first) as archive:
            self.assertIn("semantic-scala.mcpb", archive.namelist())
            self.assertNotIn(".mcp.json", archive.namelist())

    def test_validate_rejects_embedded_mcpb_identity_change(self) -> None:
        packager = load_packager()
        plugin = self.base / "plugin"
        packager.assemble(self.mcpb, plugin)
        with (plugin / "semantic-scala.mcpb").open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(packager.PackagingError, "manifest differs"):
            packager.validate(plugin)


if __name__ == "__main__":
    unittest.main()
