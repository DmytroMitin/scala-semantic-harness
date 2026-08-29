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
V4_POINT = load("point-evidence-result.v4.schema.json")
REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema))
    for schema in (V2_SOURCE, V2_RECONCILIATION, V4_POINT)
)


def validate(instance):
    jsonschema.Draft202012Validator(V4_POINT, registry=REGISTRY).validate(instance)


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


def report(class_present=True, live_status="Resolved"):
    live_result = {
        "schemaVersion": "semantic-scala.symbol-at-result.v1",
        "symbol": "example/Main.answer." if live_status == "Resolved" else None,
        "displayName": "answer" if live_status == "Resolved" else None,
        "range": {
            "startLine": 2,
            "startCharacter": 2,
            "endLine": 2,
            "endCharacter": 12,
        } if live_status == "Resolved" else None,
        "source": "/workspace/src/main/scala/example/Main.scala",
    }
    return {
        "schemaVersion": "semantic-scala.point-evidence-result.v4",
        "workspace": "/workspace",
        "sourceFile": "/workspace/src/main/scala/example/Main.scala",
        "position": {"line": 3, "column": 7, "encoding": "UTF-16"},
        "discovery": discovery(),
        "targetContext": {
            "project": "kernelJVM",
            "configuration": "Compile",
            "status": "Acquired",
            "requestedScalaVersion": "3.3.7",
            "effectiveScalaVersion": "3.3.7",
            "scalaAxisStatus": "RequestedMatched",
            "targetJavaContext": "a" * 64,
            "acquisitionProfile": "PartialExistingOutputPointContext",
            "acquisitionEffect": "TargetSourceOutputsNotRequested",
            "buildPerformed": "NotRequested",
            "possibleEffects": [
                "BuildDefinitionOrPluginLoading",
                "DependencyResolution",
                "MetadataOrCacheWrites",
            ],
            "pathStatus": "Represented",
            "classDirectory": "kernel/jvm/classes",
            "semanticdbTargetRoot": "kernel/jvm/meta",
            "selectedClassDirectoryStatus": "PresentIncluded" if class_present else "AbsentNotIncluded",
            "compiledOutputFreshness": "NotAssessed",
            "classpathBasis": "ExistingSelectedClassDirectoryPlusExternalDependencies",
            "externalDependencyEntryCount": 6,
            "presentationCompilerContextEntryCount": 7 if class_present else 6,
            "contextCompleteness": "PartialExistingOutputs",
            "failure": None,
        },
        "targetSelection": {
            "status": "NoCandidateOwnedByTarget",
            "selectedArtifact": None,
            "ownedCandidates": [],
            "reason": "No matched SemanticDB candidate is owned by the selected target",
        },
        "livePoint": {"status": live_status, "result": live_result, "reason": None},
        "reconciliation": {
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
        },
    }


class TargetAwarePointV4SchemaTest(unittest.TestCase):
    def test_prepared_and_missing_output_reports_remain_closed_partial_v4(self):
        validate(report(class_present=True, live_status="Resolved"))
        validate(report(class_present=False, live_status="Unresolved"))

    def test_extra_raw_paths_wrong_partiality_and_invalid_presence_are_rejected(self):
        raw = report()
        raw["targetContext"]["classpath"] = ["/private/cache/dependency.jar"]
        with self.assertRaises(jsonschema.ValidationError):
            validate(raw)

        complete = report()
        complete["targetContext"]["contextCompleteness"] = "Complete"
        with self.assertRaises(jsonschema.ValidationError):
            validate(complete)

        absent = report(class_present=False)
        absent["targetContext"]["selectedClassDirectoryStatus"] = "PresentButExcluded"
        with self.assertRaises(jsonschema.ValidationError):
            validate(absent)

    def test_failure_context_cannot_claim_paths_counts_or_acquired_axis(self):
        failed = report()
        failed["targetContext"].update({
            "status": "UnknownProject",
            "effectiveScalaVersion": None,
            "scalaAxisStatus": "Unavailable",
            "targetJavaContext": None,
            "pathStatus": "UnavailableUnsafeOrNonUnique",
            "classDirectory": None,
            "semanticdbTargetRoot": None,
            "selectedClassDirectoryStatus": "UnavailableUnsafe",
            "externalDependencyEntryCount": None,
            "presentationCompilerContextEntryCount": None,
            "failure": "UnknownProject",
        })
        validate(failed)

        invalid = copy.deepcopy(failed)
        invalid["targetContext"]["classDirectory"] = "target/classes"
        with self.assertRaises(jsonschema.ValidationError):
            validate(invalid)


if __name__ == "__main__":
    unittest.main()
