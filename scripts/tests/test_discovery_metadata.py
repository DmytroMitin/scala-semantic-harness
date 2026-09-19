#!/usr/bin/env python3

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "distribution" / "discovery" / "metadata.json"
CHANNEL = ROOT / "distribution" / "coursier" / "channel.json"
CANONICAL_SKILL = ROOT / "skills" / "semantic-scala" / "SKILL.md"


class DiscoveryMetadataTest(unittest.TestCase):
    def test_metadata_matches_the_supported_public_contract(self) -> None:
        metadata = json.loads(METADATA.read_text(encoding="utf-8"))
        channel = json.loads(CHANNEL.read_text(encoding="utf-8"))

        self.assertEqual(metadata["schemaVersion"], "semantic-scala.discovery.v1")
        self.assertEqual(metadata["name"], "semantic-scala")
        self.assertEqual(metadata["license"], "Apache-2.0")
        self.assertEqual(
            metadata["repository"],
            "https://github.com/DmytroMitin/scala-semantic-harness",
        )
        self.assertEqual(metadata["supportedVersion"], "0.1.0-alpha.3")
        self.assertEqual(metadata["mcp"]["transport"], "stdio")
        self.assertEqual(metadata["mcp"]["toolCount"], 8)
        self.assertEqual(metadata["mcp"]["command"], "semantic-scala-mcp")
        self.assertEqual(metadata["skill"]["name"], "semantic-scala")
        self.assertEqual(
            metadata["skill"]["canonicalPath"],
            "skills/semantic-scala/SKILL.md",
        )
        self.assertTrue(CANONICAL_SKILL.is_file())

        applications = metadata["distribution"]["applications"]
        self.assertEqual(applications, ["semantic-scala", "semantic-scala-mcp"])
        for application in applications:
            dependencies = channel[application]["dependencies"]
            self.assertEqual(len(dependencies), 1)
            self.assertTrue(dependencies[0].endswith(":0.1.0-alpha.3"))

    def test_install_command_selects_the_canonical_repository_skill(self) -> None:
        metadata = json.loads(METADATA.read_text(encoding="utf-8"))
        command = metadata["skill"]["installCommand"]

        self.assertEqual(
            command,
            "npx skills add "
            "https://github.com/DmytroMitin/scala-semantic-harness "
            "--skill semantic-scala",
        )


if __name__ == "__main__":
    unittest.main()
