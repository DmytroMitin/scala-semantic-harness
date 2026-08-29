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


V2 = load("semanticdb-for-source.v2.schema.json")
V4 = load("semanticdb-for-source.v4.schema.json")
REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema)) for schema in (V2, V4)
)


def report():
    return {
        "schemaVersion": "semantic-scala.semanticdb-for-source.v4",
        "workspace": "/workspace",
        "sourceFile": "/workspace/src/Main.scala",
        "sourceRelativePath": "src/Main.scala",
        "discovery": {
            "schemaVersion": "semantic-scala.semanticdb-for-source.v2",
            "workspace": "/workspace",
            "sourceFile": "/workspace/src/Main.scala",
            "sourceRelativePath": "src/Main.scala",
            "status": "Unavailable",
            "semanticdbFiles": 0,
            "parseableFiles": 0,
            "unparseableFiles": 0,
            "matches": [],
            "candidatesConsidered": 0,
            "warnings": [],
            "errors": [],
        },
        "targetContext": {
            "project": "kernelJVM",
            "configuration": "Compile",
            "status": "Acquired",
            "requestedScalaVersion": "3.3.7",
            "effectiveScalaVersion": "3.3.7",
            "scalaAxisStatus": "RequestedMatched",
            "targetJavaContext": "a" * 64,
            "acquisitionProfile": "RootOnlySourceMapping",
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
            "failure": None,
        },
        "targetSelection": {
            "status": "NoCandidateOwnedByTarget",
            "selectedArtifact": None,
            "ownedCandidates": [],
            "reason": "No matched candidate is target-owned",
        },
    }


class TargetAwareV4SchemaTest(unittest.TestCase):
    def test_closed_root_only_v4_payload(self):
        jsonschema.Draft202012Validator(V4, registry=REGISTRY).validate(report())

    def test_rejects_extra_fields_path_leaks_and_false_build_claims(self):
        for field, value in (
            ("classpath", ["/private/path"]),
            ("buildPerformed", "Performed"),
            ("acquisitionEffect", "CompiledTargetSources"),
        ):
            invalid = copy.deepcopy(report())
            invalid["targetContext"][field] = value
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.Draft202012Validator(V4, registry=REGISTRY).validate(invalid)

    def test_requested_axis_requires_effective_provenance(self):
        invalid = report()
        invalid["targetContext"]["effectiveScalaVersion"] = None
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.Draft202012Validator(V4, registry=REGISTRY).validate(invalid)


if __name__ == "__main__":
    unittest.main()
