#!/usr/bin/env python3
"""A fixed, executable cross-app workflow used to prove the bridge and grader.
This is NOT presented as an AI-generated or AWFL-produced benchmark result.
"""
import json
import os
import re
from datetime import datetime, timezone
from urllib.request import Request, urlopen


def call(tool, **args):
    url = os.environ["AUTOMATIONBENCH_BRIDGE_URL"] + "/invoke"
    body = json.dumps({"tool": tool, "args": args}).encode()
    request = Request(
        url, body, method="POST",
        headers={
            "Content-Type": "application/json",
            "X-AutomationBench-Token": os.environ["AUTOMATIONBENCH_BRIDGE_TOKEN"],
        })
    with urlopen(request, timeout=20) as response:
        envelope = json.load(response)
    if not envelope.get("ok"):
        raise RuntimeError(f"{tool}: {envelope.get('error')}")
    result = envelope["result"]
    return json.loads(result) if isinstance(result, str) else result


messages = call("gmail_find_email", query="from:safety@company.example.com")["messages"]
# Process only the latest authoritative HQ drill update, not older plans,
# postponed notices, satellite drills, or unrelated safety meetings.
hq = [item for item in messages if item.get("subject", "").startswith("Emergency Drill - HQ")]
latest = max(hq, key=lambda item: item.get("date", 0))
body = latest.get("body_plain", "")
date = re.search(r"(20\d\d-\d\d-\d\d)\s+(\d\d:\d\d)-(\d\d:\d\d)\s+UTC", body)
if not date:
    raise RuntimeError("Latest HQ drill notice lacks a confirmed new time")
day, start, end = date.groups()

items = call("monday_find_item", board_id="brd_ops", name="HQ Emergency Drill")["results"]
if len(items) != 1:
    raise RuntimeError("Expected one HQ drill item")
item_id = items[0]["item_id"]
call("monday_change_status_column_value",
     board_id="brd_ops", item_id=item_id, column_id="status", value_label="Scheduled")
call("monday_change_date_column_value",
     board_id="brd_ops", item_id=item_id, column_id="date",
     value_date=day, value_time=start)
call("google_calendar_create_detailed_event",
     calendarid="cal_ops",
     summary="HQ Emergency Drill",
     start__dateTime=f"{day}T{start}:00Z",
     end__dateTime=f"{day}T{end}:00Z",
     description=f"Safety notice: {body}")
call("slack_send_channel_message",
     channel="CEMTEAM",
     text=f"HQ Emergency Drill rescheduled: {day} {start}-{end} UTC. Safety notice: {body}")
print(json.dumps({"executed": True, "drill_date": day, "start_utc": start, "end_utc": end}))
