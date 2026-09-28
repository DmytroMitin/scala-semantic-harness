#!/usr/bin/env python3
"""Behavioral contracts for the OpenAI skills-only semantic-scala helper."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
HELPER = (
    ROOT
    / "packaging/openai-skills-plugin/semantic-scala"
    / "skills/semantic-scala/scripts/semantic_scala_cli.py"
)
VERSION = "0.1.0-alpha.3"
DIGEST = "f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d"


class OpenAiSkillsHelperTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.workspace = self.base / "workspace"
        self.workspace.mkdir()
        self.fixture = self.workspace / "Fixture.scala"
        self.fixture.write_text(
            "object Fixture { def value: Option[Int] = Some(1) }\n",
            encoding="utf-8",
        )
        self.cache = self.base / "cache"

    def _environment(self) -> dict[str, str]:
        value = os.environ.copy()
        value["XDG_CACHE_HOME"] = str(self.cache)
        value["HTTPS_PROXY"] = "http://127.0.0.1:9"
        value["HTTP_PROXY"] = "http://127.0.0.1:9"
        value["ALL_PROXY"] = "http://127.0.0.1:9"
        value["NO_PROXY"] = ""
        return value

    def _module(self):
        self.assertTrue(HELPER.is_file(), "skills-only helper is not implemented")
        spec = importlib.util.spec_from_file_location("openai_semantic_scala_cli", HELPER)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _archive_bytes(
        self, *, extra: list[tuple[str, bytes, int]] | None = None
    ) -> bytes:
        files: list[tuple[str, bytes, int]] = [
            ("manifest.json", b'{"name":"semantic-scala","version":"0.1.0-alpha.3"}\n', 0o644),
            ("bin/semantic-scala", b"#!/bin/sh\nexit 0\n", 0o755),
            ("runtime/bin/java", b"fake-java\n", 0o755),
        ]
        files.extend(extra or [])
        inventory = {
            "schemaVersion": "semantic-scala.mcpb-payload-inventory.v1",
            "version": VERSION,
            "platform": "linux-x86_64",
            "files": [
                {
                    "path": name,
                    "bytes": len(data),
                    "mode": f"{stat.S_IMODE(mode):04o}",
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
                for name, data, mode in files
            ],
        }
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data, mode in files:
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
            payload = json.dumps(inventory, indent=2, sort_keys=True).encode() + b"\n"
            info = zipfile.ZipInfo(
                "payload-inventory.json", date_time=(1980, 1, 1, 0, 0, 0)
            )
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, payload)
        return buffer.getvalue()

    def _run(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(HELPER), *arguments],
            cwd=self.workspace,
            env=self._environment(),
            check=False,
            text=True,
            capture_output=True,
            timeout=10,
        )

    def _install_fake_cache(self) -> Path:
        runtime = self.cache / "semantic-scala/openai-skills-runtime" / VERSION / DIGEST
        (runtime / "bin").mkdir(parents=True)
        (runtime / "runtime/bin").mkdir(parents=True)
        cli = runtime / "bin/semantic-scala"
        cli.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "print(json.dumps({'argv': sys.argv[1:], 'cwd': os.getcwd()}, sort_keys=True))\n",
            encoding="utf-8",
        )
        for executable in [
            cli,
            runtime / "runtime/bin/java",
        ]:
            if not executable.exists():
                executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            executable.chmod(0o700)
        marker = {
            "mcpbBytes": 285603142,
            "mcpbSha256": DIGEST,
            "requiredEntrypoints": [
                "bin/semantic-scala",
                "runtime/bin/java",
            ],
            "schemaVersion": "semantic-scala.openai-skills-cache.v1",
            "semanticScalaVersion": VERSION,
        }
        marker_path = runtime / ".semantic-scala-runtime.json"
        marker_path.write_text(
            json.dumps(marker, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        marker_path.chmod(0o600)
        runtime.chmod(0o700)
        return runtime

    def test_warm_cached_helper_runs_only_effect_summary_without_network(self) -> None:
        self._install_fake_cache()
        result = self._run("effect-summary", "--file", "Fixture.scala", "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(
            payload,
            {
                "argv": ["effect-summary", "--file", "Fixture.scala", "--json"],
                "cwd": str(self.workspace),
            },
        )

    def test_status_is_non_networking_and_reports_cache_state(self) -> None:
        missing = self._run("status", "--json")
        self.assertEqual(missing.returncode, 0, missing.stderr)
        self.assertEqual(
            json.loads(missing.stdout),
            {
                "artifactBytes": 285603142,
                "artifactSha256": DIGEST,
                "cached": False,
                "firstRunNetworkRequired": True,
                "version": VERSION,
            },
        )
        self._install_fake_cache()
        present = self._run("status", "--json")
        self.assertEqual(present.returncode, 0, present.stderr)
        self.assertTrue(json.loads(present.stdout)["cached"])

    def test_rejects_unsupported_or_unsafe_arguments_before_network(self) -> None:
        cases = [
            (("compile", "--json"), "unsupported command"),
            (("effect-summary", "--file", str(self.fixture), "--json"), "relative"),
            (("effect-summary", "--file", "../Fixture.scala", "--json"), "inside"),
            (("effect-summary", "--file", "Missing.scala", "--json"), "regular file"),
            (("effect-summary", "--file", "Fixture.scala"), "--json"),
        ]
        for arguments, expected in cases:
            with self.subTest(arguments=arguments):
                result = self._run(*arguments)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(expected, result.stderr)

    def test_fixed_artifact_identity_cannot_be_overridden_by_environment(self) -> None:
        helper = self._module()
        os.environ["SEMANTIC_SCALA_MCPB_URL"] = "https://attacker.invalid/runtime.mcpb"
        self.addCleanup(os.environ.pop, "SEMANTIC_SCALA_MCPB_URL", None)
        self.assertEqual(
            helper.production_identity(),
            helper.ArtifactIdentity(
                url=(
                    "https://github.com/DmytroMitin/scala-semantic-harness/releases/"
                    "download/0.1.0-alpha.3/"
                    "semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb"
                ),
                bytes=285603142,
                sha256=DIGEST,
                version=VERSION,
            ),
        )

    def test_download_verification_fails_closed(self) -> None:
        helper = self._module()
        output = self.base / "artifact.mcpb"
        expected = helper.ArtifactIdentity(
            url="https://example.invalid/runtime.mcpb",
            bytes=3,
            sha256=hashlib.sha256(b"abc").hexdigest(),
            version=VERSION,
        )
        for payload, message in [
            (b"abcd", "larger"),
            (b"ab", "byte count"),
            (b"abd", "SHA-256"),
        ]:
            with self.subTest(payload=payload), self.assertRaisesRegex(
                helper.BootstrapError, message
            ):
                helper._stream_download(io.BytesIO(payload), output, expected)
            self.assertFalse(output.exists())

    def test_redirects_must_remain_https(self) -> None:
        helper = self._module()
        handler = helper._HttpsOnlyRedirectHandler()
        request = urllib.request.Request("https://example.invalid/source")
        for target in ["http://example.invalid/a", "ftp://example.invalid/a", "/a"]:
            with self.subTest(target=target), self.assertRaisesRegex(
                helper.BootstrapError, "not HTTPS"
            ):
                handler.redirect_request(request, None, 302, "Found", {}, target)

    def test_safe_extraction_rejects_traversal_and_symlinks(self) -> None:
        helper = self._module()
        cases = [
            (self._archive_bytes(extra=[("../escape", b"bad", 0o644)]), "unsafe"),
            (
                self._archive_bytes(extra=[("link", b"target", stat.S_IFLNK | 0o777)]),
                "regular file",
            ),
        ]
        for index, (payload, message) in enumerate(cases):
            archive = self.base / f"bad-{index}.mcpb"
            archive.write_bytes(payload)
            identity = helper.ArtifactIdentity(
                url="https://example.invalid/runtime.mcpb",
                bytes=len(payload),
                sha256=hashlib.sha256(payload).hexdigest(),
                version=VERSION,
            )
            with self.subTest(message=message), self.assertRaisesRegex(
                helper.BootstrapError, message
            ):
                helper._extract_verified_archive(
                    archive, self.base / f"out-{index}", identity
                )

    def test_cold_install_is_atomic_and_warm_reuse_does_not_fetch(self) -> None:
        helper = self._module()
        payload = self._archive_bytes()
        identity = helper.ArtifactIdentity(
            url="https://example.invalid/runtime.mcpb",
            bytes=len(payload),
            sha256=hashlib.sha256(payload).hexdigest(),
            version=VERSION,
        )
        calls = 0

        def fetch():
            nonlocal calls
            calls += 1
            return io.BytesIO(payload)

        first = helper._ensure_runtime(self.cache, identity, fetch)
        second = helper._ensure_runtime(
            self.cache,
            identity,
            lambda: self.fail("warm cache attempted a network fetch"),
        )
        self.assertEqual(first, second)
        self.assertEqual(calls, 1)
        self.assertEqual(stat.S_IMODE(first.stat().st_mode), 0o700)
        self.assertEqual(
            stat.S_IMODE((first / helper.CACHE_MARKER).stat().st_mode), 0o600
        )
        self.assertEqual(list(self.cache.rglob("*.mcpb")), [])


if __name__ == "__main__":
    unittest.main()
