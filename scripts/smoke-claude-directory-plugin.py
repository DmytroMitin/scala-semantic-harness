#!/usr/bin/env python3
"""Run a cold or warm exact-eight MCP smoke through the thin Claude plugin."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import selectors
import signal
import stat
import subprocess
import sys
from typing import Any


EXPECTED_TOOLS = [
    "semantic_compile",
    "semantic_errors",
    "semantic_test",
    "semantic_effect_summary",
    "semantic_symbol_at",
    "semantic_symbols",
    "semantic_reconcile_symbol",
    "semantic_point_evidence",
]
MCPB_SHA256 = "f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d"


class SmokeError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def snapshot(root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        mode = path.lstat().st_mode
        relative = path.relative_to(root).as_posix()
        if stat.S_ISLNK(mode):
            raise SmokeError(f"fixture contains a symlink: {relative}")
        if stat.S_ISDIR(mode):
            result.append({"path": relative, "type": "directory", "mode": f"{stat.S_IMODE(mode):04o}"})
        elif stat.S_ISREG(mode):
            result.append(
                {
                    "path": relative,
                    "type": "file",
                    "mode": f"{stat.S_IMODE(mode):04o}",
                    "bytes": path.stat().st_size,
                    "sha256": sha256(path),
                }
            )
        else:
            raise SmokeError(f"fixture contains a special entry: {relative}")
    return result


def send(process: subprocess.Popen[str], value: dict[str, Any]) -> None:
    if process.stdin is None:
        raise SmokeError("MCP stdin is unavailable")
    process.stdin.write(json.dumps(value, separators=(",", ":")) + "\n")
    process.stdin.flush()


def receive(
    process: subprocess.Popen[str], selector: selectors.BaseSelector, request_id: int, timeout: int
) -> dict[str, Any]:
    if not selector.select(timeout=timeout):
        raise SmokeError(f"timed out waiting for MCP response {request_id}")
    if process.stdout is None:
        raise SmokeError("MCP stdout is unavailable")
    line = process.stdout.readline()
    if not line:
        detail = process.stderr.read() if process.stderr is not None else ""
        raise SmokeError(f"MCP exited before response {request_id}: {detail[-2000:]}")
    response = json.loads(line)
    if response.get("id") != request_id or "error" in response:
        raise SmokeError(f"MCP request {request_id} failed: {response}")
    result = response.get("result")
    if not isinstance(result, dict):
        raise SmokeError(f"MCP response {request_id} lacks a result object")
    return result


def cache_root(cache_base: Path) -> Path:
    return cache_base / "semantic-scala/runtime/0.1.0-alpha.3" / MCPB_SHA256


def run(plugin: Path, cache_base: Path, fixture: Path, offline_proxy: bool) -> dict[str, Any]:
    plugin = plugin.resolve(strict=True)
    cache_base = cache_base.absolute()
    cache = cache_root(cache_base)
    ready_before = (cache / ".semantic-scala-runtime.json").is_file()
    if fixture.exists() or fixture.is_symlink():
        raise SmokeError(f"fixture root must be fresh: {fixture}")
    fixture.mkdir(parents=True)
    fixture = fixture.resolve(strict=True)
    source = fixture / "Fixture.scala"
    source.write_text(
        "object Fixture:\n  def value: Option[Int] = Some(1)\n", encoding="utf-8"
    )
    before = snapshot(fixture)
    config = json.loads((plugin / ".mcp.json").read_text(encoding="utf-8"))
    server = config["mcpServers"]["semantic-scala"]
    if server != {
        "command": "python3",
        "args": ["${CLAUDE_PLUGIN_ROOT}/bootstrap/semantic_scala_bootstrap.py"],
    }:
        raise SmokeError("candidate MCP configuration changed")
    bootstrap = server["args"][0].replace("${CLAUDE_PLUGIN_ROOT}", str(plugin))
    environment = os.environ.copy()
    environment["XDG_CACHE_HOME"] = str(cache_base)
    if offline_proxy:
        environment.update(
            {
                "HTTPS_PROXY": "http://127.0.0.1:9",
                "HTTP_PROXY": "http://127.0.0.1:9",
                "ALL_PROXY": "http://127.0.0.1:9",
                "NO_PROXY": "",
                "https_proxy": "http://127.0.0.1:9",
                "http_proxy": "http://127.0.0.1:9",
                "all_proxy": "http://127.0.0.1:9",
                "no_proxy": "",
            }
        )
    process = subprocess.Popen(
        [server["command"], bootstrap],
        cwd=fixture,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    selector: selectors.BaseSelector | None = None
    try:
        if process.stdout is None:
            raise SmokeError("MCP stdout is unavailable")
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "semantic-scala-thin-smoke", "version": "1"},
                },
            },
        )
        initialized = receive(process, selector, 1, 600)
        send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        send(process, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        tools = receive(process, selector, 2, 120).get("tools")
        names = [tool.get("name") for tool in tools] if isinstance(tools, list) else []
        if names != EXPECTED_TOOLS:
            raise SmokeError(f"exact-eight registry changed: {names}")
        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "semantic_effect_summary",
                    "arguments": {"workspace": str(fixture), "file": source.name},
                },
            },
        )
        structured = receive(process, selector, 3, 120).get("structuredContent")
        if not isinstance(structured, dict) or structured.get("ok") is not True:
            raise SmokeError(
                f"semantic_effect_summary did not return adapter ok=true: {structured}"
            )
        payload = structured.get("payload")
        methods = payload.get("methods") if isinstance(payload, dict) else None
        if (
            not isinstance(payload, dict)
            or payload.get("schemaVersion") != "semantic-scala.effect-summary.v1"
            or not isinstance(methods, list)
            or len(methods) != 1
            or methods[0].get("declaredReturnType") != "Option[Int]"
        ):
            raise SmokeError(f"unexpected semantic effect result: {payload}")
        if snapshot(fixture) != before:
            raise SmokeError("read-only semantic treatment modified the fixture")
        marker = json.loads((cache / ".semantic-scala-runtime.json").read_text(encoding="utf-8"))
        return {
            "cacheReadyBefore": ready_before,
            "cacheReadyAfter": True,
            "cacheRoot": str(cache),
            "networkFailureProxyConfigured": offline_proxy,
            "serverVersion": initialized.get("serverInfo", {}).get("version"),
            "toolCount": len(names),
            "toolNames": names,
            "semanticTool": "semantic_effect_summary",
            "semanticResult": {
                "ok": structured["ok"],
                "schemaVersion": payload["schemaVersion"],
                "method": methods[0].get("name"),
                "declaredReturnType": methods[0]["declaredReturnType"],
            },
            "fixtureModified": False,
            "cacheMarker": marker,
            "downloadCopiesRemaining": len(list(cache_base.rglob("*.mcpb"))),
        }
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        if selector is not None:
            selector.close()
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None:
                stream.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin-root", type=Path, required=True)
    parser.add_argument("--cache-base", type=Path, required=True)
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--offline-proxy", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.plugin_root, args.cache_base, args.fixture_root, args.offline_proxy)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, SmokeError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
