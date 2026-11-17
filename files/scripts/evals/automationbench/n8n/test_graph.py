"""Structural contract for the n8n AutomationBench task 1219 artifact."""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def graph():
    return json.loads((ROOT / "emergency_drill.json").read_text())


def test_real_n8n_nodes_connected():
    data = graph()
    assert data["name"].startswith("AutomationBench 1219")
    assert data["active"] is False
    nodes = data["nodes"]
    assert len(nodes) == 10
    by_name = {node["name"]: node for node in nodes}
    assert len(by_name) == len(nodes)
    assert {node["type"] for node in nodes} == {
        "n8n-nodes-base.manualTrigger", "n8n-nodes-base.httpRequest",
        "n8n-nodes-base.code", "n8n-nodes-base.if"
    }
    reachable = {"Start"}
    while True:
        expanded = reachable | {
            edge["node"] for source in reachable
            for branch in data["connections"].get(source, {}).get("main", [])
            for edge in branch
        }
        if expanded == reachable:
            break
        reachable = expanded
    assert reachable == set(by_name)


def test_six_authorized_simulator_tools():
    nodes = [node for node in graph()["nodes"]
             if node["type"] == "n8n-nodes-base.httpRequest"]
    assert len(nodes) == 6
    for node in nodes:
        params = node["parameters"]
        assert params["url"] == "__AUTOMATIONBENCH_BRIDGE_URL__/invoke"
        assert params["method"] == "POST"
        headers = params["headerParameters"]["parameters"]
        assert headers == [{
            "name": "X-AutomationBench-Token",
            "value": "__AUTOMATIONBENCH_BRIDGE_TOKEN__"
        }]
        assert "JSON.stringify" in params["jsonBody"]


def test_task_constraints_are_embedded_in_graph():
    by_name = {node["name"]: node for node in graph()["nodes"]}
    due = by_name["Update Due Date"]["parameters"]["jsonBody"]
    assert "column_id:'due'" in due
    notify = by_name["Notify Emergency Team"]["parameters"]["jsonBody"]
    assert "After hours" in notify
    notice = by_name["Parse Latest HQ Notice"]["parameters"]["jsCode"]
    assert "internal_date" in notice and "sort(" in notice
    assert "startsWith('Emergency Drill - HQ')" in notice
    assert "throw new Error" in notice
    branch = by_name["After Hours?"]["parameters"]
    assert "afterHours" in branch["conditions"]["conditions"][0]["leftValue"]


def test_generator_is_deterministic():
    path = ROOT / "emergency_drill.json"
    before = path.read_bytes()
    subprocess.run([sys.executable, str(ROOT / "generate.py")],
                   check=True, capture_output=True)
    assert path.read_bytes() == before
