#!/usr/bin/env python3
"""Contract tests for the deterministic semantic-scala MCPB packager."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER = ROOT / "scripts/package-mcpb.py"
MANIFEST = ROOT / "packaging/mcpb/semantic-scala/manifest.json"
TOOLS = [
    "semantic_compile",
    "semantic_errors",
    "semantic_test",
    "semantic_effect_summary",
    "semantic_symbol_at",
    "semantic_symbols",
    "semantic_reconcile_symbol",
    "semantic_point_evidence",
]


class PackageMcpbTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)

    def _run(self, *args: str, expect_success: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            ["python3", str(PACKAGER), *args],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if expect_success and result.returncode != 0:
            self.fail(f"packager failed ({result.returncode}): {result.stderr}")
        if not expect_success and result.returncode == 0:
            self.fail("packager unexpectedly succeeded")
        return result

    def _stage(self, launcher: str, main_class: str) -> Path:
        stage = self.base / launcher
        (stage / "bin").mkdir(parents=True)
        (stage / "lib/classes-0").mkdir(parents=True)
        (stage / "lib/classes-0/example.class").write_bytes(b"class")
        (stage / "lib/1-dependency.jar").write_bytes(b"jar")
        (stage / "bin" / launcher).write_text(
            "#!/usr/bin/env bash\n"
            'APP_HOME="$(cd x && pwd)"\n'
            'CLASSPATH="$APP_HOME/lib/classes-0:$APP_HOME/lib/1-dependency.jar"\n'
            f'exec java -XX:+PerfDisableSharedMem -cp "$CLASSPATH" {main_class} "$@"\n',
            encoding="utf-8",
        )
        return stage

    def _package_root(self) -> Path:
        package = self.base / "package"
        (package / "bin").mkdir(parents=True)
        (package / "runtime/bin").mkdir(parents=True)
        (package / "app/cli/lib").mkdir(parents=True)
        (package / "app/mcp/lib").mkdir(parents=True)
        shutil.copyfile(MANIFEST, package / "manifest.json")
        for executable in ("bin/semantic-scala", "bin/semantic-scala-mcp", "runtime/bin/java"):
            path = package / executable
            path.write_bytes(executable.encode())
            path.chmod(0o755)
        (package / "app/cli/classpath.txt").write_text("app/cli/lib/a.jar\n", encoding="utf-8")
        (package / "app/mcp/classpath.txt").write_text("app/mcp/lib/b.jar\n", encoding="utf-8")
        (package / "app/cli/lib/a.jar").write_bytes(b"a")
        (package / "app/mcp/lib/b.jar").write_bytes(b"b")
        (package / "LICENSES").mkdir()
        (package / "LICENSES/semantic-scala-Apache-2.0.txt").write_text("license\n", encoding="utf-8")
        (package / "payload-inventory.json").write_text("{}\n", encoding="utf-8")
        self._run("normalize-modes", "--root", str(package))
        return package

    def test_inspect_stage_derives_ordered_classpath_and_main_class(self) -> None:
        stage = self._stage("semantic-scala", "semantic.harness.cli.Main")
        result = self._run(
            "inspect-stage",
            "--stage",
            str(stage),
            "--launcher",
            "semantic-scala",
            "--expected-main",
            "semantic.harness.cli.Main",
        )
        inspected = json.loads(result.stdout)
        self.assertEqual(inspected["mainClass"], "semantic.harness.cli.Main")
        self.assertEqual(inspected["classpath"], ["lib/classes-0", "lib/1-dependency.jar"])

    def test_inspect_stage_rejects_unexpected_main_class(self) -> None:
        stage = self._stage("semantic-scala", "wrong.Main")
        result = self._run(
            "inspect-stage",
            "--stage",
            str(stage),
            "--launcher",
            "semantic-scala",
            "--expected-main",
            "semantic.harness.cli.Main",
            expect_success=False,
        )
        self.assertIn("main class", result.stderr.lower())

    def test_validate_enforces_self_contained_linux_alpha3_contract(self) -> None:
        package = self._package_root()
        result = self._run("validate", "--package-root", str(package))
        validated = json.loads(result.stdout)
        self.assertEqual(validated["version"], "0.1.0-alpha.3")
        self.assertEqual(validated["platforms"], ["linux"])
        self.assertEqual(validated["toolNames"], TOOLS)
        self.assertEqual(validated["serverType"], "binary")

    def test_validate_rejects_group_writable_payload_file(self) -> None:
        package = self._package_root()
        (package / "payload-inventory.json").chmod(0o664)
        result = self._run("validate", "--package-root", str(package), expect_success=False)
        self.assertIn("writable", result.stderr.lower())

    def test_pack_is_byte_deterministic_and_preserves_executable_modes(self) -> None:
        package = self._package_root()
        first = self.base / "first.mcpb"
        second = self.base / "second.mcpb"
        first_result = json.loads(self._run("pack", "--package-root", str(package), "--output", str(first)).stdout)
        second_result = json.loads(self._run("pack", "--package-root", str(package), "--output", str(second)).stdout)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(first_result["sha256"], second_result["sha256"])
        with zipfile.ZipFile(first) as archive:
            names = archive.namelist()
            self.assertEqual(names, sorted(names))
            mode = archive.getinfo("bin/semantic-scala-mcp").external_attr >> 16
            self.assertEqual(mode & 0o777, 0o755)
            self.assertEqual(archive.getinfo("manifest.json").date_time, (1980, 1, 1, 0, 0, 0))

    def test_pack_rejects_symlinks(self) -> None:
        package = self._package_root()
        (package / "escape").symlink_to("/tmp/outside-mcpb")
        result = self._run(
            "pack",
            "--package-root",
            str(package),
            "--output",
            str(self.base / "rejected.mcpb"),
            expect_success=False,
        )
        self.assertIn("symlink", result.stderr.lower())

    def test_materialize_runtime_links_replaces_only_internal_file_links(self) -> None:
        runtime = self.base / "runtime"
        (runtime / "legal/java.base").mkdir(parents=True)
        (runtime / "legal/java.compiler").mkdir(parents=True)
        source = runtime / "legal/java.base/LICENSE"
        source.write_text("jdk license\n", encoding="utf-8")
        linked = runtime / "legal/java.compiler/LICENSE"
        linked.symlink_to("../java.base/LICENSE")

        result = self._run("materialize-runtime-links", "--runtime", str(runtime))
        materialized = json.loads(result.stdout)
        self.assertEqual(materialized["materializedLinks"], 1)
        self.assertFalse(linked.is_symlink())
        self.assertEqual(linked.read_bytes(), source.read_bytes())

    def test_normalize_modes_removes_group_write_without_losing_executability(self) -> None:
        package = self.base / "modes"
        (package / "bin").mkdir(parents=True)
        executable = package / "bin/tool"
        executable.write_bytes(b"tool")
        executable.chmod(0o775)
        data = package / "manifest.json"
        data.write_text("{}\n", encoding="utf-8")
        data.chmod(0o664)

        self._run("normalize-modes", "--root", str(package))
        self.assertEqual(os.stat(package).st_mode & 0o777, 0o755)
        self.assertEqual(os.stat(executable).st_mode & 0o777, 0o755)
        self.assertEqual(os.stat(data).st_mode & 0o777, 0o644)


if __name__ == "__main__":
    unittest.main()
