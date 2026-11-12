"""Regression checks for CLI tool-result callbacks in generated Workflows YAML.

Run after generating YAML: python3 -m unittest discover -s tests
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ToolCallbackContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cli = (ROOT / "yaml_gens/tools-CliTools.yaml").read_text()
        cls.dispatcher = (
            ROOT / "yaml_gens/helpers-ToolDispatcher.yaml"
        ).read_text()

    def test_tool_error_maps_remain_results(self):
        self.assertIn("events.await_callback", self.cli)
        self.assertIn("json.encode_to_string", self.cli)
        self.assertNotIn("- checkError:", self.cli)
        self.assertIn("createCallback_try_updateResult:", self.cli)

    def test_callback_infrastructure_errors_remain_fatal(self):
        self.assertIn("- ReRaise_error:", self.cli)
        self.assertIn("raise: \"${mainTryError}\"", self.cli)
        self.assertIn("raise: \"${createCallback_tryError}\"", self.cli)

    def test_dispatcher_persists_tool_results(self):
        self.assertIn("saveResult", self.dispatcher)
        self.assertIn("callToolWorkflow", self.dispatcher)
        self.assertIn("encoded", self.dispatcher)


if __name__ == "__main__":
    unittest.main()
