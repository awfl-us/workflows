# AutomationBench integration (native AWFL workflow vertical slice)

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

## Native AWFL workflow: task 1219

`src/main/scala/us/awfl/workflows/evals/automationbench/EmergencyDrill.scala`
is a **real AWFL Scala DSL** workflow manually authored by ChatGPT as the
workflow-construction agent, compiled by `awfl-us/compiler-rust` into a native
Rust executable (ABI 3 / IPC v2). The independent `native_driver.py`
implements only the compiled effect/continuation protocol: it forwards
`http.post` effects to the simulator bridge and injects their journaled
responses into the resumed executable. **It does not implement the workflow's
selection or business logic.**

In an AWFL `scala` consumer, with the `compiler-rust` repository checked out
beside `workflows`, run:

```bash
cd /workspace/workflows
files/scripts/evals/automationbench/build_native.sh \
  /workspace/workflows/eval_runs/automationbench-native-1219/artifact

cd /workspace/automationbench-upstream
~/.local/bin/uv run python /workspace/workflows/files/scripts/evals/automationbench/runner.py evaluate \
  --domain operations --task-id 1219 \
  --artifact /workspace/workflows/eval_runs/automationbench-native-1219/artifact/artifact.json \
  --output-dir /workspace/workflows/eval_runs/automationbench-native-1219/score \
  --platform awfl-native-rust --model gpt-6 --agent-harness chatgpt \
  --authoring-method scala-dsl-compiled-rust \
  --timeout-seconds 60 --max-tool-calls 20
```

The benchmark `build_native.sh` **delegates compilation and verified
static-musl binary production to `workflows/scripts/native-dev.py`** using
its local-only `--class` mode. It no longer invokes the Scala frontend
or Cargo independently. An unregistered builder-produced Scala class can
also be specified as argument 2, with the fully qualified emitted variant
as argument 3. The generated workflow must be a `core.Workflow` on the
`workflows` source classpath. No GCS publication or native routing changes
occur during benchmark compilation.

This path includes the production build script's Scala compilation, native
code generation, shared Cargo release build, ABI/digest checks and
static-musl ELF verification. The artifact wrapper freezes the verified
executable and simulator adapter for independent grading. **Build time**
must be recorded as builder cost in future automated agent experiments;
the evaluator's `workflow_runtime_seconds` excludes compilation.

The build script **freezes the executable and protocol host into an artifact
directory**, rather than referring to a mutable Cargo target path. It
produces `artifact.json` and `build_metadata.json` with source,
compiler and binary hashes. Generated files, simulation states and grader
outputs live under ignored `eval_runs/`, not in Git.

**Observed after interactive debugging and reading assertion feedback:** on
public operations task 1219 the native compiled workflow scored **1.00
partial credit / 1.00 strict completion**, with **8/8 official assertions
passing**, **6 business tool calls** and **0.205 s artifact runtime** in one
local run. It updated the HQ Monday status and due date, created the HQ
calendar event, notified the after-hours Slack channel, and made no
prohibited updates. The runtime measurement excludes Scala/Rust compilation,
simulator startup, and grading. This is an **iteratively developed, public-task
result**, *not* a blind held-out evaluation or general benchmark score.
Builder tool-call counts, tokens, costs and wall-time have not been metered.

## n8n Community Edition: same task, same evaluator

The independently authored [n8n workflow](n8n/emergency_drill.json) contains
**10 real n8n nodes**: Manual Trigger, six HTTP Request nodes, two Code nodes
and an after-hours If branch. The separate
[local CLI adapter](n8n/run_n8n.py) binds the same authenticated simulator
bridge, imports the JSON into ephemeral self-hosted n8n Community Edition
and executes the actual graph using n8n itself. No n8n Cloud account or
external SaaS credentials are required.

The first **two n8n executions** also achieved **1.00 strict / 1.00 partial
credit**, **8/8 positive/negative assertions** and **six business tool calls**,
with **14.034 s and 13.387 s** measured artifact runtime. Those times
include n8n CLI process startup, import, database initialization and
execution; they **cannot** be divided by AWFL's standalone Rust execution
time to claim a fair speedup. The n8n workflow was built after the AWFL
task was solved, so neither result is a blind model-construction evaluation.

See the [n8n setup, commands and timing notes](n8n/README.md).

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

## Cloud-agent workflow construction (SWE-bench-style experiment)

`AutomationBenchEval` and `experiments.AutomationBench` now use the same
dataset/agent lifecycle as `SweBenchLite`, rather than staging a reference
implementation:

1. In the **parent evaluation environment**,
   `bootstrap_parent.sh` installs `uv`, syncs Python 3.13 dependencies,
   and verifies the pinned upstream source in a fresh Scala consumer.
   `runner.py dataset --domain operations --task-id 1219 --limit 1
   --format json` loads public dataset rows as a JSON array.
   Rows contain the user prompt and permitted tool descriptions, **never**
   `initial_state`, `answer`, or assertion details. The parent retains
   the upstream simulator and grader.
2. `ParallelFor` creates a **new temporary project** per row and starts a
   **Scala GKE cloud consumer** with `producer/start` /
   `imageProfile=scala`. The consumer's initial filesystem is empty;
   the experiment clones `awfl-us/workflows` explicitly into
   `/workspace/workflows` and sets that as the agent's working directory
   before handing off the task. Do not assume attaching a project has already
   cloned the repository. It **preserves the repository's original
   `AGENT.md`** with its DSL and dependency guides, writes the separate
   `AUTOMATIONBENCH_AGENT.md`, and updates the task-local
   `.awfl/config.json` so the built-in `agent.sh` preload reads **both**
   documents every turn. The sandbox config also replaces the repository's
   unrelated hardcoded testbed command with `pwd`. A preflight verifies both
   files and the secondary preload before ProjectManager starts.
3. `ProjectManager` receives a real `Task` with the task-specific prompt,
   a fixed `fund` budget, and instructions to write an independent Scala
   workflow at
   `src/main/scala/us/awfl/workflows/evals/automationbench/AgentSubmission.scala`.
   The required output is a `AgentSubmission` object and one `submit`
   variant, using the simulator `http.post` tool transport.
4. The parent collects the generated source, agent wall time, and LLM usage.
   It always attempts to stop the consumer and delete the temporary project.
5. `grade_submission.py` freezes each source, **clones a local disposable
   workflows checkout** (not a linked Git worktree; sbt-git/JGit does not
   support those), invokes the shared `build_native.sh` → `native-dev.py
   --class` compiler, and evaluates the frozen executable through the
   independent simulator and official rubric.
6. Per-task results are saved as JSONL and aggregated into the existing
   `Eval` CSV. Builder time, compilation time, runtime latency, and LLM
   cost are distinct fields. The public test row remains an iteratively
   known task; this is not a held-out model leaderboard.

Cloud `ProjectManager` usage is gated by the normal AWFL service
authentication and per-run budget. **Do not run untrusted benchmark-builder
submissions with credentials on the parent scoring host**: Scala object
initialization during compilation is executable code. The initial prototype
assumes trusted agent output and must move compilation into an isolated
credential-free builder container before accepting adversarial submissions.

The Python reference workflow and prebuilt n8n graph remain available as
execution regression fixtures; they are no longer the Scala experiment's
builder output.

## Next milestones

1. **Implemented in the Scala experiment:** load public dataset rows and run
   AWFL ProjectManager for each in a fresh project/Scala cloud consumer,
   with a fixed fund, source collection, usage, and cleanup. Add a CI smoke
   and verify a full launched execution before claiming an agent-produced
   result. Builder tool-call counts and token breakdown remain unmeasured.
2. Generalize the AWFL-to-simulator protocol adapter for other tasks, varied
   tool argument schemas, failures and more robust date/notice formats.
3. **Done for task 1219:** the n8n platform adapter runs a real workflow
   against the same independently reset simulator. Extend it beyond this
   single public task and compare **warm-engine** execution fairly.
4. Add multiple independently seeded input/state variations per artifact. This
   extends AutomationBench and must be reported separately from the official
   one-scenario task score.
5. Isolate evaluator credentials and grader files in a separate sandbox,
   track builder versus runtime token/cost usage, and compare repeated trials.

The initial concurrent cloud-agent smoke test required a **GKE namespace
quota adjustment**: 17Gi already reserved plus a new 4Gi Scala consumer
exceeded the previous 20Gi ephemeral-storage quota. The quota was raised
to 24Gi through the separate `awfl-us/server`
`infra/gke-guardrails` Terraform root (server PR #136), leaving pod,
memory and CPU ceilings unchanged.
