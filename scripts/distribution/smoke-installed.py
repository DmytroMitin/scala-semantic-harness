#!/usr/bin/env python3
"""Smoke-test installed semantic-scala applications without CLI overrides."""

from __future__ import annotations

import argparse
import json
import os
import selectors
import subprocess
import sys
from pathlib import Path
from typing import Any


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
TIMEOUT_SECONDS = 120


class SmokeError(RuntimeError):
    pass


def send(process: subprocess.Popen[str], message: dict[str, Any]) -> None:
    if process.stdin is None:
        raise SmokeError("MCP stdin unavailable")
    process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
    process.stdin.flush()


def receive(
    process: subprocess.Popen[str], selector: selectors.BaseSelector, request_id: int
) -> dict[str, Any]:
    if not selector.select(TIMEOUT_SECONDS):
        raise SmokeError(f"timeout waiting for MCP response {request_id}")
    if process.stdout is None:
        raise SmokeError("MCP stdout unavailable")
    line = process.stdout.readline()
    if not line:
        raise SmokeError(f"MCP closed stdout before response {request_id}")
    response = json.loads(line)
    if response.get("id") != request_id or "error" in response:
        raise SmokeError(f"invalid MCP response {request_id}: {response}")
    return response


def run(install: Path, fixture: Path, version: str) -> dict[str, object]:
    environment = os.environ.copy()
    environment.pop("SEMANTIC_SCALA_CLI", None)
    environment["PATH"] = f"{install}{os.pathsep}{environment.get('PATH', '')}"
    cli = subprocess.run(
        ["semantic-scala", "--version"],
        cwd=fixture,
        env=environment,
        text=True,
        capture_output=True,
        timeout=TIMEOUT_SECONDS,
        check=False,
    )
    if cli.returncode != 0 or cli.stdout.strip() != version:
        raise SmokeError(f"installed CLI version mismatch: {cli.stdout!r} {cli.stderr!r}")

    process = subprocess.Popen(
        ["semantic-scala-mcp"],
        cwd=fixture,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    selector = selectors.DefaultSelector()
    if process.stdout is None:
        raise SmokeError("MCP stdout unavailable")
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "task146-local-proof", "version": "1"},
                },
            },
        )
        initialized = receive(process, selector, 1)["result"]
        if initialized.get("serverInfo", {}).get("version") != version:
            raise SmokeError(f"installed MCP version mismatch: {initialized}")
        send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

        send(process, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        tools = receive(process, selector, 2)["result"].get("tools", [])
        names = [tool.get("name") for tool in tools]
        if names != TOOLS:
            raise SmokeError(f"installed MCP tool allowlist mismatch: {names}")

        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "semantic_effect_summary",
                    "arguments": {"workspace": str(fixture), "file": "src/UserRepo.scala"},
                },
            },
        )
        call = receive(process, selector, 3)["result"]
        structured = call.get("structuredContent", {})
        payload = structured.get("payload", {})
        if call.get("isError") is not False or structured.get("ok") is not True:
            raise SmokeError(f"read-only installed MCP call failed: {call}")
        if payload.get("schemaVersion") != "semantic-scala.effect-summary.v1":
            raise SmokeError(f"read-only installed MCP payload mismatch: {payload}")
        if not payload.get("methods"):
            raise SmokeError("read-only installed MCP result had no methods")
        command = structured.get("command", [])
        if not command or command[0] != "semantic-scala" or any(str(install) in item for item in command):
            raise SmokeError(f"installed MCP command reporting was not sanitized: {command}")
        return {
            "version": version,
            "tools": names,
            "readOnlyTool": "semantic_effect_summary",
            "readOnlySchema": payload["schemaVersion"],
            "cliOverridePresent": False,
        }
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--install", required=True, type=Path)
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.install, args.fixture, args.version), sort_keys=True))
        return 0
    except (SmokeError, OSError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        print(f"installed distribution smoke failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
