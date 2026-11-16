"""Portable native publishing validation without Docker or GCP credentials."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

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

    def test_unregistered_scala_classes_are_validated_without_target_registration(self):
        module_name = "us.awfl.workflows.evals.automationbench.EmergencyDrill"
        spec, modules = module.unregistered_class(module_name)
        self.assertEqual(spec, {})
        self.assertEqual(modules, [module_name])
        for invalid in ("", "notqualified", "a.b;rm -rf /", "a.b c", "a..b"):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(
                    ValueError, "Invalid fully qualified"):
                module.unregistered_class(invalid)

    def test_unregistered_single_and_multi_variant_selection(self):
        index = self.root / ".awfl-native-workflows"
        first = "evals-automationbench-EmergencyDrill-emergency-drill"
        index.write_text(first + "\n")
        self.assertEqual(module.select_generated_root(self.root, None), first)
        self.assertEqual(module.select_generated_root(self.root, first), first)
        with self.assertRaisesRegex(ValueError, "not emitted"):
            module.select_generated_root(self.root, "unknown")
        index.write_text(first + "\n" + "evals-automationbench-EmergencyDrill-other\n")
        with self.assertRaisesRegex(ValueError, "select one with --only"):
            module.select_generated_root(self.root, None)
        self.assertEqual(module.select_generated_root(self.root, first), first)
        index.write_text("bad/path\n")
        with self.assertRaisesRegex(ValueError, "Invalid native compiler artifact index"):
            module.select_generated_root(self.root, None)

    def test_unregistered_class_cannot_publish_or_claim_recursive_closure(self):
        class_name = "us.awfl.workflows.evals.automationbench.EmergencyDrill"
        for flags in ("--publish", "--recursive", "--force-publish"):
            with self.subTest(flags=flags), patch(
                    "sys.argv", ["native-dev.py", "--class", class_name, flags]), \
                 self.assertRaises(SystemExit) as ctx:
                module.main()
            self.assertEqual(ctx.exception.code, 2)

    def test_unregistered_skip_compile_rejects_other_class(self):
        with patch("sys.argv", [
                "native-dev.py", "--class",
                "us.awfl.workflows.evals.automationbench.EmergencyDrill",
                "--skip-compile", "--output", str(self.root)]):
            with self.assertRaisesRegex(ValueError, "not for requested Scala class"):
                module.main()

    def test_benchmark_uses_same_native_builder_not_separate_compiler(self):
        wrapper = (SCRIPT.parent.parent /
                   "files/scripts/evals/automationbench/build_native.sh").read_text()
        self.assertIn('scripts/native-dev.py', wrapper)
        self.assertIn('--class "$CLASS"', wrapper)
        self.assertIn('--only "$WORKFLOW"', wrapper)
        self.assertNotIn("sbt -batch", wrapper)
        self.assertNotIn("cargo build", wrapper)

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

    def test_jvm_classpath_cache_invalidates_on_scala_source_change(self):
        compiler = self.root / "compiler"
        path = compiler / "frontend/src/main/scala/Fixture.scala"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("object Fixture { val x = 1 }")
        first = module.source_fingerprint(compiler)
        path.write_text("object Fixture { val x = 2 }")
        second = module.source_fingerprint(compiler)
        self.assertNotEqual(first, second)

    def test_remote_active_index_skips_only_exactly_matching_release_metadata(self):
        crate = self.crate("codebase-ProjectManager",
                           ops=("native.parallel", "workflow.run"))
        mf_path = crate / "manifest.json"
        manifest = json.loads(mf_path.read_text())
        manifest["parallel_version"] = 1
        manifest["trusted_globals_version"] = 1
        mf_path.write_text(json.dumps(manifest))
        crates = module.generated_crates(self.root, "codebase-ProjectManager", False, [])
        binary = {"sha256": "a" * 64,
                  "operations": ["native.parallel", "workflow.run"]}
        built = {"codebase-ProjectManager": binary}
        expected = module.expected_release("codebase-ProjectManager", manifest, binary)
        self.assertEqual(expected["parallelVersion"], 1)
        self.assertEqual(expected["trustedGlobalsVersion"], 1)
        self.assertEqual(module.changed_releases(crates, built,
            {"codebase-ProjectManager": expected}), [])
        self.assertEqual(module.changed_releases(crates, built,
            {"codebase-ProjectManager": {**expected, "artifactSha256": "b"*64}}),
            ["codebase-ProjectManager"])
        self.assertEqual(module.changed_releases(crates, built, {}),
                         ["codebase-ProjectManager"])

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
