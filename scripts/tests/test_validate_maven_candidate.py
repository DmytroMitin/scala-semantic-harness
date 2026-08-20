import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts/distribution/validate-maven-candidate.py"
VERSION = "0.1.0-task146.local"
MODULES = [
    "semantic-harness-core_3",
    "semantic-harness-sbt-runner_3",
    "semantic-harness-semanticdb-reader_3",
    "semantic-harness-presentation-compiler_3",
    "semantic-harness-semantic-reconciliation_3",
    "semantic-harness-fp-analyzers_3",
    "semantic-scala-cli_3",
    "semantic-harness-mcp-server_3",
]
EDGES = {
    "semantic-harness-core_3": [],
    "semantic-harness-sbt-runner_3": ["semantic-harness-core_3"],
    "semantic-harness-semanticdb-reader_3": ["semantic-harness-core_3"],
    "semantic-harness-presentation-compiler_3": ["semantic-harness-core_3"],
    "semantic-harness-semantic-reconciliation_3": [
        "semantic-harness-core_3",
        "semantic-harness-semanticdb-reader_3",
        "semantic-harness-presentation-compiler_3",
    ],
    "semantic-harness-fp-analyzers_3": ["semantic-harness-core_3"],
    "semantic-scala-cli_3": [
        "semantic-harness-core_3",
        "semantic-harness-sbt-runner_3",
        "semantic-harness-semanticdb-reader_3",
        "semantic-harness-presentation-compiler_3",
        "semantic-harness-semantic-reconciliation_3",
        "semantic-harness-fp-analyzers_3",
    ],
    "semantic-harness-mcp-server_3": [
        "semantic-harness-core_3",
        "semantic-harness-fp-analyzers_3",
        "semantic-harness-presentation-compiler_3",
        "semantic-harness-semanticdb-reader_3",
        "semantic-harness-semantic-reconciliation_3",
    ],
}


def pom(artifact: str, group: str) -> str:
    dependencies = "".join(
        f"<dependency><groupId>{group}</groupId><artifactId>{dependency}</artifactId><version>{VERSION}</version></dependency>"
        for dependency in EDGES[artifact]
    )
    return f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{VERSION}</version>
  <description>implementation</description><url>https://example.invalid/project</url>
  <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
  <scm><url>https://example.invalid/scm</url><connection>scm:git:https://example.invalid/repo.git</connection></scm>
  <developers><developer><id>owner</id><name>Owner</name><url>https://example.invalid/owner</url></developer></developers>
  <dependencies>{dependencies}</dependencies>
</project>"""


def write_repository(repository: Path, group: str, group_path: str) -> None:
    for module in MODULES:
        module_root = repository / group_path / module / VERSION
        module_root.mkdir(parents=True)
        bases = [
            f"{module}-{VERSION}.pom",
            f"{module}-{VERSION}.jar",
            f"{module}-{VERSION}-sources.jar",
            f"{module}-{VERSION}-javadoc.jar",
        ]
        for name in bases:
            content = pom(module, group).encode() if name.endswith(".pom") else name.encode()
            primary = module_root / name
            primary.write_bytes(content)
            (module_root / f"{name}.asc").write_text("synthetic signature")
            for algorithm in ("sha256", "sha512"):
                digest = hashlib.new(algorithm, content).hexdigest()
                (module_root / f"{name}.{algorithm}").write_text(digest + "\n")


class MavenCandidateValidatorTest(unittest.TestCase):
    def test_exact_eight_complete_shapes_are_accepted_and_hashed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            write_repository(repository, "com.github.dmytromitin", "com/github/dmytromitin")
            output = Path(temporary) / "report.json"
            result = subprocess.run(
                [
                    sys.executable,
                    str(VALIDATOR),
                    "repository",
                    "--repository",
                    str(repository),
                    "--version",
                    VERSION,
                    "--output",
                    str(output),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(report["moduleCount"], 8)
            self.assertEqual(report["group"], "com.github.dmytromitin")
            self.assertTrue(report["legacyNamespaceAbsent"])
            self.assertEqual(sorted(report["modules"]), sorted(MODULES))
            self.assertEqual(len(report["artifacts"]), 32)

    def test_legacy_namespace_is_rejected_even_with_a_complete_selected_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            write_repository(repository, "io.github.dmytromitin", "io/github/dmytromitin")
            write_repository(repository, "com.github.dmytromitin", "com/github/dmytromitin")
            result = subprocess.run(
                [sys.executable, str(VALIDATOR), "repository", "--repository", str(repository), "--version", VERSION],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("legacy Maven namespace present", result.stderr)

    def test_missing_sidecar_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            repository.mkdir()
            result = subprocess.run(
                [sys.executable, str(VALIDATOR), "repository", "--repository", str(repository), "--version", VERSION],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
