import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts/distribution/coursier-channel.py"
TEMPLATES = ROOT / "distribution/coursier/templates"


class CoursierChannelTest(unittest.TestCase):
    def run_tool(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(TOOL), *args],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_templates_validate_as_exact_two_app_source(self) -> None:
        result = self.run_tool("validate-templates")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("semantic-scala", result.stdout)
        self.assertIn("semantic-scala-mcp", result.stdout)

    def test_generation_substitutes_exact_version_and_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "channel"
            result = self.run_tool(
                "generate",
                "--version",
                "0.1.0-task146.local",
                "--repository",
                "file:/tmp/task146-repository",
                "--output",
                str(output),
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                sorted(path.name for path in output.iterdir()),
                ["semantic-scala-mcp.json", "semantic-scala.json"],
            )
            cli = json.loads((output / "semantic-scala.json").read_text())
            mcp = json.loads((output / "semantic-scala-mcp.json").read_text())
            self.assertEqual(cli["repositories"], ["file:/tmp/task146-repository", "central"])
            self.assertEqual(
                cli["dependencies"],
                ["io.github.dmytromitin:semantic-scala-cli_3:0.1.0-task146.local"],
            )
            self.assertEqual(cli["mainClass"], "semantic.harness.cli.Main")
            self.assertEqual(
                mcp["dependencies"],
                ["io.github.dmytromitin:semantic-harness-mcp-server_3:0.1.0-task146.local"],
            )
            self.assertEqual(mcp["mainClass"], "semantic.harness.mcp.Main")

            validation = self.run_tool(
                "validate-generated",
                "--version",
                "0.1.0-task146.local",
                "--repository",
                "file:/tmp/task146-repository",
                "--channel",
                str(output),
            )
            self.assertEqual(validation.returncode, 0, validation.stderr)

    def test_snapshot_and_non_exact_versions_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            for version in ("0.1.0-SNAPSHOT", "latest.release", ""):
                result = self.run_tool(
                    "generate",
                    "--version",
                    version,
                    "--repository",
                    "central",
                    "--output",
                    str(Path(temporary) / "channel"),
                )
                self.assertNotEqual(result.returncode, 0)

    def test_generation_refuses_nonempty_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "channel"
            output.mkdir()
            (output / "unexpected").write_text("do not overwrite")
            result = self.run_tool(
                "generate",
                "--version",
                "0.1.0-task146.local",
                "--repository",
                "central",
                "--output",
                str(output),
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((output / "unexpected").read_text(), "do not overwrite")


if __name__ == "__main__":
    unittest.main()
