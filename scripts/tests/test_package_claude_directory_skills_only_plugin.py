#!/usr/bin/env python3
"""Contracts for the Claude Directory skills-only CLI plugin candidate."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER_PATH = ROOT / "scripts/package-claude-directory-skills-only-plugin.py"
SOURCE = ROOT / "packaging/claude-directory-skills-only-plugin/semantic-scala"
CANONICAL_POLICY = ROOT / "skills/semantic-scala/SKILL.md"


def load_packager():
    if not PACKAGER_PATH.is_file():
        raise AssertionError("Claude Directory skills-only packager is not implemented")
    spec = importlib.util.spec_from_file_location(
        "package_claude_directory_skills_only_plugin", PACKAGER_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ClaudeDirectorySkillsOnlyPackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)

    def test_maintained_source_is_exactly_the_contained_skills_only_layout(self) -> None:
        packager = load_packager()
        manifest = packager.validate(SOURCE)
        files = {
            path.relative_to(SOURCE).as_posix()
            for path in SOURCE.rglob("*")
            if path.is_file()
        }
        self.assertEqual(
            files,
            {
                ".claude-plugin/plugin.json",
                "LICENSE",
                "README.md",
                "package-manifest.json",
                "skills/semantic-scala/SKILL.md",
                "skills/semantic-scala/references/semantic-scala-policy.md",
            },
        )
        self.assertEqual(manifest["fileCount"], 6)
        self.assertEqual(manifest["route"], "skills-only-external-cli")
        self.assertTrue(manifest["skillsOnly"])
        self.assertFalse(manifest["bundlesCli"])
        self.assertEqual(manifest["runtimeDeclarations"], [])
        self.assertEqual(
            (
                SOURCE
                / "skills/semantic-scala/references/semantic-scala-policy.md"
            ).read_bytes(),
            CANONICAL_POLICY.read_bytes(),
        )

    def test_plugin_and_skill_have_no_runtime_or_preapproved_tool_declarations(self) -> None:
        packager = load_packager()
        packager.validate(SOURCE)
        plugin = json.loads(
            (SOURCE / ".claude-plugin/plugin.json").read_text(encoding="utf-8")
        )
        self.assertEqual(plugin["name"], "semantic-scala")
        self.assertEqual(plugin["version"], "0.1.0-alpha.3")
        encoded = json.dumps(plugin, sort_keys=True)
        for forbidden in ["mcpServers", "hooks", "lspServers", "commands", "agents"]:
            self.assertNotIn(forbidden, encoded)

        skill = (SOURCE / "skills/semantic-scala/SKILL.md").read_text(
            encoding="utf-8"
        )
        frontmatter = skill.split("---", 2)[1]
        self.assertNotIn("allowed-tools", frontmatter)
        self.assertNotIn("disallowed-tools", frontmatter)
        self.assertIn("references/semantic-scala-policy.md", skill)
        self.assertIn("independently installed `semantic-scala`", skill)
        self.assertIn("ordinary shell permissions", skill)
        self.assertIn("Do not install, update, or download", skill)
        self.assertIn("Do not substitute an MCP server", skill)
        for command in [
            "compile",
            "errors",
            "test",
            "effect-summary",
            "symbol-at",
            "symbols",
            "reconcile-symbol",
            "point-evidence",
        ]:
            self.assertIn(f"`semantic-scala {command}", skill)

    def test_assemble_and_archive_are_deterministic(self) -> None:
        packager = load_packager()
        first = self.base / "first"
        second = self.base / "second"
        self.assertEqual(packager.assemble(first), packager.assemble(second))
        first_zip = self.base / "first.zip"
        second_zip = self.base / "second.zip"
        first_archive = packager.pack(first, first_zip)
        second_archive = packager.pack(second, second_zip)
        self.assertEqual(first_archive["archiveSha256"], second_archive["archiveSha256"])
        self.assertEqual(first_zip.read_bytes(), second_zip.read_bytes())
        with zipfile.ZipFile(first_zip) as archive:
            self.assertEqual(archive.namelist(), sorted(archive.namelist()))
            self.assertLess(first_archive["archiveBytes"], 50 * 1024 * 1024)

    def test_validation_rejects_mcp_runtime_hooks_and_allowed_tools(self) -> None:
        packager = load_packager()
        cases = [
            (".mcp.json", '{"mcpServers": {}}\n', "allowlist"),
            ("hooks/hooks.json", "{}\n", "allowlist"),
            (
                "skills/semantic-scala/SKILL.md",
                "---\nname: semantic-scala\ndescription: test\nallowed-tools: Bash\n---\n",
                "allowed-tools",
            ),
        ]
        for index, (relative, content, message) in enumerate(cases):
            with self.subTest(relative=relative):
                candidate = self.base / f"case-{index}"
                shutil.copytree(SOURCE, candidate)
                target = candidate / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
                if relative == "skills/semantic-scala/SKILL.md":
                    packager.write_manifest(candidate)
                with self.assertRaisesRegex(packager.PackagingError, message):
                    packager.validate(candidate)

    def test_validation_rejects_executable_files_and_symlinks(self) -> None:
        packager = load_packager()
        executable = self.base / "executable"
        shutil.copytree(SOURCE, executable)
        readme = executable / "README.md"
        readme.chmod(0o755)
        packager.write_manifest(executable)
        with self.assertRaisesRegex(packager.PackagingError, "executable"):
            packager.validate(executable)

        linked = self.base / "linked"
        shutil.copytree(SOURCE, linked)
        policy = linked / "skills/semantic-scala/references/semantic-scala-policy.md"
        policy.unlink()
        os.symlink(CANONICAL_POLICY, policy)
        with self.assertRaisesRegex(packager.PackagingError, "unsupported entry"):
            packager.validate(linked)

    def test_manifest_records_directory_limits_and_external_cli_boundary(self) -> None:
        packager = load_packager()
        manifest = packager.validate(SOURCE)
        self.assertLess(manifest["totalBytes"], 256 * 1024 * 1024)
        self.assertLess(manifest["largestFileBytes"], 5 * 1024 * 1024)
        self.assertEqual(manifest["archiveEntryCount"], 6)
        self.assertEqual(manifest["filesystemEntryCount"], 10)
        self.assertLess(manifest["filesystemEntryCount"], 10_000)
        self.assertEqual(
            manifest["cliPrerequisite"],
            {
                "command": "semantic-scala",
                "installation": "independent",
                "requiredVersion": "0.1.0-alpha.3",
            },
        )
        self.assertEqual(manifest["networkDuringPluginInstall"], False)
        self.assertEqual(manifest["automaticNetworkDuringPluginUse"], False)


if __name__ == "__main__":
    unittest.main()
