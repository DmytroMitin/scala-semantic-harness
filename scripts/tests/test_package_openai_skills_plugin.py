#!/usr/bin/env python3
"""Packaging contracts for the OpenAI skills-only semantic-scala plugin."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER_PATH = ROOT / "scripts/package-openai-skills-plugin.py"
CANONICAL_POLICY = ROOT / "skills/semantic-scala/SKILL.md"
SUBMISSION = ROOT / "distribution/openai-skills-only/submission.json"
REVIEW_CASES = ROOT / "distribution/openai-skills-only/review-test-cases.md"


def load_packager():
    if not PACKAGER_PATH.is_file():
        raise AssertionError("OpenAI skills-only packager is not implemented")
    spec = importlib.util.spec_from_file_location(
        "package_openai_skills_plugin", PACKAGER_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OpenAiSkillsPackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)

    def test_assembled_package_is_skills_only_and_canonical_policy_is_exact(self) -> None:
        packager = load_packager()
        plugin = self.base / "semantic-scala"
        manifest = packager.assemble(plugin)
        files = {
            path.relative_to(plugin).as_posix()
            for path in plugin.rglob("*")
            if path.is_file()
        }
        self.assertEqual(
            files,
            {
                ".codex-plugin/plugin.json",
                "README.md",
                "package-manifest.json",
                "plugin.json",
                "skills/semantic-scala/SKILL.md",
                "skills/semantic-scala/references/semantic-scala-policy.md",
                "skills/semantic-scala/scripts/semantic_scala_cli.py",
            },
        )
        self.assertEqual(manifest["fileCount"], 7)
        self.assertEqual(
            (plugin / "skills/semantic-scala/references/semantic-scala-policy.md").read_bytes(),
            CANONICAL_POLICY.read_bytes(),
        )
        self.assertEqual(
            list(plugin.rglob("mcp.json")) + list(plugin.rglob(".mcp.json")), []
        )

    def test_manifest_and_skill_scope_match_the_narrow_codex_workflow(self) -> None:
        packager = load_packager()
        plugin = self.base / "semantic-scala"
        packager.assemble(plugin)
        portable = json.loads((plugin / "plugin.json").read_text(encoding="utf-8"))
        compatibility = json.loads(
            (plugin / ".codex-plugin/plugin.json").read_text(encoding="utf-8")
        )
        for manifest in [portable, compatibility]:
            self.assertEqual(manifest["name"], "semantic-scala")
            self.assertEqual(manifest["version"], "0.1.0-alpha.3")
            encoded = json.dumps(manifest, sort_keys=True)
            self.assertNotIn("mcpServers", encoded)
            self.assertNotIn("apps", encoded)
        interface = compatibility["interface"]
        self.assertLessEqual(len(interface["displayName"]), 30)
        self.assertLessEqual(len(interface["shortDescription"]), 30)
        self.assertEqual(interface["category"], "Developer Tools")
        self.assertEqual(
            interface["capabilities"], ["Inspect declared Scala effect wrappers"]
        )
        skill = (plugin / "skills/semantic-scala/SKILL.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("scripts/semantic_scala_cli.py", skill)
        self.assertIn("references/semantic-scala-policy.md", skill)
        self.assertIn("effect-summary", skill)
        self.assertNotIn("semantic-scala-mcp", skill)
        self.assertFalse((plugin / "skills/semantic-scala/agents/openai.yaml").exists())

    def test_package_and_archive_generation_are_deterministic(self) -> None:
        packager = load_packager()
        first = self.base / "first"
        second = self.base / "second"
        first_manifest = packager.assemble(first)
        second_manifest = packager.assemble(second)
        self.assertEqual(first_manifest, second_manifest)
        first_zip = self.base / "first.zip"
        second_zip = self.base / "second.zip"
        first_archive = packager.pack(first, first_zip)
        second_archive = packager.pack(second, second_zip)
        self.assertEqual(first_archive["archiveSha256"], second_archive["archiveSha256"])
        self.assertEqual(first_zip.read_bytes(), second_zip.read_bytes())
        with zipfile.ZipFile(first_zip) as archive:
            self.assertEqual(
                archive.namelist(), sorted(archive.namelist())
            )

    def test_validation_rejects_unreviewed_files_and_mcp_configuration(self) -> None:
        packager = load_packager()
        plugin = self.base / "semantic-scala"
        packager.assemble(plugin)
        (plugin / "rogue.txt").write_text("unexpected\n", encoding="utf-8")
        with self.assertRaisesRegex(packager.PackagingError, "allowlist"):
            packager.validate(plugin)
        (plugin / "rogue.txt").unlink()
        (plugin / "mcp.json").write_text('{"servers": {}}\n', encoding="utf-8")
        with self.assertRaisesRegex(packager.PackagingError, "MCP"):
            packager.validate(plugin)

    def test_submission_packet_matches_the_validated_narrow_scope(self) -> None:
        self.assertTrue(SUBMISSION.is_file(), "submission packet is not implemented")
        packet = json.loads(SUBMISSION.read_text(encoding="utf-8"))
        self.assertEqual(packet["pluginName"], "semantic-scala")
        self.assertEqual(packet["version"], "0.1.0-alpha.3")
        self.assertEqual(packet["submissionType"], "skills-only")
        self.assertEqual(packet["category"], "Developer Tools")
        self.assertLessEqual(len(packet["displayName"]), 30)
        self.assertLessEqual(len(packet["shortDescription"]), 30)
        self.assertEqual(
            packet["capabilities"], ["Inspect declared Scala effect wrappers"]
        )
        self.assertEqual(len(packet["starterPrompts"]), 3)
        self.assertTrue(all(len(value) <= 128 for value in packet["starterPrompts"]))
        self.assertEqual(len(packet["reviewTests"]["positive"]), 5)
        self.assertEqual(len(packet["reviewTests"]["negative"]), 3)
        self.assertEqual(packet["mcpServers"], [])
        self.assertEqual(packet["terms"]["requiredForSkillsOnlyZip"], False)
        self.assertEqual(
            packet["privacy"]["status"],
            "owner-approved-pending-repository-publication",
        )
        self.assertEqual(packet["logo"]["status"], "human-design-required")
        self.assertEqual(
            packet["composerIcon"]["status"], "human-design-required"
        )
        self.assertEqual(packet["portalActions"], 0)

        cases = REVIEW_CASES.read_text(encoding="utf-8")
        self.assertEqual(cases.count("- Fixture:"), 8)
        self.assertEqual(cases.count("- Expected behavior:"), 8)
        self.assertEqual(cases.count("- Expected result:"), 8)


if __name__ == "__main__":
    unittest.main()
