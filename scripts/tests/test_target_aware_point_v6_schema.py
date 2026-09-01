import copy
import json
import runpy
import unittest
from pathlib import Path

import jsonschema
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "modules" / "cli" / "src" / "main" / "resources" / "semantic-scala" / "schemas"
V5_TEST = runpy.run_path(str(Path(__file__).with_name("test_target_aware_point_v5_schema.py")))


def load(name):
    return json.loads((SCHEMAS / name).read_text(encoding="utf-8"))


V2_SOURCE = load("semanticdb-for-source.v2.schema.json")
V2_RECONCILIATION = load("reconcile-symbol-result.v2.schema.json")
V4_POINT = load("point-evidence-result.v4.schema.json")
V5_POINT = load("point-evidence-result.v5.schema.json")
V6_POINT = load("point-evidence-result.v6.schema.json")
REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema))
    for schema in (V2_SOURCE, V2_RECONCILIATION, V4_POINT, V5_POINT, V6_POINT)
)


def validate(instance):
    jsonschema.Draft202012Validator(V6_POINT, registry=REGISTRY).validate(instance)


def report():
    value = V5_TEST["report"]()
    value["schemaVersion"] = "semantic-scala.point-evidence-result.v6"
    context = value["targetContext"]
    context.update({
        "acquisitionProfile": "StrictFreshInternalCompileOutputPointContext",
        "classpathBasis": "ExistingSelectedAndFreshInternalCompileOutputsPlusExternalDependencies",
        "internalDependencyFreshIncludedCount": 1,
        "internalDependencyStaleExcludedCount": 0,
        "internalDependencyUnverifiableExcludedCount": 0,
    })
    context.pop("internalDependencyPresentIncludedCount")
    fresh, absent = context["internalDependencies"]
    fresh.update({
        "analysisFile": "macros/.jvm/target/scala-3.3/zinc/inc_compile_3.zip",
        "directoryStatus": "PresentFreshIncluded",
        "freshnessStatus": "Fresh",
        "freshnessReason": "SourceAndProductContentMatch",
        "recordedSourceCount": 8,
        "recordedProductCount": 211,
    })
    absent.update({
        "analysisFile": "core/.jvm/target/scala-3.3/zinc/inc_compile_3.zip",
        "directoryStatus": "AbsentNotIncluded",
        "freshnessStatus": "Unverifiable",
        "freshnessReason": "DependencyClassDirectoryAbsent",
        "recordedSourceCount": None,
        "recordedProductCount": None,
    })
    return value


class TargetAwarePointV6SchemaTest(unittest.TestCase):
    def test_v6_accepts_fresh_included_and_stale_or_absent_excluded_states(self):
        validate(report())

        stale = report()
        dependency = stale["targetContext"]["internalDependencies"][0]
        dependency.update({
            "directoryStatus": "PresentStaleExcluded",
            "freshnessStatus": "Stale",
            "freshnessReason": "SourceContentMismatch",
            "contributedToPresentationCompilerContext": False,
        })
        stale["targetContext"]["internalDependencyFreshIncludedCount"] = 0
        stale["targetContext"]["internalDependencyStaleExcludedCount"] = 1
        validate(stale)

    def test_v6_rejects_false_fresh_contribution_and_private_paths(self):
        stale_contributes = report()
        dependency = stale_contributes["targetContext"]["internalDependencies"][0]
        dependency.update({
            "directoryStatus": "PresentStaleExcluded",
            "freshnessStatus": "Stale",
            "freshnessReason": "SourceContentMismatch",
            "contributedToPresentationCompilerContext": True,
        })
        with self.assertRaises(jsonschema.ValidationError):
            validate(stale_contributes)

        fabricated_fresh = report()
        dependency = fabricated_fresh["targetContext"]["internalDependencies"][0]
        dependency["freshnessReason"] = "AnalysisFileMissing"
        with self.assertRaises(jsonschema.ValidationError):
            validate(fabricated_fresh)

        private_analysis = report()
        private_analysis["targetContext"]["internalDependencies"][0]["analysisFile"] = "/private/cache/inc_compile.zip"
        with self.assertRaises(jsonschema.ValidationError):
            validate(private_analysis)

        windows_traversal = report()
        windows_traversal["targetContext"]["internalDependencies"][0]["analysisFile"] = "macros\\..\\private.zip"
        with self.assertRaises(jsonschema.ValidationError):
            validate(windows_traversal)

        complete = report()
        complete["targetContext"]["contextCompleteness"] = "Complete"
        with self.assertRaises(jsonschema.ValidationError):
            validate(complete)

        absent_wrong_reason = report()
        absent_wrong_reason["targetContext"]["internalDependencies"][1]["freshnessReason"] = "AnalysisFileMissing"
        with self.assertRaises(jsonschema.ValidationError):
            validate(absent_wrong_reason)

        stale_without_counts = report()
        dependency = stale_without_counts["targetContext"]["internalDependencies"][0]
        dependency.update({
            "directoryStatus": "PresentStaleExcluded",
            "freshnessStatus": "Stale",
            "freshnessReason": "ProductContentMismatch",
            "recordedSourceCount": None,
            "recordedProductCount": None,
            "contributedToPresentationCompilerContext": False,
        })
        with self.assertRaises(jsonschema.ValidationError):
            validate(stale_without_counts)

        missing_without_analysis = report()
        dependency = missing_without_analysis["targetContext"]["internalDependencies"][0]
        dependency.update({
            "directoryStatus": "PresentUnverifiableExcluded",
            "freshnessStatus": "Unverifiable",
            "freshnessReason": "AnalysisFileMissing",
            "analysisFile": None,
            "recordedSourceCount": None,
            "recordedProductCount": None,
            "contributedToPresentationCompilerContext": False,
        })
        with self.assertRaises(jsonschema.ValidationError):
            validate(missing_without_analysis)

        post_parse_without_counts = report()
        dependency = post_parse_without_counts["targetContext"]["internalDependencies"][0]
        dependency.update({
            "directoryStatus": "PresentUnverifiableExcluded",
            "freshnessStatus": "Unverifiable",
            "freshnessReason": "GeneratedOrManagedSourceUnbounded",
            "recordedSourceCount": None,
            "recordedProductCount": None,
            "contributedToPresentationCompilerContext": False,
        })
        with self.assertRaises(jsonschema.ValidationError):
            validate(post_parse_without_counts)

    def test_v6_rejects_fabricated_inventory_on_unavailable_top_level_context(self):
        unavailable = report()
        context = unavailable["targetContext"]
        context["pathStatus"] = "UnavailableUnsafeOrNonUnique"
        context["failure"] = "unsafe roots"
        with self.assertRaises(jsonschema.ValidationError):
            validate(unavailable)

    def test_v5_schema_rejects_v6_fields_and_route(self):
        validator = jsonschema.Draft202012Validator(V5_POINT, registry=REGISTRY)
        with self.assertRaises(jsonschema.ValidationError):
            validator.validate(report())

    def test_v6_rejects_top_level_axis_project_effect_and_count_fabrication(self):
        cases = []
        invalid_project = report()
        invalid_project["targetContext"]["project"] = "../kernelJVM"
        cases.append(invalid_project)

        invalid_axis = report()
        invalid_axis["targetContext"]["scalaAxisStatus"] = "SwitchFailure"
        cases.append(invalid_axis)

        default_with_request = report()
        default_with_request["targetContext"]["scalaAxisStatus"] = "BuildDefault"
        cases.append(default_with_request)

        invalid_effects = report()
        invalid_effects["targetContext"]["possibleEffects"] = ["DependencyResolution"]
        cases.append(invalid_effects)

        invalid_java = report()
        invalid_java["targetContext"]["targetJavaContext"] = "not-a-token"
        cases.append(invalid_java)

        negative_count = report()
        negative_count["targetContext"]["internalDependencyFreshIncludedCount"] = -1
        cases.append(negative_count)

        for value in cases:
            with self.assertRaises(jsonschema.ValidationError):
                validate(value)


if __name__ == "__main__":
    unittest.main()
