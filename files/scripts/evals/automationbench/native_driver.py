#!/usr/bin/env python3
"""Unprivileged local host for AWFL native ABI 3 / IPC 2 binaries.

It interprets *only* the native effect protocol, never the workflow's
business logic. The workflow is compiled from Scala DSL to executable Rust.
All business tool calls are forwarded to the task allowlisted simulator.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


def tagged(value):
    if value is None:
        return {"type": "null"}
    if isinstance(value, bool):
        return {"type": "bool", "value": value}
    if isinstance(value, int):
        return {"type": "integer", "value": value}
    if isinstance(value, float):
        return {"type": "double", "value": value}
    if isinstance(value, str):
        return {"type": "string", "value": value}
    if isinstance(value, list):
        return {"type": "list", "value": [tagged(item) for item in value]}
    if isinstance(value, dict):
        return {"type": "map", "value": {key: tagged(item) for key, item in value.items()}}
    raise ValueError(f"Cannot tag value of type {type(value).__name__}")


def invoke_native(binary: str, payload: dict) -> dict:
    completed = subprocess.run(
        [binary], input=json.dumps(payload), text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if completed.returncode:
        raise RuntimeError(f"Native process exited {completed.returncode}: {completed.stderr[-3000:]}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"Bad native JSON output: {completed.stdout[-1500:]}") from error


def dispatch_http(effect: dict, bridge_url: str, bridge_token: str) -> dict:
    if effect.get("operation") != "http.post":
        raise RuntimeError(f"Unsupported native effect {effect.get('operation')!r}")
    args = effect["args"]
    url = args.get("url")
    if url != bridge_url + "/invoke":
        raise RuntimeError("Native artifact attempted a non-simulator HTTP effect")
    body = args.get("body")
    headers = args.get("headers") or {}
    if not isinstance(body, dict) or not isinstance(headers, dict):
        raise RuntimeError("Malformed native HTTP body/headers")
    if headers.get("X-AutomationBench-Token") != bridge_token:
        raise RuntimeError("Missing simulator authentication")
    req = urllib.request.Request(
        url, json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json",
                 "X-AutomationBench-Token": bridge_token},
    )
    with urllib.request.urlopen(req, timeout=15) as r:
        response = json.load(r)
        return {"body": response, "code": r.status, "headers": {}}


def run(binary: str) -> dict:
    bridge_url = os.environ["AUTOMATIONBENCH_BRIDGE_URL"]
    token = os.environ["AUTOMATIONBENCH_BRIDGE_TOKEN"]
    # The shared AWFL PostRequest DSL builds an optional OIDC audience from
    # input.env.BASE_URL, even though the local, allowlisted AutomationBench
    # simulator is authenticated using X-AutomationBench-Token instead.
    # Supply only the local simulator origin here; no cloud credentials,
    # trusted globals, or real server identity are granted to the artifact.
    payload = {
        "protocol_version": 2,
        "input": {
            "bridge_url": bridge_url,
            "bridge_token": token,
            "env": {"BASE_URL": bridge_url},
        },
    }
    max_steps = int(os.environ.get("AUTOMATIONBENCH_NATIVE_MAX_STEPS", "80"))
    events = []
    seen = set()
    for index in range(max_steps):
        response = invoke_native(binary, payload)
        if response.get("protocol_version") != 2:
            raise RuntimeError("Invalid native protocol version")
        status = response.get("status")
        if status == "completed":
            return {"status": "completed", "native_steps": index + 1,
                    "native_result": response.get("result"), "effects": events}
        if status == "failed":
            raise RuntimeError(f"Compiled AWFL workflow failed: {response.get('error')}")
        if status != "suspended":
            raise RuntimeError(f"Unexpected native status {status!r}")
        effect = response["effect"]
        identity = effect["effect_id"]
        if identity in seen:
            raise RuntimeError(f"Repeated native effect {identity}")
        seen.add(identity)
        business_result = dispatch_http(effect, bridge_url, token)
        events.append({"effect_id": identity,
                       "operation": effect["operation"],
                       "tool": effect.get("args", {}).get("body", {}).get("tool")})
        checkpoint = response["continuation"]
        result_slot = effect["result_slot"]
        if not result_slot:
            raise RuntimeError("Missing native result slot")
        checkpoint["slots"][result_slot] = tagged(business_result)
        payload["continuation"] = checkpoint
    raise RuntimeError("Native workflow exceeded maximum IPC transitions")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: native_driver.py /absolute/path/to/compiled-binary")
    path = Path(sys.argv[1]).resolve(strict=True)
    if not path.is_file():
        raise SystemExit("Native executable not found")
    print(json.dumps(run(str(path))))
