#!/usr/bin/env python3
"""Executable smoke artifact, NOT an AI-built AWFL benchmark submission."""
import json
import os
from urllib.request import Request, urlopen

url = os.environ["AUTOMATIONBENCH_BRIDGE_URL"]
token = os.environ["AUTOMATIONBENCH_BRIDGE_TOKEN"]
req = Request(url + "/tools", headers={"X-AutomationBench-Token": token})
with urlopen(req, timeout=10) as response:
    tools = json.load(response)
assert any(t["name"] == "gmail_find_email" for t in tools)
print(json.dumps({"seen_tools": len(tools), "mode": "smoke-only"}))
