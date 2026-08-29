import copy
import json
import unittest
from pathlib import Path

import jsonschema
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "modules" / "cli" / "src" / "main" / "resources" / "semantic-scala" / "schemas"


def load(name):
    return json.loads((SCHEMAS / name).read_text(encoding="utf-8"))


V2_SOURCE = load("semanticdb-for-source.v2.schema.json")
V2_RECONCILIATION = load("reconcile-symbol-result.v2.schema.json")
V3_SOURCE = load("semanticdb-for-source.v3.schema.json")
V3_POINT = load("point-evidence-result.v3.schema.json")
REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema))
    for schema in (V2_SOURCE, V2_RECONCILIATION, V3_SOURCE, V3_POINT)
)


def validate(instance, schema):
    jsonschema.Draft202012Validator(schema, registry=REGISTRY).validate(instance)


def discovery():
    return {
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


def target_context():
    return {
        "project": "kernelJVM",
        "configuration": "Compile",
        "status": "Acquired",
        "acquisitionOrigin": "FreshSbtEvaluation",
        "pathProvenanceStatus": "Represented",
        "classDirectory": "kernel/jvm/classes",
        "semanticdbTargetRoot": "kernel/jvm/meta",
        "classpathEntryCount": 6,
        "scalaVersion": "3.3.7",
        "targetJavaContext": "a" * 64,
        "failure": None,
    }


def target_selection():
    return {
        "status": "NoCandidateOwnedByTarget",
        "selectedArtifact": None,
        "ownedCandidates": [],
        "reason": "No matched SemanticDB candidate is owned by the selected target",
    }


def source_report():
    return {
        "schemaVersion": "semantic-scala.semanticdb-for-source.v3",
        "workspace": "/workspace",
        "sourceFile": "/workspace/src/main/scala/example/Main.scala",
        "sourceRelativePath": "src/main/scala/example/Main.scala",
        "discovery": discovery(),
        "targetContext": target_context(),
        "targetSelection": target_selection(),
    }


def reconciliation():
    return {
        "schemaVersion": "semantic-scala.reconcile-symbol-result.v2",
        "file": "/workspace/src/main/scala/example/Main.scala",
        "semanticdb": None,
        "queryPosition": {
            "startLine": 2,
            "startCharacter": 6,
            "endLine": 2,
            "endCharacter": 6,
        },
        "freshness": None,
        "outcome": {
            "status": "NotAttempted",
            "result": None,
            "qualificationReason": None,
            "notAttemptedReason": "NoArtifactCandidate",
        },
    }


def point_report():
    source = source_report()
    return {
        "schemaVersion": "semantic-scala.point-evidence-result.v3",
        "workspace": source["workspace"],
        "sourceFile": source["sourceFile"],
        "position": {"line": 3, "column": 7, "encoding": "UTF-16"},
        "discovery": source["discovery"],
        "targetContext": source["targetContext"],
        "targetSelection": source["targetSelection"],
        "livePoint": {
            "status": "Unavailable",
            "result": None,
            "reason": "Target-aligned live point evidence was not available",
        },
        "reconciliation": reconciliation(),
    }


class TargetAwareV3SchemaTest(unittest.TestCase):
    def test_source_and_point_reports_accept_closed_v3_payloads(self):
        validate(source_report(), V3_SOURCE)
        validate(point_report(), V3_POINT)

    def test_unknown_fields_and_wrong_versions_are_rejected(self):
        extra = source_report()
        extra["targetContext"]["javaHome"] = "/private/jdk"
        with self.assertRaises(jsonschema.ValidationError):
            validate(extra, V3_SOURCE)

        wrong = point_report()
        wrong["schemaVersion"] = "semantic-scala.point-evidence-result.v2"
        with self.assertRaises(jsonschema.ValidationError):
            validate(wrong, V3_POINT)

        nested = point_report()
        nested["discovery"]["schemaVersion"] = "semantic-scala.semanticdb-for-source.v3"
        with self.assertRaises(jsonschema.ValidationError):
            validate(nested, V3_POINT)

    def test_failure_context_cannot_claim_acquired_provenance(self):
        failed = source_report()
        failed["targetContext"] = {
            "project": "missing",
            "configuration": "Compile",
            "status": "UnknownProject",
            "acquisitionOrigin": None,
            "pathProvenanceStatus": "UnavailableUnsafe",
            "classDirectory": None,
            "semanticdbTargetRoot": None,
            "classpathEntryCount": None,
            "scalaVersion": None,
            "targetJavaContext": None,
            "failure": "unknown project",
        }
        failed["targetSelection"] = {
            "status": "UnknownProject",
            "selectedArtifact": None,
            "ownedCandidates": [],
            "reason": "The selected sbt project is unknown",
        }
        validate(failed, V3_SOURCE)

        invalid = copy.deepcopy(failed)
        invalid["targetContext"]["semanticdbTargetRoot"] = "target/meta"
        with self.assertRaises(jsonschema.ValidationError):
            validate(invalid, V3_SOURCE)


if __name__ == "__main__":
    unittest.main()
