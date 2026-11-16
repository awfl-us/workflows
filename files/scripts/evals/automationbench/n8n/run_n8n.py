#!/usr/bin/env python3
"""Run a genuine n8n JSON workflow against the authenticated AutomationBench bridge.

Installs/uses a self-hosted n8n Community Edition CLI; no n8n Cloud account.
No business logic is in this adapter: it only binds the simulator endpoint,
imports the frozen n8n workflow graph, and executes it with n8n itself.
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile
import time

N8N_BIN = (pathlib.Path(__file__).resolve().parents[5]
           / "eval_runs/n8n-runtime/node_modules/.bin/n8n")
BRIDGE_MARKER = "__AUTOMATIONBENCH_BRIDGE_URL__"
TOKEN_MARKER = "__AUTOMATIONBENCH_BRIDGE_TOKEN__"


def run(workflow_path: pathlib.Path):
    binary = N8N_BIN.resolve(strict=True)
    if not binary.is_file():
        raise RuntimeError("n8n CLI not installed; see README for local setup")
    url = os.environ["AUTOMATIONBENCH_BRIDGE_URL"]
    token = os.environ["AUTOMATIONBENCH_BRIDGE_TOKEN"]
    if not url.startswith("http://127.0.0.1:"):
        raise ValueError("Only the benchmark localhost bridge is supported")
    graph = json.loads(workflow_path.read_text())
    if graph.get("active") or len(graph.get("nodes", [])) < 2:
        raise ValueError("Expected an inactive, multi-node n8n workflow graph")
    graph["id"] = "awfl-n8n-drill-1219"
    graph_text = json.dumps(graph)
    if graph_text.count(BRIDGE_MARKER) != 6 or graph_text.count(TOKEN_MARKER) != 6:
        raise ValueError("Expected exactly six authorized HTTP tool nodes")
    graph_text = graph_text.replace(BRIDGE_MARKER, url).replace(TOKEN_MARKER, token)
    with tempfile.TemporaryDirectory(prefix="automationbench-n8n-") as root:
        root_dir = pathlib.Path(root)
        imported = root_dir / "workflow.json"
        imported.write_text(graph_text)
        env = os.environ.copy()
        env.update({
            "N8N_USER_FOLDER": str(root_dir / "user"),
            "DB_TYPE": "sqlite",
            "DB_SQLITE_DATABASE": str(root_dir / "n8n.sqlite"),
            "N8N_ENCRYPTION_KEY": secrets.token_hex(16),
            "N8N_DIAGNOSTICS_ENABLED": "false",
            "N8N_VERSION_NOTIFICATIONS_ENABLED": "false",
            "N8N_TEMPLATES_ENABLED": "false",
            "N8N_LOG_LEVEL": "error",
            "N8N_METRICS": "false",
            "N8N_ENFORCE_SETTINGS_FILE_PERMISSIONS": "false",
        })
        steps = [
            ["import:workflow", "--input="+str(imported)],
            ["execute", "--id="+graph["id"]],
        ]
        logs = []
        for command in steps:
            start = time.monotonic()
            completed = subprocess.run(
                [str(binary), *command],
                env=env,
                cwd=str(root_dir),
                capture_output=True,
                text=True,
                timeout=90,
            )
            record = {
                "command": command[0],
                "seconds": round(time.monotonic()-start, 3),
                "exit_code": completed.returncode,
                "stdout": completed.stdout[-5000:],
                "stderr": completed.stderr[-5000:],
            }
            logs.append(record)
            if completed.returncode:
                raise RuntimeError(json.dumps(record))
        return {
            "workflow": graph["name"],
            "node_count": len(graph["nodes"]),
            "graph_sha256": hashlib.sha256(workflow_path.read_bytes()).hexdigest(),
            "n8n_commands": logs,
        }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: run_n8n.py /path/to/frozen/workflow.json")
    print(json.dumps(run(pathlib.Path(sys.argv[1]).resolve(strict=True))))
