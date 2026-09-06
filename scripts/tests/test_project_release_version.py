import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts/distribution/project-release-version.py"


class ProjectReleaseVersionTest(unittest.TestCase):
    def run_tool(self, build_text: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "build.sbt").write_text(build_text, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(TOOL), "--source", str(source)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

    def test_reports_the_single_exact_release_version(self) -> None:
        result = self.run_tool('ThisBuild / version := "0.1.0-alpha.3"\n')

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "0.1.0-alpha.3\n")

    def test_rejects_snapshot_or_moving_versions(self) -> None:
        for version in ("0.1.0-alpha.3-SNAPSHOT", "latest.release", "release"):
            with self.subTest(version=version):
                result = self.run_tool(f'ThisBuild / version := "{version}"\n')

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("exact non-SNAPSHOT release version", result.stderr)

    def test_rejects_missing_or_ambiguous_version_assignments(self) -> None:
        cases = (
            'ThisBuild / scalaVersion := "3.9.0"\n',
            'ThisBuild / version := "0.1.0-alpha.3"\nThisBuild / version := "other"\n',
        )
        for build_text in cases:
            with self.subTest(build_text=build_text):
                result = self.run_tool(build_text)

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("exactly one literal ThisBuild / version", result.stderr)


if __name__ == "__main__":
    unittest.main()
