#!/usr/bin/env python3
"""Regression guard for the bounded public-evidence manifest."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "benchmarks" / "validate-public-evidence.py"


class ValidatePublicEvidenceTest(unittest.TestCase):
    def test_current_manifest_passes_full_validator(self) -> None:
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"

        result = subprocess.run(
            [sys.executable, str(VALIDATOR)],
            cwd=ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("public-evidence validation passed:", result.stdout)


if __name__ == "__main__":
    unittest.main()
