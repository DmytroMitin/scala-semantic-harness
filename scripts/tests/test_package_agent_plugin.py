#!/usr/bin/env python3
"""Contract tests for the deterministic Agent Plugins package assembler."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PACKAGER = ROOT / "scripts/package-agent-plugin.py"
CANONICAL_SKILL = ROOT / "skills/semantic-scala/SKILL.md"


class PackageAgentPluginTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.cli_stage = self._stage("cli", "semantic-scala")
        self.mcp_stage = self._stage("mcp", "semantic-scala-mcp")

    def _stage(self, name: str, launcher: str) -> Path:
        stage = self.base / name
        (stage / "bin").mkdir(parents=True)
        (stage / "lib").mkdir()
        executable = stage / "bin" / launcher
        executable.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        executable.chmod(0o755)
        (stage / "lib" / f"{name}.jar").write_bytes(f"{name}-bytes".encode())
        return stage

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

    def _assemble(self, output: Path) -> dict[str, object]:
        result = self._run(
            "assemble",
            "--cli-stage",
            str(self.cli_stage),
            "--mcp-stage",
            str(self.mcp_stage),
            "--output",
            str(output),
        )
        return json.loads(result.stdout)

    def test_assemble_is_deterministic_and_preserves_portable_contract(self) -> None:
        first = self.base / "first"
        second = self.base / "second"

        first_result = self._assemble(first)
        second_result = self._assemble(second)

        self.assertEqual(first_result["contentSha256"], second_result["contentSha256"])
        self.assertEqual(
            (first / "package-manifest.json").read_bytes(),
            (second / "package-manifest.json").read_bytes(),
        )
        self.assertEqual(
            CANONICAL_SKILL.read_bytes(),
            (first / "skills/semantic-scala/SKILL.md").read_bytes(),
        )

        plugin = json.loads((first / "plugin.json").read_text(encoding="utf-8"))
        self.assertEqual(
            plugin["$schema"],
            "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
        )
        mcp = json.loads((first / "mcp.json").read_text(encoding="utf-8"))
        server = mcp["mcpServers"]["semantic-scala"]
        self.assertEqual(server["type"], "stdio")
        self.assertEqual(server["command"], "./mcp/bin/semantic-scala-mcp")
        self.assertEqual(
            server["args"],
            ["--cli", "${PLUGIN_ROOT}/cli/bin/semantic-scala"],
        )
        self.assertEqual(server["cwd"], "${PLUGIN_ROOT}")
        self.assertTrue(os.access(first / "cli/bin/semantic-scala", os.X_OK))
        self.assertTrue(os.access(first / "mcp/bin/semantic-scala-mcp", os.X_OK))

        validated = self._run("validate", "--plugin-root", str(first))
        self.assertEqual(json.loads(validated.stdout)["validationLevel"], "structural")

    def test_assemble_rejects_stage_symlinks(self) -> None:
        (self.cli_stage / "lib/escape.jar").symlink_to("/tmp/outside-plugin.jar")
        result = self._run(
            "assemble",
            "--cli-stage",
            str(self.cli_stage),
            "--mcp-stage",
            str(self.mcp_stage),
            "--output",
            str(self.base / "rejected"),
            expect_success=False,
        )
        self.assertIn("symlink", result.stderr.lower())

    def test_validate_rejects_machine_specific_generated_text(self) -> None:
        plugin_root = self.base / "unsafe"
        self._assemble(plugin_root)
        mcp_path = plugin_root / "mcp.json"
        mcp = json.loads(mcp_path.read_text(encoding="utf-8"))
        home_marker = "/home/" + "dmytro/private"
        mcp["mcpServers"]["semantic-scala"]["args"].append(home_marker)
        mcp_path.write_text(json.dumps(mcp), encoding="utf-8")

        result = self._run(
            "validate",
            "--plugin-root",
            str(plugin_root),
            expect_success=False,
        )
        self.assertIn("machine-specific", result.stderr.lower())

    def test_smoke_interprets_portable_mcp_configuration(self) -> None:
        launcher = self.mcp_stage / "bin/semantic-scala-mcp"
        launcher.write_text(
            """#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

expected_cli = Path(os.environ["PLUGIN_ROOT"]) / "cli/bin/semantic-scala"
if sys.argv[1:] != ["--cli", str(expected_cli)] or not expected_cli.is_file():
    raise SystemExit(9)

names = [
    "semantic_compile", "semantic_errors", "semantic_test",
    "semantic_effect_summary", "semantic_symbol_at", "semantic_symbols",
    "semantic_reconcile_symbol", "semantic_point_evidence",
]
for line in sys.stdin:
    request = json.loads(line)
    if "id" not in request:
        continue
    method = request.get("method")
    if method == "initialize":
        result = {"capabilities": {"tools": {}}}
    elif method == "tools/list":
        result = {"tools": [{"name": name, "inputSchema": {}} for name in names]}
    elif method == "tools/call":
        file_arg = request["params"]["arguments"]["file"]
        ok = not file_arg.startswith("/")
        result = {
            "structuredContent": {
                "ok": ok,
                "payload": (
                    {"schemaVersion": "semantic-scala.point-evidence-result.v1"}
                    if ok else None
                ),
            }
        }
    else:
        result = {}
    print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)
""",
            encoding="utf-8",
        )
        launcher.chmod(0o755)
        plugin_root = self.base / "smoke-package"
        self._assemble(plugin_root)

        result = self._run(
            "smoke",
            "--plugin-root",
            str(plugin_root),
            "--plugin-data",
            str(self.base / "plugin data"),
        )
        smoke = json.loads(result.stdout)
        self.assertEqual(smoke["toolCount"], 8)
        self.assertEqual(smoke["toolNames"][0], "semantic_compile")
        self.assertEqual(smoke["representativeTool"], "semantic_point_evidence")
        self.assertEqual(
            smoke["representativeSchema"], "semantic-scala.point-evidence-result.v1"
        )


if __name__ == "__main__":
    unittest.main()
