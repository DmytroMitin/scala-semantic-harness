#!/usr/bin/env python3
"""Security and packaging contracts for the thin Claude directory plugin."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER_PATH = ROOT / "scripts/package-claude-directory-plugin.py"
BOOTSTRAP_PATH = (
    ROOT
    / "packaging/claude-directory-plugin/semantic-scala/bootstrap/semantic_scala_bootstrap.py"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PACKAGER = load_module("package_claude_directory_plugin", PACKAGER_PATH)
BOOTSTRAP = load_module("semantic_scala_bootstrap", BOOTSTRAP_PATH)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ThinPluginContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)

    def _archive_bytes(
        self,
        *,
        extra: list[tuple[str, bytes, int]] | None = None,
        duplicate: tuple[str, bytes, int] | None = None,
    ) -> bytes:
        files: list[tuple[str, bytes, int]] = [
            ("manifest.json", b'{"name":"semantic-scala","version":"0.1.0-alpha.3"}\n', 0o644),
            ("bin/semantic-scala", b"#!/bin/sh\nexit 0\n", 0o755),
            ("bin/semantic-scala-mcp", b"#!/bin/sh\nexit 0\n", 0o755),
            ("runtime/bin/java", b"fake-java\n", 0o755),
            ("app/cli/classpath.txt", b"app/cli/lib/classes\n", 0o644),
            ("app/mcp/classpath.txt", b"app/mcp/lib/classes\n", 0o644),
        ]
        files.extend(extra or [])
        inventory = {
            "schemaVersion": "semantic-scala.mcpb-payload-inventory.v1",
            "version": "0.1.0-alpha.3",
            "platform": "linux-x86_64",
            "files": [
                {
                    "path": name,
                    "bytes": len(data),
                    "mode": f"{stat.S_IMODE(mode):04o}",
                    "sha256": sha256_bytes(data),
                }
                for name, data, mode in files
            ],
        }
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data, mode in files:
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                file_mode = mode if stat.S_IFMT(mode) else stat.S_IFREG | mode
                info.external_attr = file_mode << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
            manifest = json.dumps(inventory, indent=2, sort_keys=True).encode() + b"\n"
            info = zipfile.ZipInfo(
                "payload-inventory.json", date_time=(1980, 1, 1, 0, 0, 0)
            )
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, manifest)
            if duplicate is not None:
                name, data, mode = duplicate
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(info, data)
        return buffer.getvalue()

    def _identity(self, data: bytes):
        return BOOTSTRAP.ArtifactIdentity(
            url="https://example.invalid/exact-alpha3.mcpb",
            bytes=len(data),
            sha256=sha256_bytes(data),
            version="0.1.0-alpha.3",
        )

    def test_production_identity_is_fixed_and_not_environment_redirectable(self) -> None:
        os.environ["SEMANTIC_SCALA_MCPB_URL"] = "https://attacker.invalid/runtime.mcpb"
        self.addCleanup(os.environ.pop, "SEMANTIC_SCALA_MCPB_URL", None)
        identity = BOOTSTRAP.production_identity()
        self.assertEqual(
            identity,
            BOOTSTRAP.ArtifactIdentity(
                url=(
                    "https://github.com/DmytroMitin/scala-semantic-harness/releases/download/"
                    "0.1.0-alpha.3/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb"
                ),
                bytes=285_603_142,
                sha256="f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d",
                version="0.1.0-alpha.3",
            ),
        )

    def test_stream_download_rejects_oversize_truncated_and_hash_mismatch(self) -> None:
        output = self.base / "download.mcpb"
        expected = BOOTSTRAP.ArtifactIdentity(
            url="https://example.invalid/a",
            bytes=3,
            sha256=sha256_bytes(b"abc"),
            version="0.1.0-alpha.3",
        )
        for payload, message in [
            (b"abcd", "larger"),
            (b"ab", "byte count"),
            (b"abd", "SHA-256"),
        ]:
            with self.subTest(payload=payload), self.assertRaisesRegex(
                BOOTSTRAP.BootstrapError, message
            ):
                BOOTSTRAP._stream_download(io.BytesIO(payload), output, expected)
            self.assertFalse(output.exists())

    def test_redirect_policy_rejects_http_ftp_and_relative_destinations(self) -> None:
        handler = BOOTSTRAP._HttpsOnlyRedirectHandler()
        request = BOOTSTRAP.urllib.request.Request("https://example.invalid/source")
        for target in [
            "http://example.invalid/downgrade",
            "ftp://example.invalid/runtime",
            "/relative-target",
        ]:
            with self.subTest(target=target), self.assertRaisesRegex(
                BOOTSTRAP.BootstrapError, "not HTTPS"
            ):
                handler.redirect_request(request, None, 302, "Found", {}, target)
        redirected = handler.redirect_request(
            request,
            None,
            302,
            "Found",
            {},
            "https://objects.example.invalid/runtime",
        )
        self.assertEqual(
            redirected.full_url, "https://objects.example.invalid/runtime"
        )

    def test_safe_extraction_rejects_unsafe_and_conflicting_entries(self) -> None:
        cases = [
            (
                self._archive_bytes(extra=[("../escape", b"bad", 0o644)]),
                "unsafe archive path",
            ),
            (
                self._archive_bytes(extra=[("link", b"target", 0o120777)]),
                "not a regular file",
            ),
            (
                self._archive_bytes(duplicate=("bin/semantic-scala", b"other", 0o755)),
                "duplicate archive path",
            ),
            (
                self._archive_bytes(
                    extra=[
                        ("conflict", b"file", 0o644),
                        ("conflict/child", b"child", 0o644),
                    ]
                ),
                "conflicting archive path",
            ),
            (
                self._archive_bytes(
                    extra=[("device", b"", stat.S_IFCHR | 0o600)]
                ),
                "not a regular file",
            ),
        ]
        for index, (payload, message) in enumerate(cases):
            with self.subTest(message=message):
                archive = self.base / f"unsafe-{index}.mcpb"
                archive.write_bytes(payload)
                with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, message):
                    BOOTSTRAP._extract_verified_archive(
                        archive, self.base / f"out-{index}", self._identity(payload)
                    )

    def test_cold_install_is_atomic_and_warm_reuse_never_fetches(self) -> None:
        payload = self._archive_bytes()
        identity = self._identity(payload)
        calls = 0

        def fetch():
            nonlocal calls
            calls += 1
            return io.BytesIO(payload)

        first = BOOTSTRAP._ensure_runtime(self.base / "cache", identity, fetch)
        second = BOOTSTRAP._ensure_runtime(
            self.base / "cache",
            identity,
            lambda: self.fail("warm cache attempted a download"),
        )
        self.assertEqual(first, second)
        self.assertEqual(calls, 1)
        self.assertTrue((first / "bin/semantic-scala").is_file())
        self.assertTrue((first / "bin/semantic-scala-mcp").is_file())
        self.assertTrue((first / "runtime/bin/java").is_file())
        self.assertEqual(stat.S_IMODE(first.stat().st_mode), 0o700)
        self.assertEqual(
            stat.S_IMODE((first / BOOTSTRAP.CACHE_MARKER).stat().st_mode), 0o600
        )
        self.assertEqual(
            stat.S_IMODE((first / "bin/semantic-scala-mcp").stat().st_mode),
            0o700,
        )
        self.assertEqual(list((self.base / "cache").rglob("*.partial-*")), [])

    def test_failed_repair_leaves_an_already_good_cache_untouched(self) -> None:
        payload = self._archive_bytes()
        identity = self._identity(payload)
        runtime = BOOTSTRAP._ensure_runtime(
            self.base / "cache", identity, lambda: io.BytesIO(payload)
        )
        before = (runtime / BOOTSTRAP.CACHE_MARKER).read_bytes()
        with self.assertRaisesRegex(AssertionError, "must not fetch"):
            BOOTSTRAP._ensure_runtime(
                self.base / "cache",
                identity,
                lambda: (_ for _ in ()).throw(AssertionError("must not fetch")),
                force_repair=True,
            )
        self.assertEqual((runtime / BOOTSTRAP.CACHE_MARKER).read_bytes(), before)
        self.assertTrue(BOOTSTRAP._cache_ready(runtime, identity))

    def test_concurrent_initialization_downloads_once(self) -> None:
        payload = self._archive_bytes()
        identity = self._identity(payload)
        calls = 0

        def fetch():
            nonlocal calls
            calls += 1
            return io.BytesIO(payload)

        with ThreadPoolExecutor(max_workers=4) as executor:
            roots = list(
                executor.map(
                    lambda _: BOOTSTRAP._ensure_runtime(
                        self.base / "concurrent-cache", identity, fetch
                    ),
                    range(4),
                )
            )
        self.assertEqual(calls, 1)
        self.assertEqual(len(set(roots)), 1)
        self.assertTrue(BOOTSTRAP._cache_ready(roots[0], identity))

    def test_failed_install_cleans_request_owned_partials(self) -> None:
        payload = self._archive_bytes()
        identity = self._identity(payload)
        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "byte count"):
            BOOTSTRAP._ensure_runtime(
                self.base / "failed-cache", identity, lambda: io.BytesIO(payload[:-1])
            )
        self.assertEqual(list((self.base / "failed-cache").rglob("*.partial-*")), [])

    def test_corrupt_cache_is_repaired_without_stale_backup_files(self) -> None:
        payload = self._archive_bytes()
        identity = self._identity(payload)
        root = BOOTSTRAP._runtime_root(self.base / "repair-cache", identity)
        root.parent.mkdir(parents=True)
        root.write_text("corrupt\n", encoding="utf-8")
        repaired = BOOTSTRAP._ensure_runtime(
            self.base / "repair-cache", identity, lambda: io.BytesIO(payload)
        )
        self.assertTrue(BOOTSTRAP._cache_ready(repaired, identity))
        self.assertEqual(list(repaired.parent.glob(f".{repaired.name}.previous-*")), [])

    def test_candidate_is_deterministic_canonical_and_within_directory_limits(self) -> None:
        first = self.base / "first"
        second = self.base / "second"
        first_manifest = PACKAGER.assemble(first)
        second_manifest = PACKAGER.assemble(second)
        self.assertEqual(first_manifest, second_manifest)
        self.assertEqual(
            (first / "package-manifest.json").read_bytes(),
            (second / "package-manifest.json").read_bytes(),
        )
        self.assertEqual(
            (ROOT / "skills/semantic-scala/SKILL.md").read_bytes(),
            (first / "skills/semantic-scala/SKILL.md").read_bytes(),
        )
        generated_paths = {
            path.relative_to(first).as_posix()
            for path in first.rglob("*")
            if path.is_file()
        }
        self.assertFalse(any("__pycache__" in path for path in generated_paths))
        self.assertFalse(any(path.endswith(".pyc") for path in generated_paths))

        archive = self.base / "candidate.zip"
        packed = PACKAGER.pack(first, archive)
        actual_unpacked = sum(path.stat().st_size for path in first.rglob("*") if path.is_file())
        self.assertEqual(first_manifest["totalBytes"], actual_unpacked)
        self.assertEqual(first_manifest["fileCount"], len(list(path for path in first.rglob("*") if path.is_file())))
        self.assertLess(packed["archiveBytes"], 10 * 1024 * 1024)
        self.assertLess(first_manifest["totalBytes"], 20 * 1024 * 1024)
        self.assertLess(first_manifest["largestFileBytes"], 1024 * 1024)
        self.assertLess(first_manifest["fileCount"], 512)
        (first / "rogue.bin").write_bytes(b"not reviewed\n")
        with self.assertRaisesRegex(
            PACKAGER.PackagingError, "file set differs from the reviewed allowlist"
        ):
            PACKAGER.validate(first)
        (first / "rogue.bin").unlink()
        self.assertEqual(PACKAGER.validate(first), first_manifest)

    def test_mcp_command_is_plain_python_and_package_relative(self) -> None:
        plugin = self.base / "plugin"
        PACKAGER.assemble(plugin)
        config = json.loads((plugin / ".mcp.json").read_text(encoding="utf-8"))
        self.assertEqual(
            config,
            {
                "mcpServers": {
                    "semantic-scala": {
                        "command": "python3",
                        "args": [
                            "${CLAUDE_PLUGIN_ROOT}/bootstrap/semantic_scala_bootstrap.py"
                        ],
                    }
                }
            },
        )

    def test_candidate_exposes_the_public_privacy_policy(self) -> None:
        plugin = self.base / "plugin"
        PACKAGER.assemble(plugin)
        readme = (plugin / "README.md").read_text(encoding="utf-8")
        self.assertIn(
            "https://github.com/DmytroMitin/scala-semantic-harness/blob/main/PRIVACY.md",
            readme,
        )
        manifest = json.loads(
            (plugin / ".claude-plugin/plugin.json").read_text(encoding="utf-8")
        )
        self.assertNotIn("privacyPolicy", manifest)
        self.assertNotIn("privacyPolicyUrl", manifest)


if __name__ == "__main__":
    unittest.main()
