import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
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
        "semantic-harness-sbt-runner_3",
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


def pom(
    artifact: str,
    group: str,
    *,
    internal_version: str = VERSION,
    extra_dependencies: str = "",
    repositories: str = "",
) -> str:
    dependencies = "".join(
        f"<dependency><groupId>{group}</groupId><artifactId>{dependency}</artifactId><version>{internal_version}</version></dependency>"
        for dependency in EDGES[artifact]
    )
    return f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{VERSION}</version>
  <description>implementation</description><url>https://example.invalid/project</url>
  <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
  <scm><url>https://example.invalid/scm</url><connection>scm:git:https://example.invalid/repo.git</connection></scm>
  <developers><developer><id>owner</id><name>Owner</name><url>https://example.invalid/owner</url></developer></developers>
  <dependencies>{dependencies}{extra_dependencies}</dependencies>
  {repositories}
</project>"""


def write_repository(
    repository: Path,
    group: str,
    group_path: str,
    *,
    include_sidecars: bool = True,
    pom_overrides: dict[str, str] | None = None,
) -> None:
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
            pom_text = (pom_overrides or {}).get(module, pom(module, group))
            content = pom_text.encode() if name.endswith(".pom") else name.encode()
            primary = module_root / name
            primary.write_bytes(content)
            if include_sidecars:
                (module_root / f"{name}.asc").write_text("synthetic signature")
                for algorithm in ("sha256", "sha512"):
                    digest = hashlib.new(algorithm, content).hexdigest()
                    (module_root / f"{name}.{algorithm}").write_text(digest + "\n")


class MavenCandidateValidatorTest(unittest.TestCase):
    def run_validator(self, repository: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(VALIDATOR),
                "repository",
                "--repository",
                str(repository),
                "--version",
                VERSION,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def write_worker_fixture(
        self, root: Path, *, jar_classifier: str = ""
    ) -> tuple[Path, Path, bytes]:
        cache = root / "cache"
        relative = Path(f"example/worker-lib/1.0/worker-lib-1.0{jar_classifier}.jar")
        jar = cache / relative
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("example/Worker.class", b"class bytes")
        content = jar.read_bytes()
        (jar.parent / "worker-lib-1.0.pom").write_text(
            """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId><artifactId>worker-lib</artifactId><version>1.0</version>
  <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>
  <dependencies></dependencies>
</project>""",
            encoding="utf-8",
        )
        inventory = root / "worker-dependencies.tsv"
        inventory.write_text(
            f"{relative.as_posix()}\t{len(content)}\t{hashlib.sha256(content).hexdigest()}\n",
            encoding="utf-8",
        )
        return cache, inventory, content

    def run_worker_validator(
        self, cache: Path, inventory: Path, output: Path | None = None
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(VALIDATOR),
            "worker-cache",
            "--cache-root",
            str(cache),
            "--inventory",
            str(inventory),
        ]
        if output is not None:
            command.extend(["--output", str(output)])
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

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

    def test_primary_only_mode_validates_unsigned_release_primaries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            write_repository(
                repository,
                "com.github.dmytromitin",
                "com/github/dmytromitin",
                include_sidecars=False,
            )
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
                    "--primaries-only",
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
            self.assertEqual(len(report["artifacts"]), 32)
            self.assertFalse(report["releaseShape"]["syntheticLocalSignatures"])
            self.assertFalse(report["releaseShape"]["sha256"])
            self.assertFalse(report["releaseShape"]["sha512"])

    def test_rejects_wrong_internal_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            changed = pom(
                "semantic-harness-sbt-runner_3",
                "com.github.dmytromitin",
                internal_version="0.1.0-alpha.2",
            )
            write_repository(
                repository,
                "com.github.dmytromitin",
                "com/github/dmytromitin",
                pom_overrides={"semantic-harness-sbt-runner_3": changed},
            )

            result = self.run_validator(repository)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("internal dependency version mismatch", result.stderr)

    def test_rejects_snapshot_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            changed = pom(
                "semantic-harness-core_3",
                "com.github.dmytromitin",
                extra_dependencies=(
                    "<dependency><groupId>example</groupId><artifactId>moving</artifactId>"
                    "<version>1.0-SNAPSHOT</version></dependency>"
                ),
            )
            write_repository(
                repository,
                "com.github.dmytromitin",
                "com/github/dmytromitin",
                pom_overrides={"semantic-harness-core_3": changed},
            )

            result = self.run_validator(repository)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SNAPSHOT dependency", result.stderr)

    def test_rejects_embedded_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "repository"
            changed = pom(
                "semantic-harness-core_3",
                "com.github.dmytromitin",
                repositories=(
                    "<repositories><repository><id>extra</id><url>https://example.invalid</url>"
                    "</repository></repositories>"
                ),
            )
            write_repository(
                repository,
                "com.github.dmytromitin",
                "com/github/dmytromitin",
                pom_overrides={"semantic-harness-core_3": changed},
            )

            result = self.run_validator(repository)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("embedded repository", result.stderr)

    def test_worker_cache_inventory_is_content_bound_and_license_audited(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache, inventory, content = self.write_worker_fixture(root)
            output = root / "report.json"

            result = self.run_worker_validator(cache, inventory, output)

            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(report["schemaVersion"], "semantic-scala.worker-runtime-inventory.v1")
            self.assertEqual(report["componentCount"], 1)
            self.assertEqual(report["totalBytes"], len(content))
            self.assertEqual(report["components"][0]["gav"], "example:worker-lib:1.0")

    def test_worker_cache_inventory_rejects_hash_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache, inventory, _ = self.write_worker_fixture(root)
            inventory.write_text(
                inventory.read_text(encoding="utf-8").rsplit("\t", 1)[0] + "\t" + "0" * 64 + "\n",
                encoding="utf-8",
            )

            result = self.run_worker_validator(cache, inventory)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("worker inventory SHA-256 mismatch", result.stderr)

    def test_worker_cache_inventory_accepts_a_classifier_jar_with_the_module_pom(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache, inventory, _ = self.write_worker_fixture(root, jar_classifier="-jdk8")

            result = self.run_worker_validator(cache, inventory)

            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
