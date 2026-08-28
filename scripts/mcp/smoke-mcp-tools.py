#!/usr/bin/env python3
"""Smoke-test the staged semantic-scala MCP tools over stdio."""

from __future__ import annotations

import json
import os
import selectors
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Any


TIMEOUT_SECONDS = 120
COMPILE_SCHEMA = "semantic-scala.compile-result.v1"
ERRORS_SCHEMA = "semantic-scala.errors-result.v1"
TEST_SCHEMA = "semantic-scala.test-result.v1"
EFFECT_SUMMARY_SCHEMA = "semantic-scala.effect-summary.v1"
SYMBOL_AT_SCHEMA = "semantic-scala.symbol-at-result.v1"
SYMBOLS_SCHEMA = "semantic-scala.symbols-result.v1"
RECONCILE_SCHEMA = "semantic-scala.reconcile-symbol-result.v2"
POINT_EVIDENCE_SCHEMA = "semantic-scala.point-evidence-result.v2"
SEMANTICDB_FOR_SOURCE_SCHEMA = "semantic-scala.semanticdb-for-source.v2"


class SmokeFailure(Exception):
    pass


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def configured_path(env_name: str, default: Path) -> Path:
    return Path(os.environ.get(env_name, str(default))).resolve()


def require_executable(path: Path, label: str) -> None:
    if not path.exists():
        raise SmokeFailure(f"{label} does not exist: {path}")
    if not os.access(path, os.X_OK):
        raise SmokeFailure(f"{label} is not executable: {path}")


def start_server(root: Path, mcp_path: Path, cli_path: Path) -> tuple[subprocess.Popen[str], list[str]]:
    stderr_lines: list[str] = []
    process = subprocess.Popen(
        [str(mcp_path), "--cli", str(cli_path)],
        cwd=root,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )

    def read_stderr() -> None:
        assert process.stderr is not None
        for line in process.stderr:
            stderr_lines.append(line.rstrip("\n"))

    threading.Thread(target=read_stderr, daemon=True).start()
    return process, stderr_lines


def stop_server(process: subprocess.Popen[str]) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def send(process: subprocess.Popen[str], message: dict[str, Any]) -> None:
    if process.stdin is None:
        raise SmokeFailure("server stdin is unavailable")
    process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
    process.stdin.flush()


def read_response(process: subprocess.Popen[str], selector: selectors.BaseSelector, expected_id: int) -> dict[str, Any]:
    if process.poll() is not None:
        raise SmokeFailure(f"server exited before response {expected_id}: code {process.returncode}")

    events = selector.select(TIMEOUT_SECONDS)
    if not events:
        raise SmokeFailure(f"timed out waiting for response id {expected_id}")

    stdout = process.stdout
    if stdout is None:
        raise SmokeFailure("server stdout is unavailable")

    line = stdout.readline()
    if not line:
        raise SmokeFailure(f"server closed stdout before response id {expected_id}")

    try:
        response = json.loads(line)
    except json.JSONDecodeError as error:
        raise SmokeFailure(f"response id {expected_id} was not JSON: {line!r}") from error

    actual_id = response.get("id")
    if actual_id != expected_id:
        raise SmokeFailure(f"expected response id {expected_id}, got {actual_id}: {response}")
    return response


def expect_no_error(response: dict[str, Any]) -> dict[str, Any]:
    if "error" in response:
        raise SmokeFailure(f"unexpected JSON-RPC error: {response['error']}")
    result = response.get("result")
    if not isinstance(result, dict):
        raise SmokeFailure(f"missing object result: {response}")
    return result


def expect_rpc_error(response: dict[str, Any], code: int) -> None:
    error = response.get("error")
    if not isinstance(error, dict):
        raise SmokeFailure(f"expected JSON-RPC error {code}, got: {response}")
    if error.get("code") != code:
        raise SmokeFailure(f"expected JSON-RPC error {code}, got: {error}")


def tool_result(response: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    result = expect_no_error(response)
    structured = result.get("structuredContent")
    if not isinstance(structured, dict):
        raise SmokeFailure(f"missing structuredContent: {result}")
    return result, structured


def initialize(process: subprocess.Popen[str], selector: selectors.BaseSelector) -> None:
    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "task028-smoke", "version": "1"},
            },
        },
    )
    result = expect_no_error(read_response(process, selector, 1))
    tools = result.get("capabilities", {}).get("tools")
    if not isinstance(tools, dict):
        raise SmokeFailure(f"initialize response did not advertise tools capability: {result}")

    send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
    print("PASS initialize")


def list_tools(process: subprocess.Popen[str], selector: selectors.BaseSelector) -> None:
    send(process, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
    result = expect_no_error(read_response(process, selector, 2))
    tools = result.get("tools")
    if not isinstance(tools, list) or len(tools) != 8:
        raise SmokeFailure(f"expected exactly eight tools, got: {tools}")

    names = [tool.get("name") for tool in tools]
    if names != [
        "semantic_compile",
        "semantic_errors",
        "semantic_test",
        "semantic_effect_summary",
        "semantic_symbol_at",
        "semantic_symbols",
        "semantic_reconcile_symbol",
        "semantic_point_evidence",
    ]:
        raise SmokeFailure(f"expected MCP tools, got: {names}")
    for tool in tools:
        name = tool.get("name")
        required = tool.get("inputSchema", {}).get("required")
        if name in {"semantic_symbol_at", "semantic_point_evidence"}:
            expected = ["workspace", "file", "line", "col"]
        elif name == "semantic_symbols":
            expected = ["workspace", "semanticdb"]
        elif name == "semantic_reconcile_symbol":
            expected = ["workspace", "file", "line", "col", "semanticdb"]
        elif name == "semantic_effect_summary":
            expected = ["workspace", "file"]
        else:
            expected = ["workspace"]
        if required != expected:
            raise SmokeFailure(f"expected required inputs {expected} for {name}, got: {required}")
    print("PASS tools/list")


def call_tool(
    process: subprocess.Popen[str],
    selector: selectors.BaseSelector,
    request_id: int,
    tool_name: str,
    workspace: Path,
    extra_arguments: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    arguments: dict[str, Any] = {"workspace": str(workspace)}
    if extra_arguments is not None:
        arguments.update(extra_arguments)
    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": arguments,
            },
        },
    )
    return tool_result(read_response(process, selector, request_id))


def validate_success_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 3, "semantic_compile", root / "examples/scala3-compile-success")
    payload = structured.get("payload")
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"compile-success should be ok/isError false: {result}")
    if structured.get("schemaVersion") != COMPILE_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != COMPILE_SCHEMA or payload.get("success") is not True:
        raise SmokeFailure(f"compile-success payload mismatch: {payload}")
    print("PASS semantic_compile success workspace")


def validate_failure_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 4, "semantic_compile", root / "examples/scala3-compile-failure")
    payload = structured.get("payload")
    diagnostics = payload.get("diagnostics") if isinstance(payload, dict) else None
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"compile-failure should be domain success: {result}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != COMPILE_SCHEMA or payload.get("success") is not False:
        raise SmokeFailure(f"compile-failure payload mismatch: {payload}")
    if not isinstance(diagnostics, list) or not diagnostics:
        raise SmokeFailure(f"compile-failure diagnostics missing: {payload}")
    print("PASS semantic_compile failure workspace")


def validate_errors_success_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 5, "semantic_errors", root / "examples/scala3-compile-success")
    payload = structured.get("payload")
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"errors success workspace should be ok/isError false: {result}")
    if structured.get("schemaVersion") != ERRORS_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != ERRORS_SCHEMA or payload.get("success") is not True:
        raise SmokeFailure(f"errors success payload mismatch: {payload}")
    print("PASS semantic_errors success workspace")


def validate_errors_failure_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 6, "semantic_errors", root / "examples/scala3-compile-failure")
    payload = structured.get("payload")
    diagnostics = payload.get("diagnostics") if isinstance(payload, dict) else None
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"errors failure workspace should be domain success: {result}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != ERRORS_SCHEMA or payload.get("success") is not False:
        raise SmokeFailure(f"errors failure payload mismatch: {payload}")
    if not isinstance(diagnostics, list) or not diagnostics:
        raise SmokeFailure(f"errors failure diagnostics missing: {payload}")
    print("PASS semantic_errors failure workspace")


def validate_test_success_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 7, "semantic_test", root / "examples/scala3-compile-success")
    payload = structured.get("payload")
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"test success workspace should be ok/isError false: {result}")
    if structured.get("schemaVersion") != TEST_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != TEST_SCHEMA or payload.get("success") is not True:
        raise SmokeFailure(f"test success payload mismatch: {payload}")
    print("PASS semantic_test success workspace")


def validate_test_failure_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 8, "semantic_test", root / "examples/scala3-test-failure")
    payload = structured.get("payload")
    failures = payload.get("failures") if isinstance(payload, dict) else None
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"test failure workspace should be domain success: {result}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != TEST_SCHEMA or payload.get("success") is not False:
        raise SmokeFailure(f"test failure payload mismatch: {payload}")
    if payload.get("failed") != 1:
        raise SmokeFailure(f"test failure count mismatch: {payload}")
    if not isinstance(failures, list) or not failures:
        raise SmokeFailure(f"test failure diagnostics missing: {payload}")
    print("PASS semantic_test failure workspace")


def validate_effect_summary_fixture(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    file = "modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala"
    result, structured = call_tool(process, selector, 9, "semantic_effect_summary", root, {"file": file})
    payload = structured.get("payload")
    methods = payload.get("methods") if isinstance(payload, dict) else None
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"effect-summary fixture should be ok/isError false: {result}")
    if structured.get("schemaVersion") != EFFECT_SUMMARY_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != EFFECT_SUMMARY_SCHEMA:
        raise SmokeFailure(f"effect-summary payload mismatch: {payload}")
    source = payload.get("source")
    if not isinstance(source, str) or not source.endswith(file):
        raise SmokeFailure(f"effect-summary source mismatch: {payload}")
    if not isinstance(methods, list) or not methods:
        raise SmokeFailure(f"effect-summary methods missing: {payload}")

    names = {method.get("packageQualifiedName") for method in methods if isinstance(method, dict)}
    expected_names = {"example.UserRepo.find", "example.UserRepo.save", "example.UserRepo.maybe"}
    missing = expected_names - names
    if missing:
        raise SmokeFailure(f"effect-summary methods missing expected names {sorted(missing)}: {names}")
    print("PASS semantic_effect_summary fixture")


def validate_symbol_at_fixture(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    file = "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    result, structured = call_tool(process, selector, 10, "semantic_symbol_at", root, {"file": file, "line": 6, "col": 16})
    payload = structured.get("payload")
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"symbol-at fixture should be ok/isError false: {result}")
    if structured.get("schemaVersion") != SYMBOL_AT_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != SYMBOL_AT_SCHEMA:
        raise SmokeFailure(f"symbol-at payload mismatch: {payload}")
    if not isinstance(payload.get("symbol"), str) or not payload["symbol"]:
        raise SmokeFailure(f"symbol-at payload symbol missing: {payload}")
    if not isinstance(payload.get("displayName"), str) or not payload["displayName"]:
        raise SmokeFailure(f"symbol-at payload displayName missing: {payload}")
    source = payload.get("source")
    if not isinstance(source, str) or not source.endswith(file):
        raise SmokeFailure(f"symbol-at source mismatch: {payload}")
    print("PASS semantic_symbol_at fixture")


def validate_symbols_fixture(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    semanticdb = "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    result, structured = call_tool(process, selector, 33, "semantic_symbols", root, {"semanticdb": semanticdb})
    payload = structured.get("payload")
    symbols = payload.get("symbols") if isinstance(payload, dict) else None
    occurrences = payload.get("occurrences") if isinstance(payload, dict) else None
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"symbols fixture should be ok/isError false: {result}")
    if structured.get("schemaVersion") != SYMBOLS_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != SYMBOLS_SCHEMA:
        raise SmokeFailure(f"symbols payload mismatch: {payload}")
    uri = payload.get("uri")
    if not isinstance(uri, str) or not uri.endswith("Main.scala"):
        raise SmokeFailure(f"symbols uri mismatch: {payload}")
    if not isinstance(symbols, list) or not symbols:
        raise SmokeFailure(f"symbols list missing: {payload}")
    if not isinstance(occurrences, list) or not occurrences:
        raise SmokeFailure(f"occurrences list missing: {payload}")

    display_names = {symbol.get("displayName") for symbol in symbols if isinstance(symbol, dict)}
    if "Main" not in display_names:
        raise SmokeFailure(f"symbols fixture missing Main displayName: {display_names}")
    print("PASS semantic_symbols fixture")


def validate_reconcile_symbol_fixture(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    file = "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    semanticdb = "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    result, structured = call_tool(
        process,
        selector,
        35,
        "semantic_reconcile_symbol",
        root,
        {"file": file, "line": 6, "col": 16, "semanticdb": semanticdb},
    )
    payload = structured.get("payload")
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"reconcile fixture should be ok/isError false: {result}")
    if structured.get("schemaVersion") != RECONCILE_SCHEMA:
        raise SmokeFailure(f"wrong wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != RECONCILE_SCHEMA:
        raise SmokeFailure(f"reconcile payload mismatch: {payload}")
    source = payload.get("file")
    if not isinstance(source, str) or not source.endswith(file):
        raise SmokeFailure(f"reconcile file mismatch: {payload}")
    freshness = payload.get("freshness")
    if not isinstance(freshness, dict) or freshness.get("status") != "Unverifiable":
        raise SmokeFailure(f"expected Unverifiable reconcile freshness, got: {freshness}")
    if freshness.get("reason") != "NoUniqueDocumentForSource":
        raise SmokeFailure(f"unexpected reconcile qualification reason: {freshness}")
    outcome = payload.get("outcome")
    if not isinstance(outcome, dict) or outcome.get("status") != "NotAttempted":
        raise SmokeFailure(f"expected typed not-attempted reconcile outcome, got: {outcome}")
    if outcome.get("result") is not None or outcome.get("qualificationReason") is not None:
        raise SmokeFailure(f"unmapped reconcile fixture fabricated a completed result: {outcome}")
    if outcome.get("notAttemptedReason") != "SelectedArtifactChangedOrRemapped":
        raise SmokeFailure(f"unexpected reconcile not-attempted reason: {outcome}")
    print("PASS semantic_reconcile_symbol fixture")


def validate_point_evidence_fixture(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    source_fixture = root / "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    semanticdb_fixture = root / "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    with tempfile.TemporaryDirectory(prefix="semantic-scala-mcp-smoke-") as temporary:
        workspace = Path(temporary)
        semanticdb = workspace / "target/META-INF/semanticdb/Main.scala.semanticdb"
        semanticdb.parent.mkdir(parents=True)
        shutil.copyfile(source_fixture, workspace / "Main.scala")
        shutil.copyfile(semanticdb_fixture, semanticdb)
        result, structured = call_tool(
            process,
            selector,
            39,
            "semantic_point_evidence",
            workspace,
            {"file": "Main.scala", "line": 6, "col": 16},
        )
    payload = structured.get("payload")
    if result.get("isError") is not False or structured.get("ok") is not True:
        raise SmokeFailure(f"point-evidence fixture should be ok/isError false: {result}")
    if structured.get("schemaVersion") != POINT_EVIDENCE_SCHEMA:
        raise SmokeFailure(f"wrong point-evidence wrapper schemaVersion: {structured}")
    if not isinstance(payload, dict) or payload.get("schemaVersion") != POINT_EVIDENCE_SCHEMA:
        raise SmokeFailure(f"point-evidence payload mismatch: {payload}")
    position = payload.get("position")
    if not isinstance(position, dict) or position != {"line": 6, "column": 16, "encoding": "UTF-16"}:
        raise SmokeFailure(f"point-evidence position mismatch: {payload}")
    discovery = payload.get("discovery")
    if not isinstance(discovery, dict) or discovery.get("schemaVersion") != SEMANTICDB_FOR_SOURCE_SCHEMA:
        raise SmokeFailure(f"point-evidence discovery mismatch: {discovery}")
    if discovery.get("status") != "UniqueMatch" or len(discovery.get("matches", [])) != 1:
        raise SmokeFailure(f"point-evidence should discover one copied fixture: {discovery}")
    match = discovery["matches"][0]
    match_freshness = match.get("freshness") if isinstance(match, dict) else None
    if not isinstance(match_freshness, dict) or match_freshness.get("status") != "Unverifiable":
        raise SmokeFailure(f"copied fixture should be Unverifiable: {match}")
    if match_freshness.get("reason") != "MissingDocumentIdentity":
        raise SmokeFailure(f"unexpected copied-fixture qualification: {match_freshness}")
    selection = payload.get("selection")
    if not isinstance(selection, dict) or selection.get("status") != "SelectedUnverifiable":
        raise SmokeFailure(f"point-evidence selection mismatch: {selection}")
    live_point = payload.get("livePoint")
    live_result = live_point.get("result") if isinstance(live_point, dict) else None
    if not isinstance(live_point, dict) or live_point.get("status") != "Resolved":
        raise SmokeFailure(f"point-evidence live point mismatch: {live_point}")
    if not isinstance(live_result, dict) or not isinstance(live_result.get("symbol"), str) or not live_result["symbol"]:
        raise SmokeFailure(f"point-evidence live symbol missing: {live_result}")
    reconciliation = payload.get("reconciliation")
    if not isinstance(reconciliation, dict) or reconciliation.get("schemaVersion") != RECONCILE_SCHEMA:
        raise SmokeFailure(f"point-evidence reconciliation mismatch: {reconciliation}")
    freshness = reconciliation.get("freshness")
    if not isinstance(freshness, dict) or freshness.get("status") != "Unverifiable":
        raise SmokeFailure(f"point-evidence reconciliation freshness mismatch: {freshness}")
    outcome = reconciliation.get("outcome")
    reconciled = outcome.get("result") if isinstance(outcome, dict) else None
    if not isinstance(outcome, dict) or outcome.get("status") != "CompletedQualifiedUnverifiable":
        raise SmokeFailure(f"point-evidence qualified outcome missing: {outcome}")
    if outcome.get("qualificationReason") != "MissingDocumentIdentity" or outcome.get("notAttemptedReason") is not None:
        raise SmokeFailure(f"point-evidence qualification mismatch: {outcome}")
    if not isinstance(reconciled, dict) or reconciled.get("status") != "RangeMatchOnly":
        raise SmokeFailure(f"expected qualified RangeMatchOnly fixture result, got: {reconciled}")
    if not isinstance(reconciled.get("compilerSymbol"), str) or not reconciled["compilerSymbol"]:
        raise SmokeFailure(f"point-evidence compiler symbol missing: {reconciled}")
    print("PASS semantic_point_evidence fixture")


def validate_invalid_workspace(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 11, "semantic_test", root / "target/missing-mcp-workspace-task028")
    if result.get("isError") is not True or structured.get("ok") is not False:
        raise SmokeFailure(f"invalid workspace should be tool error: {result}")
    error = structured.get("error")
    if not isinstance(error, str) or "Workspace does not exist" not in error:
        raise SmokeFailure(f"invalid workspace error mismatch: {structured}")
    print("PASS semantic_test invalid workspace")


def validate_invalid_input(process: subprocess.Popen[str], selector: selectors.BaseSelector) -> None:
    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 12,
            "method": "tools/call",
            "params": {"name": "semantic_compile", "arguments": {}},
        },
    )
    expect_rpc_error(read_response(process, selector, 12), -32602)

    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 13,
            "method": "tools/call",
            "params": {"name": "semantic_test", "arguments": {"workspace": 42}},
        },
    )
    expect_rpc_error(read_response(process, selector, 13), -32602)

    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 14,
            "method": "tools/call",
            "params": {"name": "semantic_effect_summary", "arguments": {"workspace": str(repo_root())}},
        },
    )
    expect_rpc_error(read_response(process, selector, 14), -32602)

    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 15,
            "method": "tools/call",
            "params": {"name": "semantic_effect_summary", "arguments": {"workspace": str(repo_root()), "file": 42}},
        },
    )
    expect_rpc_error(read_response(process, selector, 15), -32602)

    symbol_file = "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    invalid_symbol_args = [
        {"workspace": str(repo_root()), "line": 6, "col": 16},
        {"workspace": str(repo_root()), "file": 42, "line": 6, "col": 16},
        {"workspace": str(repo_root()), "file": symbol_file, "col": 16},
        {"workspace": str(repo_root()), "file": symbol_file, "line": "6", "col": 16},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 0, "col": 16},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "col": "16"},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "col": 0},
    ]
    for offset, arguments in enumerate(invalid_symbol_args):
        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 16 + offset,
                "method": "tools/call",
                "params": {"name": "semantic_symbol_at", "arguments": arguments},
            },
        )
        expect_rpc_error(read_response(process, selector, 16 + offset), -32602)

    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 50,
            "method": "tools/call",
            "params": {"name": "semantic_symbols", "arguments": {"workspace": str(repo_root())}},
        },
    )
    expect_rpc_error(read_response(process, selector, 50), -32602)

    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 51,
            "method": "tools/call",
            "params": {"name": "semantic_symbols", "arguments": {"workspace": str(repo_root()), "semanticdb": 42}},
        },
    )
    expect_rpc_error(read_response(process, selector, 51), -32602)

    semanticdb = "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    invalid_reconcile_args = [
        {"workspace": str(repo_root()), "line": 6, "col": 16, "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": 42, "line": 6, "col": 16, "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "col": 16},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "col": 16, "semanticdb": 42},
        {"workspace": str(repo_root()), "file": symbol_file, "col": 16, "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": symbol_file, "line": "6", "col": 16, "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 0, "col": 16, "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "col": "16", "semanticdb": semanticdb},
        {"workspace": str(repo_root()), "file": symbol_file, "line": 6, "col": 0, "semanticdb": semanticdb},
    ]
    for offset, arguments in enumerate(invalid_reconcile_args):
        send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 60 + offset,
                "method": "tools/call",
                "params": {"name": "semantic_reconcile_symbol", "arguments": arguments},
            },
        )
        expect_rpc_error(read_response(process, selector, 60 + offset), -32602)
    print("PASS invalid input shape")


def validate_invalid_effect_summary_file(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 30, "semantic_effect_summary", root, {"file": "../outside.scala"})
    if result.get("isError") is not True or structured.get("ok") is not False:
        raise SmokeFailure(f"invalid effect-summary file should be tool error: {result}")
    error = structured.get("error")
    if not isinstance(error, str) or "File escapes workspace" not in error:
        raise SmokeFailure(f"invalid effect-summary file error mismatch: {structured}")
    print("PASS semantic_effect_summary invalid file")


def validate_invalid_symbol_at_file(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 31, "semantic_symbol_at", root, {"file": "../outside.scala", "line": 6, "col": 16})
    if result.get("isError") is not True or structured.get("ok") is not False:
        raise SmokeFailure(f"invalid symbol-at file should be tool error: {result}")
    error = structured.get("error")
    if not isinstance(error, str) or "File escapes workspace" not in error:
        raise SmokeFailure(f"invalid symbol-at file error mismatch: {structured}")
    print("PASS semantic_symbol_at invalid file")


def validate_invalid_symbol_at_position(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    file = "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 32,
            "method": "tools/call",
            "params": {
                "name": "semantic_symbol_at",
                "arguments": {"workspace": str(root), "file": file, "line": 0, "col": 16},
            },
        },
    )
    expect_rpc_error(read_response(process, selector, 32), -32602)
    print("PASS semantic_symbol_at invalid position")


def validate_invalid_symbols_file(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    result, structured = call_tool(process, selector, 34, "semantic_symbols", root, {"semanticdb": "../outside.semanticdb"})
    if result.get("isError") is not True or structured.get("ok") is not False:
        raise SmokeFailure(f"invalid symbols file should be tool error: {result}")
    error = structured.get("error")
    if not isinstance(error, str) or "SemanticDB file escapes workspace" not in error:
        raise SmokeFailure(f"invalid symbols file error mismatch: {structured}")
    print("PASS semantic_symbols invalid semanticdb")


def validate_invalid_reconcile_symbol_file(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    semanticdb = "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    result, structured = call_tool(
        process,
        selector,
        36,
        "semantic_reconcile_symbol",
        root,
        {"file": "../outside.scala", "line": 6, "col": 16, "semanticdb": semanticdb},
    )
    if result.get("isError") is not True or structured.get("ok") is not False:
        raise SmokeFailure(f"invalid reconcile file should be tool error: {result}")
    error = structured.get("error")
    if not isinstance(error, str) or "File escapes workspace" not in error:
        raise SmokeFailure(f"invalid reconcile file error mismatch: {structured}")
    print("PASS semantic_reconcile_symbol invalid file")


def validate_invalid_reconcile_symbol_semanticdb(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    file = "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    result, structured = call_tool(
        process,
        selector,
        37,
        "semantic_reconcile_symbol",
        root,
        {"file": file, "line": 6, "col": 16, "semanticdb": "../outside.semanticdb"},
    )
    if result.get("isError") is not True or structured.get("ok") is not False:
        raise SmokeFailure(f"invalid reconcile semanticdb should be tool error: {result}")
    error = structured.get("error")
    if not isinstance(error, str) or "SemanticDB file escapes workspace" not in error:
        raise SmokeFailure(f"invalid reconcile semanticdb error mismatch: {structured}")
    print("PASS semantic_reconcile_symbol invalid semanticdb")


def validate_invalid_reconcile_symbol_position(process: subprocess.Popen[str], selector: selectors.BaseSelector, root: Path) -> None:
    file = "modules/presentation-compiler/src/test/resources/presentation-fixtures/simple/Main.scala"
    semanticdb = "modules/semanticdb-reader/src/test/resources/semanticdb-fixtures/simple/Main.scala.semanticdb"
    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 38,
            "method": "tools/call",
            "params": {
                "name": "semantic_reconcile_symbol",
                "arguments": {"workspace": str(root), "file": file, "line": 0, "col": 16, "semanticdb": semanticdb},
            },
        },
    )
    expect_rpc_error(read_response(process, selector, 38), -32602)
    print("PASS semantic_reconcile_symbol invalid position")


def validate_unknown_tool(process: subprocess.Popen[str], selector: selectors.BaseSelector) -> None:
    send(
        process,
        {
            "jsonrpc": "2.0",
            "id": 99,
            "method": "tools/call",
            "params": {"name": "unknown_tool", "arguments": {}},
        },
    )
    expect_rpc_error(read_response(process, selector, 99), -32602)
    print("PASS unknown tool")


def run() -> None:
    root = repo_root()
    mcp_path = configured_path("SEMANTIC_SCALA_MCP", root / "modules/mcp-server/target/stage/bin/semantic-scala-mcp")
    cli_path = configured_path("SEMANTIC_SCALA_CLI", root / "modules/cli/target/stage/bin/semantic-scala")

    require_executable(mcp_path, "SEMANTIC_SCALA_MCP")
    require_executable(cli_path, "SEMANTIC_SCALA_CLI")

    process, stderr_lines = start_server(root, mcp_path, cli_path)
    try:
        if process.stdout is None:
            raise SmokeFailure("server stdout is unavailable")
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)

        initialize(process, selector)
        list_tools(process, selector)
        validate_success_workspace(process, selector, root)
        validate_failure_workspace(process, selector, root)
        validate_errors_success_workspace(process, selector, root)
        validate_errors_failure_workspace(process, selector, root)
        validate_test_success_workspace(process, selector, root)
        validate_test_failure_workspace(process, selector, root)
        validate_effect_summary_fixture(process, selector, root)
        validate_symbol_at_fixture(process, selector, root)
        validate_symbols_fixture(process, selector, root)
        validate_reconcile_symbol_fixture(process, selector, root)
        validate_point_evidence_fixture(process, selector, root)
        validate_invalid_workspace(process, selector, root)
        validate_invalid_input(process, selector)
        validate_invalid_effect_summary_file(process, selector, root)
        validate_invalid_symbol_at_file(process, selector, root)
        validate_invalid_symbol_at_position(process, selector, root)
        validate_invalid_symbols_file(process, selector, root)
        validate_invalid_reconcile_symbol_file(process, selector, root)
        validate_invalid_reconcile_symbol_semanticdb(process, selector, root)
        validate_invalid_reconcile_symbol_position(process, selector, root)
        validate_unknown_tool(process, selector)
    finally:
        stop_server(process)

    warning_count = len([line for line in stderr_lines if line.strip()])
    if warning_count:
        print(f"NOTE server stderr had {warning_count} line(s); stdout JSON-RPC validation still passed")
    print("PASS smoke-mcp-tools")


if __name__ == "__main__":
    try:
        run()
    except SmokeFailure as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
