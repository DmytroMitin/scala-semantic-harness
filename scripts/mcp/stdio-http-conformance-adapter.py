#!/usr/bin/env python3
"""Loopback-only HTTP bridge for testing a newline-delimited stdio MCP server."""

from __future__ import annotations

import argparse
import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Sequence


MAX_REQUEST_BYTES = 4 * 1024 * 1024


class StdioBridge:
    def __init__(self, command: Sequence[str]) -> None:
        self._process = subprocess.Popen(
            list(command),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=None,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        self._lock = threading.Lock()

    def exchange(self, message: dict[str, Any]) -> dict[str, Any] | None:
        with self._lock:
            if self._process.poll() is not None:
                raise RuntimeError("stdio MCP server exited before the request")
            assert self._process.stdin is not None
            self._process.stdin.write(
                json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n"
            )
            self._process.stdin.flush()

            if "id" not in message:
                return None

            assert self._process.stdout is not None
            response_line = self._process.stdout.readline()
            if not response_line:
                raise RuntimeError("stdio MCP server closed stdout before responding")
            response = json.loads(response_line)
            if not isinstance(response, dict):
                raise RuntimeError("stdio MCP response was not a JSON object")
            return response

    def close(self) -> None:
        if self._process.poll() is not None:
            return
        self._process.terminate()
        try:
            self._process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self._process.kill()
            self._process.wait(timeout=5)


def handler_for(bridge: StdioBridge) -> type[BaseHTTPRequestHandler]:
    class ConformanceHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self) -> None:
            if self.path != "/mcp":
                self.send_error(404)
                return

            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                self.send_error(400)
                return
            if length < 0 or length > MAX_REQUEST_BYTES:
                self.send_error(413)
                return

            try:
                message = json.loads(self.rfile.read(length))
            except (UnicodeDecodeError, json.JSONDecodeError):
                self.send_error(400)
                return
            if not isinstance(message, dict):
                self.send_error(400)
                return

            try:
                response = bridge.exchange(message)
            except (BrokenPipeError, json.JSONDecodeError, RuntimeError):
                self.send_error(502)
                return

            if response is None:
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return

            body = json.dumps(
                response, ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            self.send_error(405)

        def do_DELETE(self) -> None:
            self.send_error(405)

        def log_message(self, format: str, *args: object) -> None:
            return

    return ConformanceHandler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Expose an existing stdio MCP command at a loopback-only /mcp "
            "endpoint for conformance testing."
        )
    )
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "server_command",
        nargs=argparse.REMAINDER,
        help="stdio MCP server command, following --",
    )
    args = parser.parse_args()
    if args.server_command[:1] == ["--"]:
        args.server_command = args.server_command[1:]
    if not args.server_command:
        parser.error("a stdio MCP server command is required after --")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    return args


def main() -> int:
    args = parse_args()
    bridge = StdioBridge(args.server_command)
    server = ThreadingHTTPServer(
        ("127.0.0.1", args.port),
        handler_for(bridge),
    )
    print(f"READY http://127.0.0.1:{args.port}/mcp", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
        bridge.close()


if __name__ == "__main__":
    raise SystemExit(main())
