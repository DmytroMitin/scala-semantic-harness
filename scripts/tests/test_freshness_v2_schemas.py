import copy
import json
import unittest
from pathlib import Path

import jsonschema


ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "modules" / "cli" / "src" / "main" / "resources" / "semantic-scala" / "schemas"


def load(name):
    return json.loads((SCHEMAS / name).read_text(encoding="utf-8"))


def evidence():
    return {
        "basis": "SemanticdbMd5Utf8",
        "documentUri": "src/main/scala/example/Main.scala",
        "documentIndex": 0,
        "semanticdbMd5": "0" * 32,
        "sourceMd5": "0" * 32,
        "sourceSnapshotSha256": "1" * 64,
        "artifactSnapshotSha256": "2" * 64,
        "sourceMtimeMillis": 1,
        "artifactMtimeMillis": 2,
        "detailCode": "SemanticdbMd5Compared",
    }


def freshness(status="Fresh"):
    value = {
        "status": status,
        "reason": None,
        "beforeSha256": None,
        "afterSha256": None,
        "evidence": evidence(),
    }
    if status == "Unverifiable":
        value["reason"] = "MissingDocumentIdentity"
    if status == "SourceChangedDuringRequest":
        value["beforeSha256"] = "1" * 64
        value["afterSha256"] = "3" * 64
    return value


def symbol_result():
    return {
        "semanticdbSymbol": "example/Main.answer.",
        "compilerSymbol": "example/Main.answer.",
        "displayName": "answer",
        "range": None,
        "status": "ExactMatch",
    }


def reconciliation(status="CompletedFresh", freshness_status="Fresh"):
    return {
        "schemaVersion": "semantic-scala.reconcile-symbol-result.v2",
        "file": "/workspace/src/main/scala/example/Main.scala",
        "semanticdb": "/workspace/target/Main.scala.semanticdb",
        "queryPosition": {"startLine": 2, "startCharacter": 6, "endLine": 2, "endCharacter": 6},
        "freshness": freshness(freshness_status),
        "outcome": {
            "status": status,
            "result": symbol_result() if status != "NotAttempted" else None,
            "qualificationReason": "MissingDocumentIdentity" if status == "CompletedQualifiedUnverifiable" else None,
            "notAttemptedReason": "StaleArtifact" if status == "NotAttempted" else None,
        },
    }


class FreshnessV2SchemaTest(unittest.TestCase):
    def test_reconcile_accepts_fresh_completed(self):
        jsonschema.validate(reconciliation(), load("reconcile-symbol-result.v2.schema.json"))

    def test_reconcile_rejects_stale_or_source_changed_completed_payload(self):
        schema = load("reconcile-symbol-result.v2.schema.json")
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(reconciliation(freshness_status="Stale"), schema)
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(reconciliation(freshness_status="SourceChangedDuringRequest"), schema)

    def test_reconcile_accepts_typed_stale_not_attempted(self):
        jsonschema.validate(
            reconciliation(status="NotAttempted", freshness_status="Stale"),
            load("reconcile-symbol-result.v2.schema.json"),
        )

    def test_discovery_rejects_wrong_version_and_unknown_fields(self):
        schema = load("semanticdb-for-source.v2.schema.json")
        report = {
            "schemaVersion": "semantic-scala.semanticdb-for-source.v2",
            "workspace": "/workspace",
            "sourceFile": "/workspace/src/main/scala/example/Main.scala",
            "sourceRelativePath": "src/main/scala/example/Main.scala",
            "status": "Unavailable",
            "semanticdbFiles": 0,
            "parseableFiles": 0,
            "unparseableFiles": 0,
            "matches": [],
            "candidatesConsidered": 0,
            "warnings": [],
            "errors": [],
        }
        jsonschema.validate(report, schema)
        wrong = copy.deepcopy(report)
        wrong["schemaVersion"] = "semantic-scala.semanticdb-for-source.v1"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(wrong, schema)
        extra = copy.deepcopy(report)
        extra["unknown"] = True
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(extra, schema)

    def test_point_rejects_wrong_nested_reconciliation_version(self):
        schema = load("point-evidence-result.v2.schema.json")
        discovery = {
            "schemaVersion": "semantic-scala.semanticdb-for-source.v2",
            "workspace": "/workspace",
            "sourceFile": "/workspace/src/main/scala/example/Main.scala",
            "sourceRelativePath": "src/main/scala/example/Main.scala",
            "status": "Unavailable",
            "semanticdbFiles": 0,
            "parseableFiles": 0,
            "unparseableFiles": 0,
            "matches": [],
            "candidatesConsidered": 0,
            "warnings": [],
            "errors": [],
        }
        point = {
            "schemaVersion": "semantic-scala.point-evidence-result.v2",
            "workspace": "/workspace",
            "sourceFile": "/workspace/src/main/scala/example/Main.scala",
            "position": {"line": 3, "column": 7, "encoding": "UTF-16"},
            "discovery": discovery,
            "selection": {"status": "NotSelectedUnavailable", "artifact": None, "reason": "unavailable"},
            "livePoint": {"status": "Unresolved", "result": {"schemaVersion": "semantic-scala.symbol-at-result.v1", "symbol": None, "displayName": None, "range": None, "source": "/workspace/src/main/scala/example/Main.scala"}, "reason": None},
            "reconciliation": reconciliation(status="NotAttempted", freshness_status="Stale"),
        }
        jsonschema.validate(point, schema)
        point["reconciliation"]["schemaVersion"] = "semantic-scala.reconcile-symbol-result.v1"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(point, schema)


if __name__ == "__main__":
    unittest.main()
