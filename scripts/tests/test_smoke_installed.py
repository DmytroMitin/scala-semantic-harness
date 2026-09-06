import json
from pathlib import Path
import runpy
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = runpy.run_path(str(ROOT / "scripts/distribution/smoke-installed.py"))
SmokeError = SCRIPT["SmokeError"]
validate_cli_effect_summary = SCRIPT["validate_cli_effect_summary"]


class InstalledSmokeTest(unittest.TestCase):
    def test_accepts_a_nonempty_cli_effect_summary(self) -> None:
        payload = {
            "schemaVersion": "semantic-scala.effect-summary.v1",
            "methods": [{"name": "find"}],
        }

        self.assertEqual(
            validate_cli_effect_summary(json.dumps(payload)),
            "semantic-scala.effect-summary.v1",
        )

    def test_rejects_a_wrong_or_empty_cli_effect_summary(self) -> None:
        for payload in (
            {"schemaVersion": "other", "methods": [{"name": "find"}]},
            {"schemaVersion": "semantic-scala.effect-summary.v1", "methods": []},
        ):
            with self.subTest(payload=payload):
                with self.assertRaises(SmokeError):
                    validate_cli_effect_summary(json.dumps(payload))


if __name__ == "__main__":
    unittest.main()
