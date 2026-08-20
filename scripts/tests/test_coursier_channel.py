import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts/distribution/coursier-channel.py"
TEMPLATES = ROOT / "distribution/coursier/templates"
CANONICAL_CHANNEL = ROOT / "distribution/coursier/channel.json"


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
                ["com.github.dmytromitin:semantic-scala-cli_3:0.1.0-task146.local"],
            )
            self.assertEqual(cli["mainClass"], "semantic.harness.cli.Main")
            self.assertEqual(
                mcp["dependencies"],
                ["com.github.dmytromitin:semantic-harness-mcp-server_3:0.1.0-task146.local"],
            )
            self.assertEqual(mcp["mainClass"], "semantic.harness.mcp.Main")
            self.assertNotIn("io.github.dmytromitin", json.dumps([cli, mcp]))

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

    def test_url_generation_is_exact_two_application_central_channel(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "channel.json"
            result = self.run_tool(
                "generate-url",
                "--version",
                "0.1.0-alpha.2",
                "--output",
                str(output),
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                json.loads(output.read_text()),
                {
                    "semantic-scala": {
                        "repositories": ["central"],
                        "dependencies": [
                            "com.github.dmytromitin:semantic-scala-cli_3:0.1.0-alpha.2"
                        ],
                        "mainClass": "semantic.harness.cli.Main",
                        "launcherType": "bootstrap",
                        "javaOptions": ["-XX:+PerfDisableSharedMem"],
                    },
                    "semantic-scala-mcp": {
                        "repositories": ["central"],
                        "dependencies": [
                            "com.github.dmytromitin:semantic-harness-mcp-server_3:0.1.0-alpha.2"
                        ],
                        "mainClass": "semantic.harness.mcp.Main",
                        "launcherType": "bootstrap",
                        "javaOptions": ["-XX:+PerfDisableSharedMem"],
                    },
                },
            )
            self.assertTrue(output.read_bytes().endswith(b"\n"))

            validation = self.run_tool(
                "validate-url",
                "--version",
                "0.1.0-alpha.2",
                "--channel",
                str(output),
            )
            self.assertEqual(validation.returncode, 0, validation.stderr)

    def test_checked_release_channel_matches_deterministic_generation(self) -> None:
        validation = self.run_tool(
            "validate-url",
            "--version",
            "0.1.0-alpha.2",
            "--channel",
            str(CANONICAL_CHANNEL),
        )
        self.assertEqual(validation.returncode, 0, validation.stderr)
        with tempfile.TemporaryDirectory() as temporary:
            generated = Path(temporary) / "channel.json"
            generation = self.run_tool(
                "generate-url",
                "--version",
                "0.1.0-alpha.2",
                "--output",
                str(generated),
            )
            self.assertEqual(generation.returncode, 0, generation.stderr)
            self.assertEqual(generated.read_bytes(), CANONICAL_CHANNEL.read_bytes())

    def test_url_validation_rejects_malformed_or_inexact_channels(self) -> None:
        valid = {
            "semantic-scala": {
                "repositories": ["central"],
                "dependencies": [
                    "com.github.dmytromitin:semantic-scala-cli_3:0.1.0-alpha.2"
                ],
                "mainClass": "semantic.harness.cli.Main",
                "launcherType": "bootstrap",
                "javaOptions": ["-XX:+PerfDisableSharedMem"],
            },
            "semantic-scala-mcp": {
                "repositories": ["central"],
                "dependencies": [
                    "com.github.dmytromitin:semantic-harness-mcp-server_3:0.1.0-alpha.2"
                ],
                "mainClass": "semantic.harness.mcp.Main",
                "launcherType": "bootstrap",
                "javaOptions": ["-XX:+PerfDisableSharedMem"],
            },
        }
        cases = {
            "malformed": "{",
            "missing-app": json.dumps({"semantic-scala": valid["semantic-scala"]}),
            "extra-app": json.dumps({**valid, "unexpected": valid["semantic-scala"]}),
            "extra-field": json.dumps(
                {
                    **valid,
                    "semantic-scala": {
                        **valid["semantic-scala"],
                        "name": "ignored-by-coursier",
                    },
                }
            ),
            "moving-version": json.dumps(
                {
                    **valid,
                    "semantic-scala": {
                        **valid["semantic-scala"],
                        "dependencies": [
                            "com.github.dmytromitin:semantic-scala-cli_3:latest.release"
                        ],
                    },
                }
            ),
            "wrong-namespace": json.dumps(
                {
                    **valid,
                    "semantic-scala": {
                        **valid["semantic-scala"],
                        "dependencies": [
                            "io.github.dmytromitin:semantic-scala-cli_3:0.1.0-alpha.2"
                        ],
                    },
                }
            ),
            "wrong-artifact": json.dumps(
                {
                    **valid,
                    "semantic-scala": {
                        **valid["semantic-scala"],
                        "dependencies": [
                            "com.github.dmytromitin:semantic-harness-core_3:0.1.0-alpha.2"
                        ],
                    },
                }
            ),
            "local-repository": json.dumps(
                {
                    **valid,
                    "semantic-scala": {
                        **valid["semantic-scala"],
                        "repositories": ["file:/tmp/repository", "central"],
                    },
                }
            ),
        }
        with tempfile.TemporaryDirectory() as temporary:
            for name, content in cases.items():
                with self.subTest(name=name):
                    channel = Path(temporary) / f"{name}.json"
                    channel.write_text(content)
                    result = self.run_tool(
                        "validate-url",
                        "--version",
                        "0.1.0-alpha.2",
                        "--channel",
                        str(channel),
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Coursier channel validation failed", result.stderr)

    def test_url_generation_refuses_existing_or_symlink_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            existing = root / "existing.json"
            existing.write_text("do not overwrite")
            result = self.run_tool(
                "generate-url",
                "--version",
                "0.1.0-alpha.2",
                "--output",
                str(existing),
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("output must be absent", result.stderr)
            self.assertEqual(existing.read_text(), "do not overwrite")

            target = root / "target.json"
            target.write_text("do not follow")
            symlink = root / "channel.json"
            symlink.symlink_to(target)
            result = self.run_tool(
                "generate-url",
                "--version",
                "0.1.0-alpha.2",
                "--output",
                str(symlink),
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("output must be absent", result.stderr)
            self.assertEqual(target.read_text(), "do not follow")


if __name__ == "__main__":
    unittest.main()
