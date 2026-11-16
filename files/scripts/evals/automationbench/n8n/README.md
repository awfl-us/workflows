# n8n Community Edition / AutomationBench task 1219

An **actual n8n workflow** for the same public HQ emergency-drill task used by
the AWFL native-Rust baseline. It does not need an n8n Cloud account.

## Workflow

[`emergency_drill.json`](emergency_drill.json) is an importable, 10-node
n8n graph (Manual Trigger, six HTTP Request nodes, two Code nodes, one If node).
It searches safety emails, selects the latest HQ drill notice, looks up the
Monday item, updates the status and **due** date, creates the Google Calendar
event, and conditionally notifies the emergency team after hours.

Each HTTP node calls the AutomationBench task-scoped simulator. The graph
contains placeholders `__AUTOMATIONBENCH_BRIDGE_URL__` and
`__AUTOMATIONBENCH_BRIDGE_TOKEN__`, replaced only at evaluation time by
`run_n8n.py`; no real Gmail, Monday, Calendar, or Slack credentials exist.

`generate.py` regenerates the JSON source deterministically. `artifact.json`
registers the n8n execution adapter with the *same independent benchmark
evaluator* as AWFL.

## Run locally (no external account)

A JavaScript environment with Node 20.19+ and Python 3.13 is required.
The first tested n8n Community Edition version was **1.123.14**.

In the AWFL Scala environment:

```bash
mkdir -p /workspace/workflows/eval_runs/n8n-runtime
npm install --prefix /workspace/workflows/eval_runs/n8n-runtime \
  --omit=dev --no-audit --no-fund n8n@1.123.14

cd /workspace/automationbench-upstream
/home/awfl/.local/bin/uv run python \
  /workspace/workflows/files/scripts/evals/automationbench/runner.py \
  evaluate --domain operations --task-id 1219 \
  --artifact /workspace/workflows/files/scripts/evals/automationbench/n8n/artifact.json \
  --output-dir /workspace/workflows/eval_runs/n8n-1219/score \
  --platform n8n-community --model gpt-6 --agent-harness chatgpt \
  --authoring-method n8n-visual-graph \
  --timeout-seconds 180 --max-tool-calls 20
```

The evaluator resets the simulated SaaS state, provides an authenticated local
HTTP bridge, and launches `run_n8n.py` in a separate process. This adapter
creates a **temporary, local SQLite-backed n8n instance**, binds the bridge
address/token in the workflow JSON, imports the workflow with
`n8n import:workflow`, executes it with `n8n execute --id`, and discards
the temporary database. No editor, hosted account, external credential, or
GCP Terraform change is required. All business decisions and invocations
occur in real n8n workflow nodes.

The package installation is intentionally ignored and **not committed**.
The execution subprocess is isolated only by a temporary HOME/DB, not an OS
sandbox. This is an internal trusted-artifact comparison, not an environment
for arbitrary hostile submissions.

## Confirmed results

Public task `operations.monday_calendar_emergency_drill` (1219), pinned
upstream at `4a8e1061254004d9dac807054eed33fad7d1ff14`:

| Platform | Strict completion | Partial credit | SaaS calls | Measured artifact runtime |
|---|---:|---:|---:|---:|
| Native AWFL Rust (first successful run) | 1.00 | 1.00 | 6 | 0.205 s |
| Native AWFL Rust (frozen artifact) | 1.00 | 1.00 | 6 | 0.177 s |
| n8n Community (run 1) | 1.00 | 1.00 | 6 | 14.034 s |
| n8n Community (run 2) | 1.00 | 1.00 | 6 | 13.387 s |

All **eight official assertions passed** in both n8n runs, including four
negative assertions guarding against unrelated changes. The n8n runs
had no runtime errors.

**Timing is not apples-to-apples:** n8n's `run_n8n.py` starts a fresh
SQLite-backed n8n process twice, once for CLI import and once to execute
the graph. Run 1 used **7.327 seconds for CLI import** and **6.590 seconds
for CLI execution**, including n8n startup. The AWFL measurement starts a
compiled Rust binary and drives its IPC effects, excluding Scala/Cargo
compilation. A valid production latency comparison must keep both execution
engines warm or explicitly charge both cold-start/deployment overheads. This
data verifies behavior, *not* that AWFL executes workflows 79x faster.

**Builder fairness:** The n8n workflow was manually authored by ChatGPT after
the AWFL task was already solved and official assertion feedback was
available. This is a known-public-task engineering demonstration, not a blind
workflow-building benchmark or model comparison. Builder tool calls,
token usage and authoring costs remain unmeasured.

Next: drive an unseen suite of complex tasks with a fixed authoring budget,
independent build/eval isolation and comparable warm-execution metrics.
