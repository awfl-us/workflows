#!/usr/bin/env python3
"""Run the native AutomationBench coordinator to its first effect.

This tests Scala -> Rust compilation and IPC without executing any effects,
creating cloud projects, making model requests, or reading grading fixtures.
"""
import argparse
import json
import subprocess
from pathlib import Path


def smoke(report_path: Path) -> dict:
    report = json.loads(report_path.read_text())
    entry = report["evals-AutomationBenchEval"]
    assert "evals-experiments-AutomationBench" in report
    assert "codebase-ProjectManager" in report
    binary = Path(entry["binary"])
    assert binary.is_file(), binary
    request = {
        "protocol_version": 2,
        "input": {
            "arg1": "native-rust-preflight",
            "arg2": "1",
            "env": {
                "BASE_URL": "http://127.0.0.1:5055",
                "userId": "native-preflight-user",
                "projectId": "native-preflight-project",
                "sessionId": "native-preflight-session",
                "model": "gpt-6-sol",
            },
        },
    }
    completed = subprocess.run(
        [str(binary)], input=json.dumps(request), text=True,
        capture_output=True, timeout=25, check=True
    )
    response = json.loads(completed.stdout)
    assert response.get("protocol_version") == 2, response
    assert response.get("status") == "suspended", response
    effect = response["effect"]
    assert effect["operation"] == "native.parallel", effect
    assert effect.get("effect_id"), effect
    assert response.get("continuation"), response
    return {
        "status": "passed",
        "runtime": "native-rust",
        "root": "evals-AutomationBenchEval",
        "compiled_workflows": len(report),
        "first_effect": effect["operation"],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(smoke(args.report.resolve()), sort_keys=True))
