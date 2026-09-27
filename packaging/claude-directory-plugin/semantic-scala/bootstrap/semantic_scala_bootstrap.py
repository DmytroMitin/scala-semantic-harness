#!/usr/bin/env python3
"""Install and exec the exact semantic-scala Alpha-3 MCP server."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tempfile
from typing import BinaryIO, Callable, NamedTuple
import urllib.parse
import urllib.request
import uuid
import zipfile


class BootstrapError(Exception):
    pass


class ArtifactIdentity(NamedTuple):
    url: str
    bytes: int
    sha256: str
    version: str


ARTIFACT = ArtifactIdentity(
    url=(
        "https://github.com/DmytroMitin/scala-semantic-harness/releases/download/"
        "0.1.0-alpha.3/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb"
    ),
    bytes=285_603_142,
    sha256="f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d",
    version="0.1.0-alpha.3",
)
CACHE_MARKER = ".semantic-scala-runtime.json"
MARKER_SCHEMA = "semantic-scala.thin-bootstrap-cache.v1"
REQUIRED_EXECUTABLES = (
    "bin/semantic-scala",
    "bin/semantic-scala-mcp",
    "runtime/bin/java",
)
DOWNLOAD_CHUNK_BYTES = 1024 * 1024


class _HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        if urllib.parse.urlsplit(new_url).scheme.lower() != "https":
            raise BootstrapError("artifact redirect target is not HTTPS")
        return super().redirect_request(
            request, response, code, message, headers, new_url
        )


def production_identity() -> ArtifactIdentity:
    return ARTIFACT


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(DOWNLOAD_CHUNK_BYTES), b""):
            digest.update(block)
    return digest.hexdigest()


def _stream_download(
    source: BinaryIO, destination: Path, identity: ArtifactIdentity
) -> None:
    digest = hashlib.sha256()
    count = 0
    try:
        with destination.open("xb") as output:
            os.chmod(destination, 0o600)
            while True:
                block = source.read(DOWNLOAD_CHUNK_BYTES)
                if not block:
                    break
                count += len(block)
                if count > identity.bytes:
                    raise BootstrapError("download is larger than the fixed artifact byte count")
                output.write(block)
                digest.update(block)
        if count != identity.bytes:
            raise BootstrapError(
                f"download byte count mismatch: expected {identity.bytes}, got {count}"
            )
        actual = digest.hexdigest()
        if actual != identity.sha256:
            raise BootstrapError(
                f"download SHA-256 mismatch: expected {identity.sha256}, got {actual}"
            )
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def _safe_path(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if (
        not name
        or "\\" in name
        or "\x00" in name
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise BootstrapError(f"unsafe archive path: {name!r}")
    return path


def _entry_mode(info: zipfile.ZipInfo) -> tuple[bool, int]:
    raw = info.external_attr >> 16
    kind = stat.S_IFMT(raw)
    mode = stat.S_IMODE(raw)
    if info.is_dir():
        if kind not in {0, stat.S_IFDIR}:
            raise BootstrapError(f"archive entry is not a regular directory: {info.filename}")
        return True, 0o700
    if kind not in {0, stat.S_IFREG}:
        raise BootstrapError(f"archive entry is not a regular file: {info.filename}")
    if mode not in {0, 0o644, 0o755}:
        raise BootstrapError(f"archive entry has an unexpected mode: {info.filename}")
    return False, 0o700 if mode & 0o111 else 0o600


def _inventory_rows(archive: zipfile.ZipFile) -> dict[str, dict[str, object]]:
    try:
        manifest = json.loads(archive.read("manifest.json"))
        inventory = json.loads(archive.read("payload-inventory.json"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BootstrapError(f"MCPB manifest or payload inventory is invalid: {error}") from error
    if (
        not isinstance(manifest, dict)
        or manifest.get("name") != "semantic-scala"
        or manifest.get("version") != ARTIFACT.version
    ):
        raise BootstrapError("MCPB manifest name or version is not exact Alpha-3")
    if not isinstance(inventory, dict) or inventory.get("version") != ARTIFACT.version:
        raise BootstrapError("MCPB payload inventory version is not exact Alpha-3")
    values = inventory.get("files")
    if not isinstance(values, list):
        raise BootstrapError("MCPB payload inventory has no files array")
    rows: dict[str, dict[str, object]] = {}
    for value in values:
        if not isinstance(value, dict) or not isinstance(value.get("path"), str):
            raise BootstrapError("MCPB payload inventory contains an invalid row")
        name = value["path"]
        if name in rows:
            raise BootstrapError(f"duplicate payload inventory path: {name}")
        rows[name] = value
    return rows


def _extract_verified_archive(
    archive_path: Path, destination: Path, identity: ArtifactIdentity
) -> None:
    if archive_path.is_symlink() or not archive_path.is_file():
        raise BootstrapError("downloaded MCPB is not a regular file")
    if archive_path.stat().st_size != identity.bytes or _sha256(archive_path) != identity.sha256:
        raise BootstrapError("downloaded MCPB identity changed before extraction")
    destination.mkdir(mode=0o700)
    try:
        with zipfile.ZipFile(archive_path) as archive:
            infos = archive.infolist()
            names: set[str] = set()
            files: set[PurePosixPath] = set()
            for info in infos:
                path = _safe_path(info.filename)
                if info.filename in names:
                    raise BootstrapError(f"duplicate archive path: {info.filename}")
                names.add(info.filename)
                is_directory, _ = _entry_mode(info)
                for parent in path.parents:
                    if str(parent) != "." and parent in files:
                        raise BootstrapError(f"conflicting archive path: {info.filename}")
                if not is_directory:
                    if any(existing != path and path in existing.parents for existing in files):
                        raise BootstrapError(f"conflicting archive path: {info.filename}")
                    files.add(path)
            rows = _inventory_rows(archive)
            for info in infos:
                path = _safe_path(info.filename)
                is_directory, extracted_mode = _entry_mode(info)
                target = destination.joinpath(*path.parts)
                if is_directory:
                    target.mkdir(parents=True, exist_ok=True, mode=0o700)
                    os.chmod(target, 0o700)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                for parent in [target.parent, *target.parents]:
                    if parent == destination.parent:
                        break
                    if parent.is_dir():
                        os.chmod(parent, 0o700)
                    if parent == destination:
                        break
                digest = hashlib.sha256()
                count = 0
                with archive.open(info) as source, target.open("xb") as output:
                    for block in iter(lambda: source.read(DOWNLOAD_CHUNK_BYTES), b""):
                        count += len(block)
                        output.write(block)
                        digest.update(block)
                os.chmod(target, extracted_mode)
                if info.filename != "payload-inventory.json":
                    row = rows.get(info.filename)
                    raw_mode = stat.S_IMODE(info.external_attr >> 16) or 0o644
                    expected = {
                        "path": info.filename,
                        "bytes": count,
                        "mode": f"{raw_mode:04o}",
                        "sha256": digest.hexdigest(),
                    }
                    if row != expected:
                        raise BootstrapError(
                            f"MCPB payload inventory mismatch: {info.filename}"
                        )
            missing_rows = set(rows).difference(names)
            if missing_rows:
                raise BootstrapError(
                    f"MCPB payload inventory names missing entries: {sorted(missing_rows)}"
                )
    except (OSError, zipfile.BadZipFile) as error:
        raise BootstrapError(f"MCPB extraction failed: {error}") from error
    for relative in REQUIRED_EXECUTABLES:
        path = destination / relative
        if path.is_symlink() or not path.is_file() or not os.access(path, os.X_OK):
            raise BootstrapError(f"required MCPB entrypoint is missing: {relative}")


def _marker(identity: ArtifactIdentity) -> dict[str, object]:
    return {
        "schemaVersion": MARKER_SCHEMA,
        "semanticScalaVersion": identity.version,
        "mcpbBytes": identity.bytes,
        "mcpbSha256": identity.sha256,
        "requiredEntrypoints": list(REQUIRED_EXECUTABLES),
    }


def _cache_ready(root: Path, identity: ArtifactIdentity) -> bool:
    if root.is_symlink() or not root.is_dir():
        return False
    marker = root / CACHE_MARKER
    try:
        if marker.is_symlink() or not marker.is_file():
            return False
        value = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    if value != _marker(identity):
        return False
    for relative in REQUIRED_EXECUTABLES:
        path = root / relative
        if path.is_symlink() or not path.is_file() or not os.access(path, os.X_OK):
            return False
    return True


def _runtime_root(cache_base: Path, identity: ArtifactIdentity) -> Path:
    return cache_base / "semantic-scala/runtime" / identity.version / identity.sha256


def _write_marker(root: Path, identity: ArtifactIdentity) -> None:
    marker = root / CACHE_MARKER
    marker.write_text(json.dumps(_marker(identity), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(marker, 0o600)


def _remove_path(path: Path) -> None:
    if path.is_symlink() or not path.is_dir():
        path.unlink(missing_ok=True)
    else:
        shutil.rmtree(path)


def _ensure_runtime(
    cache_base: Path,
    identity: ArtifactIdentity,
    fetch: Callable[[], BinaryIO],
    *,
    force_repair: bool = False,
) -> Path:
    root = _runtime_root(cache_base, identity)
    lock_directory = root.parent.parent
    lock_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(lock_directory, 0o700)
    root.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(root.parent, 0o700)
    lock_path = lock_directory / ".install.lock"
    with lock_path.open("a+b") as lock:
        os.chmod(lock_path, 0o600)
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        if _cache_ready(root, identity) and not force_repair:
            return root
        partial = Path(tempfile.mkdtemp(prefix=".install.partial-", dir=root.parent))
        os.chmod(partial, 0o700)
        archived = partial / "artifact.mcpb"
        extracted = partial / "payload"
        previous: Path | None = None
        try:
            with fetch() as source:
                _stream_download(source, archived, identity)
            _extract_verified_archive(archived, extracted, identity)
            _write_marker(extracted, identity)
            if not _cache_ready(extracted, identity):
                raise BootstrapError("new cache failed its readiness check")
            if root.exists() or root.is_symlink():
                previous = root.with_name(f".{root.name}.previous-{uuid.uuid4().hex}")
                root.rename(previous)
            try:
                extracted.rename(root)
            except BaseException:
                if previous is not None and previous.exists() and not root.exists():
                    previous.rename(root)
                raise
            if previous is not None:
                _remove_path(previous)
            return root
        finally:
            shutil.rmtree(partial, ignore_errors=True)


def _cache_base() -> Path:
    configured = os.environ.get("XDG_CACHE_HOME")
    if configured and Path(configured).is_absolute():
        return Path(configured)
    return Path.home() / ".cache"


def _open_fixed_artifact() -> BinaryIO:
    if urllib.parse.urlsplit(ARTIFACT.url).scheme.lower() != "https":
        raise BootstrapError("fixed artifact URL is not HTTPS")
    request = urllib.request.Request(
        ARTIFACT.url,
        headers={"User-Agent": "semantic-scala-thin-bootstrap/0.1.0-alpha.3"},
    )
    opener = urllib.request.build_opener(_HttpsOnlyRedirectHandler())
    response = opener.open(request, timeout=120)
    length = response.headers.get("Content-Length")
    if length is not None:
        try:
            announced = int(length)
        except ValueError:
            response.close()
            raise BootstrapError("download Content-Length is invalid")
        if announced > ARTIFACT.bytes:
            response.close()
            raise BootstrapError("download Content-Length exceeds fixed artifact size")
    return response


def ensure_runtime() -> Path:
    return _ensure_runtime(_cache_base(), ARTIFACT, _open_fixed_artifact)


def main() -> int:
    if sys.version_info < (3, 11):
        raise BootstrapError("Python 3.11 or later is required")
    runtime = ensure_runtime()
    server = runtime / "bin/semantic-scala-mcp"
    cli = runtime / "bin/semantic-scala"
    os.execv(str(server), [str(server), "--cli", str(cli), *sys.argv[1:]])
    raise AssertionError("os.execv returned unexpectedly")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BootstrapError, OSError) as error:
        print(f"semantic-scala bootstrap: {error}", file=sys.stderr)
        raise SystemExit(1)
