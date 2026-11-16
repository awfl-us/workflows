#!/usr/bin/env python3
# AWFL integration of Zapier AutomationBench (upstream MIT, pinned externally).
# Simulator, tool functions, and scoring are imported from upstream, not reproduced.
from __future__ import annotations

import argparse
import asyncio
import hashlib
import inspect
import json
import os
import secrets
import signal
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

from datasets import Dataset
from automationbench.domains import get_domain_dataset
from automationbench.runner import AutomationBenchEnv
from automationbench.rubric import create_rubric, partial_credit, task_completed_correctly
from automationbench.tools import ALL_TOOLS


def plain(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


def load_task(domain: str, task_id: str) -> dict:
    matching = [row for row in get_domain_dataset(domain)
                if str(row["example_id"]) == str(task_id)]
    if len(matching) != 1:
        raise ValueError(f"Expected one task for {domain}/{task_id}; found {len(matching)}")
    return matching[0]


async def create_environment(row: dict):
    """Use the actual upstream environment setup and filtering, not a mock."""
    env = AutomationBenchEnv(
        dataset=Dataset.from_list([row]),
        rubric=create_rubric(),
        toolset="limited_zapier",
        max_turns=1,
    )
    state = await env.setup_state({
        "example_id": row["example_id"],
        "prompt": row["prompt"],
        "info": row["info"],
    })
    return state


def prepare(row: dict) -> dict:
    info = plain(row["info"])
    allowed = set(info.get("zapier_tools", []))
    tool_schemas = []
    for fn in ALL_TOOLS:
        if fn.__name__ in allowed:
            sig = inspect.signature(fn)
            tool_schemas.append({
                "name": fn.__name__,
                "description": inspect.getdoc(fn) or "",
                "parameters": [
                    {"name": name, "required": p.default is inspect.Parameter.empty}
                    for name, p in sig.parameters.items() if name != "world"
                ],
            })
    return {
        "benchmark": "automationbench-public",
        "task_id": str(row["example_id"]),
        "task_name": info["task_name"],
        "prompt": plain(row["prompt"]),
        "business_tools": tool_schemas,
        # Assertions and initial_state are intentionally NOT supplied to the builder.
        # A builder may inspect development-state only through a separate simulator.
    }


def agent_dataset(domain: str, task_id: str | None, limit: int) -> list[dict]:
    """Return only task prompts and allowed tool contracts to cloud builders."""
    if not 1 <= limit <= 100:
        raise ValueError("Dataset limit must be between 1 and 100")
    selected = [row for row in get_domain_dataset(domain)
                if task_id is None or str(row["example_id"]) == task_id][:limit]
    if not selected:
        raise ValueError(f"No AutomationBench tasks: {domain}/{task_id}")
    result = []
    for row in selected:
        spec = prepare(row)
        result.append({
            "domain": domain,
            "task_id": spec["task_id"],
            "task_name": spec["task_name"],
            "task_spec": json.dumps(spec, ensure_ascii=False),
        })
    return result


def json_write(path: Path, obj: Any):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, default=str) + "\n", encoding="utf-8")


def limit_text(text: str, n: int = 12000) -> str:
    return text[:n] + (f"\n[truncated {len(text) - n} chars]" if len(text) > n else "")


class Bridge:
    """Fresh task-scoped business state, accessible only through allowed tools."""
    def __init__(self, state: dict, token: str, max_tool_calls: int = 60):
        self.state = state
        self.token = token
        self.max_tool_calls = max_tool_calls
        self.lock = threading.Lock()
        self.allowed = {definition.name for definition in state["tool_defs"]}
        self.functions = {fn.__name__: fn for fn in ALL_TOOLS
                          if fn.__name__ in self.allowed}
        self.calls: list[dict] = []

    def inventory(self) -> list[dict]:
        return [{"name": d.name, "description": d.description, "parameters": d.parameters}
                for d in self.state["tool_defs"]]

    def invoke(self, name: str, args: dict) -> dict:
        with self.lock:
            if len(self.calls) >= self.max_tool_calls:
                return {"ok": False, "error": "Business tool call budget exceeded"}
            fn = self.functions.get(name)
            if fn is None:
                return {"ok": False, "error": "Tool not allowed for this task"}
            if not isinstance(args, dict) or "world" in args:
                return {"ok": False, "error": "Invalid tool arguments"}
            call = {"tool": name, "args": args, "index": len(self.calls) + 1}
            self.calls.append(call)
            try:
                if "world" in inspect.signature(fn).parameters:
                    result = fn(world=self.state["world"], **args)
                else:
                    result = fn(**args)
                if inspect.isawaitable(result):
                    result = asyncio.run(result)
                call["result"] = limit_text(str(result), 3000)
                return {"ok": True, "result": result}
            except Exception as error:
                call["error"] = f"{type(error).__name__}: {error}"
                return {"ok": False, "error": limit_text(str(error), 1200)}


def handler_for(bridge: Bridge):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, status: int, body: dict | list):
            raw = json.dumps(body, default=str).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _authorized(self) -> bool:
            if self.headers.get("X-AutomationBench-Token") != bridge.token:
                self._send(403, {"ok": False, "error": "Unauthorized"})
                return False
            return True

        def do_GET(self):
            if not self._authorized():
                return
            if self.path == "/tools":
                self._send(200, bridge.inventory())
            else:
                self._send(404, {"error": "Not found"})

        def do_POST(self):
            if not self._authorized():
                return
            if self.path != "/invoke":
                return self._send(404, {"error": "Not found"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not (0 < size <= 65536):
                    return self._send(413, {"error": "Invalid payload size"})
                req = json.loads(self.rfile.read(size))
                self._send(200, bridge.invoke(req.get("tool", ""), req.get("args", {})))
            except Exception as error:
                self._send(400, {"ok": False, "error": type(error).__name__})

        def log_message(self, *_args):
            return
    return Handler


def run_artifact(manifest_path: Path, url: str, token: str, timeout: int) -> dict:
    manifest_path = manifest_path.resolve(strict=True)
    manifest = json.loads(manifest_path.read_text())
    cmd = manifest.get("command")
    if not isinstance(cmd, list) or not cmd or not all(isinstance(x, str) for x in cmd):
        raise ValueError("Artifact manifest must have a nonempty command array")
    # The original authoring agent has finished; only its frozen artifact runs.
    cmd = [piece.replace("{python}", sys.executable).replace(
        "{artifact_dir}", str(manifest_path.parent)) for piece in cmd]
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="awfl-automationbench-artifact-") as home:
        proc_env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": home,
            "AUTOMATIONBENCH_BRIDGE_URL": url,
            "AUTOMATIONBENCH_BRIDGE_TOKEN": token,
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        if "JAVA_HOME" in os.environ:
            proc_env["JAVA_HOME"] = os.environ["JAVA_HOME"]
        try:
            process = subprocess.Popen(
                cmd, cwd=manifest_path.parent, env=proc_env, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                start_new_session=True,
            )
            stdout, stderr = process.communicate(timeout=timeout)
            return {
                "deployment_success": True,
                "runtime_success": process.returncode == 0,
                "runtime_exit_code": process.returncode,
                "workflow_runtime_seconds": round(time.monotonic() - start, 3),
                "stdout": limit_text(stdout),
                "stderr": limit_text(stderr),
            }
        except subprocess.TimeoutExpired as error:
            # Kill the entire process group, including compiler/runtime children.
            os.killpg(process.pid, signal.SIGKILL)
            stdout, stderr = process.communicate()
            return {
                "deployment_success": True,
                "runtime_success": False,
                "runtime_exit_code": None,
                "workflow_runtime_seconds": round(time.monotonic() - start, 3),
                "error_category": "execution_timeout",
                "stdout": limit_text(stdout or ""),
                "stderr": limit_text(stderr or ""),
            }


def evaluate(domain: str, task_id: str, artifact: Path, output: Path,
             timeout: int, max_tool_calls: int, seed: int = 1,
             platform: str = "unspecified", model: str = "unspecified",
             agent_harness: str = "unspecified", authoring_method: str = "unspecified",
             attempt: int = 1) -> dict:
    row = load_task(domain, task_id)
    state = asyncio.run(create_environment(row))
    fingerprint = state.get("_task_contract_sha256")
    # Score is derived from a fresh environment. Never reuse builder state.
    initial_sha = hashlib.sha256(
        state["world"].model_dump_json().encode()).hexdigest()
    token = secrets.token_urlsafe(24)
    bridge = Bridge(state, token, max_tool_calls=max_tool_calls)
    http = HTTPServer(("127.0.0.1", 0), handler_for(bridge))
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()
    runtime: dict = {}
    try:
        runtime = run_artifact(
            artifact, f"http://127.0.0.1:{http.server_port}", token, timeout)
    except Exception as error:
        runtime = {
            "deployment_success": False,
            "runtime_success": False,
            "workflow_runtime_seconds": 0.0,
            "error_category": "artifact_or_deployment_failure",
            "stderr": str(error),
        }
    finally:
        http.shutdown()
        http.server_close()
        thread.join(timeout=3)

    partial = partial_credit(state)
    strict = task_completed_correctly(state)
    result = {
        "benchmark": "automationbench-public-workflow-construction",
        "upstream_task_contract_sha256": fingerprint,
        "task_id": str(row["example_id"]),
        "domain": domain,
        "task_name": state["info"]["task_name"],
        "platform": platform,
        "model": model,
        "agent_harness": agent_harness,
        "authoring_method": authoring_method,
        "attempt": attempt,
        "seed": seed,  # Official public tasks have fixed initial state.
        "build_success": artifact.is_file(),
        "deployment_success": runtime.get("deployment_success", False),
        "task_completed_correctly": strict,
        "partial_credit": partial,
        "builder_tool_calls": None,
        "builder_input_tokens": None,
        "builder_output_tokens": None,
        "builder_cost_usd": None,
        "build_wall_time_seconds": None,
        "workflow_runtime_seconds": runtime.get("workflow_runtime_seconds", 0),
        "workflow_runtime_cost_usd": None,  # Not metered in this first slice.
        "business_tool_calls": len(bridge.calls),
        "error_category": runtime.get("error_category",
                          None if runtime.get("runtime_success") else "runtime_failure"),
        "artifact_location": str(artifact.resolve()),
        "runtime_exit_code": runtime.get("runtime_exit_code"),
    }
    json_write(output / "results.json", result)
    json_write(output / "assertions.json", state.get("_assertion_results", []))
    json_write(output / "tool_calls.json", bridge.calls)
    json_write(output / "initial_state.json", state["initial_state"])
    json_write(output / "final_state.json", state["world"].model_dump(mode="json"))
    json_write(output / "runtime.json", runtime)
    json_write(output / "metadata.json", {
        "domain": domain, "task_id": task_id, "seed": seed,
        "upstream_contract_sha256": fingerprint, "initial_world_sha256": initial_sha,
        "allowed_tools": bridge.inventory(),
    })
    return result


def main():
    parser = argparse.ArgumentParser(description="Independent AutomationBench simulator and grader")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--domain", required=True)
    p.add_argument("--task-id", required=True)
    p.add_argument("--out")
    ds = sub.add_parser("dataset", help="Agent task rows without scoring state or assertions")
    ds.add_argument("--domain", required=True)
    ds.add_argument("--task-id")
    ds.add_argument("--limit", type=int, default=1)
    ds.add_argument("--format", choices=["jsonl", "json"], default="jsonl")
    e = sub.add_parser("evaluate")
    e.add_argument("--domain", required=True)
    e.add_argument("--task-id", required=True)
    e.add_argument("--artifact", type=Path, required=True)
    e.add_argument("--output-dir", type=Path, required=True)
    e.add_argument("--timeout-seconds", type=int, default=90)
    e.add_argument("--max-tool-calls", type=int, default=60)
    e.add_argument("--seed", type=int, default=1)
    e.add_argument("--platform", default="unspecified")
    e.add_argument("--model", default="unspecified")
    e.add_argument("--agent-harness", default="unspecified")
    e.add_argument("--authoring-method", default="unspecified")
    e.add_argument("--attempt", type=int, default=1)
    args = parser.parse_args()
    if args.command == "prepare":
        result = prepare(load_task(args.domain, args.task_id))
        if args.out:
            json_write(Path(args.out), result)
        print(json.dumps(result))
    elif args.command == "dataset":
        tasks = agent_dataset(args.domain, args.task_id, args.limit)
        if args.format == "json":
            sys.stdout.write(json.dumps(tasks, ensure_ascii=False))
        else:
            sys.stdout.write("\n".join(
                json.dumps(task, ensure_ascii=False) for task in tasks))
    elif args.command == "evaluate":
        if not (1 <= args.timeout_seconds <= 900 and 1 <= args.max_tool_calls <= 1000):
            parser.error("Timeout or tool call budget out of range")
        print(json.dumps(evaluate(
            args.domain, args.task_id, args.artifact, args.output_dir,
            args.timeout_seconds, args.max_tool_calls, args.seed,
            args.platform, args.model, args.agent_harness,
            args.authoring_method, args.attempt)))


if __name__ == "__main__":
    main()
