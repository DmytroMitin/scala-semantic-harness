#!/usr/bin/env python3
"""Contract tests for the lightweight Git-backed Codex plugin."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PACKAGER = ROOT / "scripts/package-codex-plugin.py"
CANONICAL_SKILL = ROOT / "skills/semantic-scala/SKILL.md"
EXPECTED_FILES = {
    ".agents/plugins/marketplace.json",
    "distribution/codex-plugin/semantic-scala/plugin.json",
    "distribution/codex-plugin/semantic-scala/.codex-plugin/plugin.json",
    "distribution/codex-plugin/semantic-scala/mcp.json",
    "distribution/codex-plugin/semantic-scala/README.md",
    "distribution/codex-plugin/semantic-scala/provenance.json",
    "distribution/codex-plugin/semantic-scala/skills/semantic-scala/SKILL.md",
}


class PackageCodexPluginTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)

    def _run(self, *arguments: str, expected: int = 0) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, str(PACKAGER), *arguments],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def _files(self, root: Path) -> set[str]:
        return {
            path.relative_to(root).as_posix()
            for path in root.rglob("*")
            if path.is_file()
        }

    def test_assemble_is_deterministic_and_emits_the_exact_contract(self) -> None:
        first = self.base / "first"
        second = self.base / "second"

        self._run("assemble", "--output", str(first))
        self._run("assemble", "--output", str(second))

        self.assertEqual(self._files(first), EXPECTED_FILES)
        self.assertEqual(self._files(second), EXPECTED_FILES)
        for relative in EXPECTED_FILES:
            self.assertEqual((first / relative).read_bytes(), (second / relative).read_bytes())
            self.assertEqual((first / relative).stat().st_mode & 0o777, 0o644)

        plugin_root = first / "distribution/codex-plugin/semantic-scala"
        marketplace = json.loads((first / ".agents/plugins/marketplace.json").read_text())
        self.assertEqual(
            marketplace,
            {
                "name": "scala-semantic-harness",
                "interface": {"displayName": "scala-semantic-harness"},
                "plugins": [
                    {
                        "name": "semantic-scala",
                        "source": {
                            "source": "local",
                            "path": "./distribution/codex-plugin/semantic-scala",
                        },
                        "policy": {
                            "installation": "AVAILABLE",
                            "authentication": "ON_INSTALL",
                        },
                        "category": "Developer Tools",
                    }
                ],
            },
        )
        plugin = json.loads((plugin_root / "plugin.json").read_text())
        self.assertEqual(
            plugin["$schema"],
            "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
        )
        self.assertEqual(plugin["name"], "semantic-scala")
        self.assertEqual(plugin["version"], "0.1.0-alpha.3")
        overlay = json.loads((plugin_root / ".codex-plugin/plugin.json").read_text())
        self.assertEqual(overlay["skills"], "./skills/")
        self.assertNotIn("mcpServers", overlay)
        self.assertNotIn("mcp", overlay)
        mcp = json.loads((plugin_root / "mcp.json").read_text())
        self.assertEqual(
            mcp,
            {
                "$schema": "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
                "mcpServers": {
                    "semantic-scala": {
                        "type": "stdio",
                        "command": "semantic-scala-mcp",
                        "args": [],
                    }
                },
            },
        )
        self.assertEqual(
            (plugin_root / "skills/semantic-scala/SKILL.md").read_bytes(),
            CANONICAL_SKILL.read_bytes(),
        )
        provenance = json.loads((plugin_root / "provenance.json").read_text())
        self.assertEqual(provenance["runtimeVersion"], "0.1.0-alpha.3")
        self.assertEqual(
            provenance["runtimeCoursierChannel"],
            "https://raw.githubusercontent.com/DmytroMitin/scala-semantic-harness/0.1.0-alpha.3/distribution/coursier/channel.json",
        )
        self.assertEqual(
            provenance["canonicalSkill"]["sha256"],
            hashlib.sha256(CANONICAL_SKILL.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            provenance["source"]["preChangeBaselineCommit"],
            "5fdcdb11c4c3612bd56e94f49d953cde40718faa",
        )
        self.assertNotIn("productCommit", provenance)

    def test_check_accepts_current_output_and_rejects_stale_or_extra_files(self) -> None:
        generated = self.base / "generated"
        self._run("assemble", "--output", str(generated))
        self._run("check", "--root", str(generated))

        skill = generated / "distribution/codex-plugin/semantic-scala/skills/semantic-scala/SKILL.md"
        skill.write_text("stale\n", encoding="utf-8")
        stale = self._run("check", "--root", str(generated), expected=1)
        self.assertIn("stale generated file", stale.stderr)

        self._run("assemble", "--output", str(self.base / "clean"))
        clean = self.base / "clean"
        payload = clean / "distribution/codex-plugin/semantic-scala/server.jar"
        payload.write_bytes(b"runtime")
        extra = self._run("check", "--root", str(clean), expected=1)
        self.assertIn("unexpected generated file", extra.stderr)

    def test_checked_in_output_is_current(self) -> None:
        self._run("check")

    def test_check_accepts_git_non_executable_modes_under_permissive_umask(self) -> None:
        generated = self.base / "permissive-umask-clone"
        self._run("assemble", "--output", str(generated))
        for relative in EXPECTED_FILES:
            (generated / relative).chmod(0o664)

        self._run("check", "--root", str(generated))


if __name__ == "__main__":
    unittest.main()
