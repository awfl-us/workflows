"""Local integration tests against pinned upstream AutomationBench."""
import asyncio
import json
from pathlib import Path

from runner import Bridge, create_environment, evaluate, load_task, prepare


def test_public_dataset_prepare_does_not_disclose_assertions():
    row = load_task("operations", "1219")
    public = prepare(row)
    assert public["task_id"] == "1219"
    assert public["task_name"] == "operations.monday_calendar_emergency_drill"
    assert len(public["business_tools"]) >= 3
    assert "assertions" not in public
    assert "initial_state" not in public


def test_simulator_environments_have_distinct_worlds():
    row = load_task("operations", "1219")
    one = asyncio.run(create_environment(row))
    two = asyncio.run(create_environment(row))
    assert one["world"] is not two["world"]
    # Pydantic can fill unseeded action-log timestamps with the current clock;
    # the task-supplied state must be identical across independent trials.
    assert one["initial_state"] == two["initial_state"]
    assert {t.name for t in one["tool_defs"]} == {t.name for t in two["tool_defs"]}
    assert len(one["info"]["assertions"]) == 8


def test_tool_allowlist_rejects_unrelated_operations():
    state = asyncio.run(create_environment(load_task("operations", "1219")))
    bridge = Bridge(state, "token", max_tool_calls=2)
    result = bridge.invoke("zendesk_delete_ticket", {})
    assert result["ok"] is False
    assert len(bridge.calls) == 0


def test_official_scoring_of_independent_smoke_artifact(tmp_path: Path):
    manifest = Path(__file__).with_name("smoke_manifest.json")
    result = evaluate("operations", "1219", manifest, tmp_path, 30, 5)
    assert result["build_success"] is True
    assert result["deployment_success"] is True
    assert result["task_completed_correctly"] == 0.0
    assert result["partial_credit"] == 0.0
    assert json.loads((tmp_path / "runtime.json").read_text())["runtime_success"] is True
    assert (tmp_path / "assertions.json").exists()
    assert (tmp_path / "initial_state.json").exists()
    assert (tmp_path / "final_state.json").exists()


def test_invalid_artifact_does_not_get_free_credit(tmp_path: Path):
    manifest = tmp_path / "invalid.json"
    manifest.write_text(json.dumps({"command": []}))
    result = evaluate("operations", "1219", manifest, tmp_path / "out", 3, 5)
    assert result["deployment_success"] is False
    assert result["error_category"] == "artifact_or_deployment_failure"
    assert result["task_completed_correctly"] == 0.0


def test_reference_workflow_changes_fresh_world_and_gets_official_partial_credit(tmp_path: Path):
    # This is a fixed Python reference artifact, not an AI-generated AWFL score.
    manifest = Path(__file__).with_name("example_manifest.json")
    result = evaluate("operations", "1219", manifest, tmp_path, 30, 20,
                      platform="python-reference", authoring_method="code-prebuilt")
    assert result["deployment_success"] is True
    assert result["error_category"] is None
    assert result["business_tool_calls"] >= 4
    assert 0 < result["partial_credit"] < 1
    assert result["task_completed_correctly"] == 0
    assert len(json.loads((tmp_path / "tool_calls.json").read_text())) >= 4


def test_runtime_failure_preserves_evaluator_lifecycle(tmp_path: Path):
    script = tmp_path / "fail.py"
    script.write_text("raise RuntimeError('intentional failure')\n")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"command": ["{python}", "{artifact_dir}/fail.py"]}))
    result = evaluate("operations", "1219", manifest, tmp_path / "out", 10, 2)
    assert result["deployment_success"] is True
    assert result["error_category"] == "runtime_failure"
    assert result["task_completed_correctly"] == 0
    assert (tmp_path / "out" / "final_state.json").exists()


def test_timeout_terminates_artifact_and_scores_fresh_state(tmp_path: Path):
    script = tmp_path / "slow.py"
    script.write_text("import time\ntime.sleep(10)\n")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"command": ["{python}", "{artifact_dir}/slow.py"]}))
    result = evaluate("operations", "1219", manifest, tmp_path / "out", 1, 2)
    assert result["error_category"] == "execution_timeout"
    assert result["task_completed_correctly"] == 0
