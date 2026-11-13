"""Regression: task workflows must respect Google Workflows CEL string() types.

The compiled Rust runtime implements Google Workflows' string(number|boolean)
contract. Passing an already-string tool name, task field, or status to
string() is a TypeError and prevents tool dispatch.
"""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1] / 'src/main/scala/us/awfl/workflows'


class TaskToolCelContractTests(unittest.TestCase):
    def test_task_dispatch_uses_string_tool_name_directly(self):
        source = (ROOT / 'tools/Tasks.scala').read_text()
        self.assertIn('val nameStr: Cel = nameV.cel', source)
        self.assertNotRegex(source, r'core\.string\s*\(\s*nameV\s*\)')

    def test_task_labels_are_not_cast_from_strings(self):
        source = (ROOT / 'helpers/Tasks.scala').read_text()
        for field in ('id', 'title', 'description', 'status'):
            self.assertNotRegex(
                source, rf'core\.string\s*\(\s*t\.flatMap\(_\.{field}\)\.cel',
            )
        # created is numeric, so this conversion remains valid.
        self.assertIn('core.string( t.flatMap(_.created).cel)', source)
        self.assertIn('json.encode_to_string(body.cel)', source)

    def test_task_update_status_is_compared_as_string(self):
        source = (ROOT / 'helpers/tasks/TaskRunners.scala').read_text()
        self.assertIn('updatedStatus.cel === ("Done": Cel)', source)
        self.assertNotRegex(source, r'core\.string\s*\(\s*updatedStatus\.cel')
        self.assertIn('json.encode_to_string(err.cel)', source)


if __name__ == '__main__':
    unittest.main()
