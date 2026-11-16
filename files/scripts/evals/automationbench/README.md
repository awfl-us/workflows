# AutomationBench integration (first local vertical slice)

The evaluator is registered alongside SWE-bench in `us.awfl.workflows.evals`.
This directory contains a Python 3.13 integration that **imports** Zapier's
public AutomationBench simulator and official assertion grader rather than
copying them.

## Tested upstream

- Source: https://github.com/zapier/AutomationBench
- Pinned revision: `4a8e1061254004d9dac807054eed33fad7d1ff14`
- Package: `automation-bench==1.0.6`
- License: MIT, Copyright 2026 Zapier, Inc. Attribution and upstream LICENSE
  must be retained. This repository does not vendor their source or test data.
- Python >=3.13, with dependencies isolated in upstream's `.venv`.

In the AWFL Scala cloud image:

```bash
git clone https://github.com/zapier/AutomationBench.git /workspace/automationbench-upstream
git -C /workspace/automationbench-upstream checkout 4a8e1061254004d9dac807054eed33fad7d1ff14
python3 -m pip install --user uv
cd /workspace/automationbench-upstream
~/.local/bin/uv sync --locked
~/.local/bin/uv run pytest -q
```

The upstream checkout, virtualenv, generated state, credentials and outputs
are **not** committed to `awfl-us/workflows`.

## Simulator and grader extension points

These concrete upstream APIs were exercised:

- `automationbench.domains.get_domain_dataset(domain)` returns a Hugging Face
  Dataset with `example_id`, `prompt`, `answer`, `info`. `info` contains
  initial simulated business state, allowed Zapier tools and assertions.
- `AutomationBenchEnv(dataset=Dataset.from_list([row]), rubric=create_rubric(),
  toolset="limited_zapier")` and `await env.setup_state(...)` initialize
  a **new WorldState**, normalize dataset values and apply the task's tool
  allowlist. No LLM is required to create the environment.
- `automationbench.tools.ALL_TOOLS` contains the actual business-tool
  implementation functions. The bridge injects the fresh `world` argument
  and permits only the tools selected by upstream `setup_state`.
- `automationbench.rubric.partial_credit(state)` and
  `task_completed_correctly(state)` are the official scoring functions.
  Upstream handles pre-satisfied assertions and anti-shotgun negative checks.
  The grader writes `_assertion_results` and `_end_state`.

The **public** task set is not the private official leaderboard set. An AWFL
workflow-construction score is a different metric from standard direct-agent
AutomationBench performance.

## Run a concrete, independently executed sample

This is a *prebuilt Python reference workflow*, **not** a generated AWFL
workflow and **not** an AI benchmark result. It exercises operations task
1219 (latest HQ emergency drill notice, Monday, Calendar, Slack). The script
itself never receives the initial state or verifier assertions; it discovers
and invokes task-scoped simulated business tools over loopback HTTP.

```bash
cd /workspace/automationbench-upstream
RUNNER=/workspace/workflows/files/scripts/evals/automationbench/runner.py
ARTIFACT=/workspace/workflows/files/scripts/evals/automationbench/example_manifest.json
~/.local/bin/uv run python "$RUNNER" prepare --domain operations --task-id 1219
~/.local/bin/uv run python "$RUNNER" evaluate \
  --domain operations --task-id 1219 \
  --artifact "$ARTIFACT" \
  --platform python-reference --agent-harness prebuilt-reference \
  --authoring-method code-prebuilt --model none \
  --timeout-seconds 60 --max-tool-calls 20 \
  --output-dir /workspace/workflows/eval_runs/automationbench_reference_1219

PYTHONPATH=/workspace/workflows/files/scripts/evals/automationbench \
  ~/.local/bin/uv run pytest -q \
  /workspace/workflows/files/scripts/evals/automationbench/test_runner.py
```

The initial evaluated reference workflow performed six allowed simulator calls
and scored **0.50 partial credit / 0.00 strict completion** (task ID 1219).
That is evidence of working execution, not a successful agent-created system.

Per-run `results.json`, `assertions.json`, `tool_calls.json`,
`initial_state.json`, `final_state.json`, `runtime.json`, and
`metadata.json` are persisted under the chosen output directory.

## Runtime boundary and reproducibility

A manifest contains a nonempty `command` array. The evaluator reinitializes
the upstream environment, serves a local authenticated `/tools` and
`/invoke` endpoint, launches the frozen artifact in a separate subprocess,
captures logs and tool calls, then shuts down the bridge and calls the upstream
rubric. The output is independent of the syntax of the artifact. Time and
business tool-call budgets are bounded; subprocess descendants are killed on
timeout. No real SaaS APIs, business data or GKE workloads are used.

The prototype runs trusted artifacts on the **same host**, and does not
provide a secure OS-level sandbox for hostile builder output. The public
dataset and assertions are available on the developer filesystem. For
**hidden evaluations**, move the evaluator and assertions into an isolated
process/container inaccessible to the builder, lock down filesystem/network
access, and preserve the artifact snapshot hash between build and evaluation.
The `seed` is currently recorded for reproducibility but does **not** create
new independent upstream scenarios. No invented robustness scores.

The included Scala `AutomationBenchEval` / `experiments.AutomationBench`
reuses `Eval` and `Experiment` for configuration expansion, input and answer
persistence and aggregation. It stages a prebuilt artifact for this first
smoke slice. The build stage is **not** an AI agent yet, and null builder
usage/cost means unmeasured, not free.

## Next milestones

1. Connect a configured **AWFL agent builder** with a fixed token/time budget
   to author and compile a genuine AWFL workflow. Capture the immutable
   executable/deployment identifier and builder transcript/usage, then invoke
   this independent runner. Do not label any prebuilt Python fixture an AWFL
   result.
2. Implement a reusable AWFL-to-simulator business tool transport and freeze
   compiled artifacts; run a full strict pass attempt on task 1219.
3. Implement the n8n platform adapter using the same prompt, allowed operations,
   model/harness and *independently reset* simulator.
4. Add multiple independently seeded input/state variations per artifact. This
   extends AutomationBench and must be reported separately from the official
   one-scenario task score.
5. Isolate evaluator credentials and grader files in a separate sandbox,
   track builder versus runtime token/cost usage, and compare repeated trials.

No Terraform or production infrastructure changes are needed for this local
integration.
