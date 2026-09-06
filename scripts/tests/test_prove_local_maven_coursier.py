import os
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
PROOF = ROOT / "scripts/distribution/prove-local-maven-coursier.sh"


class LocalMavenCoursierProofTest(unittest.TestCase):
    def test_source_and_primaries_only_options_can_be_combined(self) -> None:
        environment = os.environ.copy()
        environment["CS"] = sys.executable
        environment["JAVA21_HOME"] = "/definitely/missing/task229-jdk"

        result = subprocess.run(
            [str(PROOF), "--source", str(ROOT), "--primaries-only"],
            cwd=ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(result.returncode, 1)
        self.assertIn("JAVA21_HOME must name a JDK 21 home", result.stderr)
        self.assertNotIn("Usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
