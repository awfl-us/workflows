"""Regression: CLI tool callbacks must start GKE consumers with a named profile."""
import unittest
from pathlib import Path


class CliProducerProfileTests(unittest.TestCase):
    def test_compiled_cli_workflow_requests_named_lightweight_image(self):
        generated = Path(__file__).resolve().parents[1] / "yaml_gens/tools-CliTools.yaml"
        if not generated.is_file():
            self.skipTest("Generate tools-CliTools via sbt before checking emitted YAML")
        yaml = generated.read_text()
        start = yaml.index('startProducer_call:')
        request = yaml[start:start + 850]
        self.assertIn('imageProfile: "${\\"lightweight\\"}"', request)
        self.assertNotIn('image: "${null}"', request)
        self.assertNotIn('imageProfile: "${lightweight}"', request)


if __name__ == "__main__":
    unittest.main()
