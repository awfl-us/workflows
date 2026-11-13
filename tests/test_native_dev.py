"""Portable native publishing validation without Docker or GCP credentials."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/native-dev.py"
spec = importlib.util.spec_from_file_location("native_dev", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeDevTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native-dev-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = "$" + "{WORKFLOW_ENV}"

    def crate(self, name, refs=(), ops=("http.post",)):
        root = self.root / name
        src = root / "src/main.rs"
        src.parent.mkdir(parents=True, exist_ok=True)
        content = "".join(
            '("workflow_id".to_owned(), AwflValue::String("%s".to_owned()));\n' % ref
            for ref in refs
        ) or "fn main() {}\n"
        src.write_text(content)
        (root / "Cargo.toml").write_text(
            '[package]\nname = "test_%s"\nversion = "0.1.0"\nedition = "2024"\n\n[workspace]\n'
            % name.replace("-", "_"))
        mf = {
            "workflow": name, "schema_version": 2, "runtime_abi": 3,
            "source_sha256": hashlib.sha256(src.read_bytes()).hexdigest(),
            "effects": [{"operation": op} for op in ops],
        }
        if "native.parallel" in ops:
            mf["parallel_version"] = 1
        (root / "manifest.json").write_text(json.dumps(mf))
        return root

    def test_root_targets_and_static_closure(self):
        spec, modules = module.modules_for("codebase-ProjectManager", True)
        self.assertEqual(len(modules), 12)
        self.assertIn("tools-CliTools", spec["dynamic_targets"])
        self.crate("codebase-ProjectManager",
                   ["tools-CliTools" + self.env], ("workflow.run",))
        with self.assertRaisesRegex(ValueError, "Missing statically"):
            module.generated_crates(self.root, "codebase-ProjectManager", True, [])
        self.crate("tools-CliTools")
        found = module.generated_crates(self.root, "codebase-ProjectManager", True,
                                        ["tools-CliTools"])
        self.assertEqual(len(found), 2)

    def test_unsupported_operations_and_bad_digests_fail_before_publishing(self):
        p = self.crate("codebase-ProjectManager", ops=("remote.unsafe",))
        with self.assertRaisesRegex(ValueError, "Unsupported effects"):
            module.generated_crates(self.root, "codebase-ProjectManager", False, [])
        self.crate("codebase-ProjectManager")
        (p / "src/main.rs").write_text("changed!")
        with self.assertRaisesRegex(ValueError, "Malformed generated"):
            module.generated_crates(self.root, "codebase-ProjectManager", False, [])

    def test_workspace_members_share_cargo_target(self):
        self.crate("codebase-ProjectManager")
        self.crate("tools-CliTools")
        crates = module.generated_crates(self.root, "codebase-ProjectManager", False, [])
        workspace = module.make_workspace(self.root, crates)
        self.assertIn('"tools-CliTools"', workspace.read_text())
        self.assertNotIn("[workspace]", (self.root / "tools-CliTools/Cargo.toml").read_text())
        self.assertEqual(len(crates), 2)

    def test_unmodified_sources_keep_cargo_mtimes(self):
        p = self.crate("codebase-ProjectManager")
        previous = module.snapshot_sources(self.root)
        path = p / "src/main.rs"
        original = path.stat().st_mtime_ns
        path.write_text(path.read_text())
        module.restore_sources(self.root, previous)
        self.assertEqual(path.stat().st_mtime_ns, original)


if __name__ == "__main__":
    unittest.main()
