#!/usr/bin/env python3

import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPOSITORY_ROOT / "scripts" / "validate-public-contract.py"


class ValidatePublicContractTest(unittest.TestCase):
    def make_repository(self, files: dict[str, str], *, track: bool = True) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        subprocess.run(["git", "init", "--quiet", root], check=True)
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        if track:
            subprocess.run(["git", "-C", root, "add", "--all"], check=True)
        return root

    def validate(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", VALIDATOR, "--root", root],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_accepts_standalone_public_language_and_benchmark_prompt_paths(self) -> None:
        root = self.make_repository(
            {
                "README.md": "Run the relevant task with bounded evidence.\n",
                "scripts/benchmark/prompts/example.semantic-harness.md": (
                    "Use compiler and test truth.\n"
                ),
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PUBLIC_CONTRACT_SCAN_PASS", result.stdout)
        self.assertIn("currentPaths=2", result.stdout)

    def test_rejects_numbered_controller_chronology(self) -> None:
        root = self.make_repository(
            {"docs/api.md": "Task " + "077 added this seam.\n"}
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("NUMBERED_CONTROLLER_REFERENCE", result.stderr)
        self.assertIn("docs/api.md:1", result.stderr)

    def test_rejects_user_specific_and_private_control_references(self) -> None:
        root = self.make_repository(
            {
                "README.md": (
                    "Use /home/" + "dmytro/project and "
                    "scala-semantic-harness" + "-control.\n"
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("USER_SPECIFIC_HOME_PATH", result.stderr)
        self.assertIn("PRIVATE_CONTROL_REPOSITORY", result.stderr)

    def test_scans_root_release_policy_documents(self) -> None:
        root = self.make_repository(
            {
                "CONTRIBUTING.md": "Use compiler truth.\n",
                "SECURITY.md": "Report through /home/" + "private/channel.\n",
                "CHANGELOG.md": "# Changelog\n",
                "RELEASE.md": "# Release policy\n",
                "LICENSE": "license text\n",
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("USER_SPECIFIC_HOME_PATH", result.stderr)
        self.assertIn("SECURITY.md:1", result.stderr)

    def test_rejects_numbered_controller_identifiers_in_paths(self) -> None:
        root = self.make_repository(
            {
                "scripts/generate-" + "task" + "064-workspace.sh": (
                    "#!/usr/bin/env bash\n"
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("NUMBERED_CONTROLLER_PATH", result.stderr)

    def test_rejects_findings_in_untracked_current_tree_files(self) -> None:
        root = self.make_repository(
            {"docs/draft.md": "Task " + "077 is still controller chronology.\n"},
            track=False,
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("NUMBERED_CONTROLLER_REFERENCE", result.stderr)

    def test_rejects_internal_product_development_rules_in_canonical_skill(self) -> None:
        root = self.make_repository(
            {
                "skills/semantic-scala/SKILL.md": (
                    "Do not add commands, MCP tools, hooks, services, "
                    "background work, or IDE integrations.\n"
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SKILL_INTERNAL_DEVELOPMENT_SCOPE", result.stderr)

    def test_rejects_product_evaluation_protocol_in_canonical_skill(self) -> None:
        root = self.make_repository(
            {
                "skills/semantic-scala/SKILL.md": (
                    "## Controlled benchmark policy\n"
                    "Pin the client and model, then measure spontaneous selection "
                    "and incorrect patches.\n"
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SKILL_PRODUCT_EVALUATION_PROTOCOL", result.stderr)

    def test_rejects_controller_handoff_instructions_in_canonical_skill(self) -> None:
        root = self.make_repository(
            {
                "skills/semantic-scala/SKILL.md": (
                    "Send the retained evidence to the controller handoff.\n"
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 1)
        self.assertIn("SKILL_CONTROLLER_OR_HANDOFF", result.stderr)

    def test_allows_ordinary_benchmark_word_in_canonical_skill(self) -> None:
        root = self.make_repository(
            {
                "skills/semantic-scala/SKILL.md": (
                    "Use the tool only when evidence can change the task; a "
                    "benchmark project is treated like any other Scala project.\n"
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_ignores_non_user_test_fixture_text(self) -> None:
        root = self.make_repository(
            {
                "modules/core/src/test/scala/example/LegacySuite.scala": (
                    'test("historical ' + "Task " + '077 fixture")\n'
                )
            }
        )

        result = self.validate(root)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_ignores_tracked_files_removed_from_the_current_tree(self) -> None:
        root = self.make_repository(
            {"docs/removed.md": "Task " + "077 historical text.\n"}
        )
        (root / "docs" / "removed.md").unlink()

        result = self.validate(root)

        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
