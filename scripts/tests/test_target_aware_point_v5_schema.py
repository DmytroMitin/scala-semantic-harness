import copy
import json
import runpy
import unittest
from pathlib import Path

import jsonschema
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "modules" / "cli" / "src" / "main" / "resources" / "semantic-scala" / "schemas"
V4_TEST = runpy.run_path(str(Path(__file__).with_name("test_target_aware_point_v4_schema.py")))


def load(name):
    return json.loads((SCHEMAS / name).read_text(encoding="utf-8"))


V2_SOURCE = load("semanticdb-for-source.v2.schema.json")
V2_RECONCILIATION = load("reconcile-symbol-result.v2.schema.json")
V4_POINT = load("point-evidence-result.v4.schema.json")
V5_POINT = load("point-evidence-result.v5.schema.json")
REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema))
    for schema in (V2_SOURCE, V2_RECONCILIATION, V4_POINT, V5_POINT)
)


def validate(instance):
    jsonschema.Draft202012Validator(V5_POINT, registry=REGISTRY).validate(instance)


def report():
    value = V4_TEST["report"](class_present=True, live_status="Resolved")
    value["schemaVersion"] = "semantic-scala.point-evidence-result.v5"
    value["targetContext"].update({
        "acquisitionProfile": "PartialExistingCompileOutputPointContext",
        "classpathBasis": "ExistingSelectedAndInternalCompileOutputsPlusExternalDependencies",
        "contextCompleteness": "PartialExistingCompileOutputs",
        "internalDependencies": [
            {
                "projectRef": "ThisBuild/macrosJVM",
                "project": "macrosJVM",
                "role": "Direct",
                "compileMapping": "DefaultCompileToCompile",
                "requestedScalaVersion": "3.3.7",
                "effectiveScalaVersion": "3.3.7",
                "scalaAxisStatus": "RequestedMatched",
                "configuration": "Compile",
                "classDirectory": "macros/.jvm/target/scala-3.3/classes",
                "directoryStatus": "PresentIncluded",
                "contributedToPresentationCompilerContext": True,
                "acquisitionEffect": "DependencySourceOutputsNotRequested",
            },
            {
                "projectRef": "ThisBuild/coreJVM",
                "project": "coreJVM",
                "role": "Transitive",
                "compileMapping": "ExplicitCompileToCompile",
                "requestedScalaVersion": "3.3.7",
                "effectiveScalaVersion": "3.3.7",
                "scalaAxisStatus": "RequestedMatched",
                "configuration": "Compile",
                "classDirectory": "core/.jvm/target/scala-3.3/classes",
                "directoryStatus": "AbsentNotIncluded",
                "contributedToPresentationCompilerContext": False,
                "acquisitionEffect": "DependencySourceOutputsNotRequested",
            },
        ],
        "internalDependencyExclusions": [],
        "internalDependencyDiscoveredCount": 2,
        "internalDependencyPresentIncludedCount": 1,
        "internalDependencyAbsentNotIncludedCount": 1,
        "internalDependencyUnavailableUnsafeCount": 0,
        "internalDependencyExcludedCount": 0,
        "presentationCompilerContextEntryCount": 8,
    })
    return value


class TargetAwarePointV5SchemaTest(unittest.TestCase):
    def test_v5_accepts_ordered_present_and_absent_internal_receipt(self):
        validate(report())

        cycle = report()
        cycle["targetContext"]["internalDependencyExclusions"] = [{
            "projectRef": "ThisBuild/examples",
            "project": "examples",
            "role": "Transitive",
            "compileMapping": "DefaultCompileToCompile",
            "reason": "CycleToSelectedOrActiveProject",
            "effectiveScalaVersion": None,
        }]
        cycle["targetContext"]["internalDependencyExcludedCount"] = 1
        validate(cycle)

        foreign = report()
        foreign["targetContext"]["internalDependencyExclusions"] = [{
            "projectRef": "ExternalBuild/foreignCore",
            "project": "foreignCore",
            "role": "Direct",
            "compileMapping": "DefaultCompileToCompile",
            "reason": "ForeignBuildProjectRef",
            "effectiveScalaVersion": None,
        }]
        foreign["targetContext"]["internalDependencyExcludedCount"] = 1
        validate(foreign)

    def test_v5_rejects_complete_claims_raw_paths_invalid_counts_and_wrong_literals(self):
        complete = report()
        complete["targetContext"]["contextCompleteness"] = "Complete"
        with self.assertRaises(jsonschema.ValidationError):
            validate(complete)

        raw = report()
        raw["targetContext"]["internalDependencies"][0]["classDirectory"] = "/private/cache/classes"
        with self.assertRaises(jsonschema.ValidationError):
            validate(raw)

        wrong_count = report()
        wrong_count["targetContext"]["internalDependencyPresentIncludedCount"] = -1
        with self.assertRaises(jsonschema.ValidationError):
            validate(wrong_count)

        missing_count = report()
        missing_count["targetContext"]["internalDependencyPresentIncludedCount"] = None
        with self.assertRaises(jsonschema.ValidationError):
            validate(missing_count)

        wrong_basis = report()
        wrong_basis["targetContext"]["classpathBasis"] = "ExistingSelectedClassDirectoryPlusExternalDependencies"
        with self.assertRaises(jsonschema.ValidationError):
            validate(wrong_basis)

        foreign_included = report()
        foreign_included["targetContext"]["internalDependencies"][0]["projectRef"] = "ExternalBuild/macrosJVM"
        with self.assertRaises(jsonschema.ValidationError):
            validate(foreign_included)

        numeric_project = report()
        numeric_project["targetContext"]["internalDependencies"][0]["project"] = "1macros"
        numeric_project["targetContext"]["internalDependencies"][0]["projectRef"] = "ThisBuild/1macros"
        with self.assertRaises(jsonschema.ValidationError):
            validate(numeric_project)

        invalid_reason = report()
        invalid_reason["targetContext"]["internalDependencyExclusions"] = [{
            "projectRef": "ThisBuild/coreJVM",
            "project": "coreJVM",
            "role": "Direct",
            "compileMapping": "DefaultCompileToCompile",
            "reason": "InventedReason",
            "effectiveScalaVersion": None,
        }]
        invalid_reason["targetContext"]["internalDependencyExcludedCount"] = 1
        with self.assertRaises(jsonschema.ValidationError):
            validate(invalid_reason)

    def test_v4_contract_still_rejects_v5_fields_and_schema(self):
        v4_validator = jsonschema.Draft202012Validator(V4_POINT, registry=REGISTRY)
        v5 = report()
        with self.assertRaises(jsonschema.ValidationError):
            v4_validator.validate(v5)

        v4 = V4_TEST["report"]()
        v4["targetContext"]["internalDependencies"] = []
        with self.assertRaises(jsonschema.ValidationError):
            v4_validator.validate(v4)


if __name__ == "__main__":
    unittest.main()
