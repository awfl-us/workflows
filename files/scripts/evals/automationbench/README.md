# AutomationBench integration (native AWFL workflow vertical slice)

**Current handoff and next steps (November 17, 2026):**
[AutomationBench native Rust execution plan](../../../../docs/automationbench-native-handoff.md).
The compiler and native coordinator IPC startup passed for all 28 workflow
binaries. Nested local Rust fork/child dispatch also passed, reaching the
first Jobs HTTP effect. Two actual local Jobs writes passed against the
Firestore emulator, before the driver hit its unimplemented native callback
effect. The full agent-produced benchmark is not yet complete.
**Deployment reuse:** the agent/ProjectManager workflows are already
deployed. Run them through the normal hosted AWFL executor, not via a
newly compiled 28-artifact registry. Build only the two *new benchmark
orchestrator* artifacts if needed; compile the newly authored
`AgentSubmission.scala` separately for grading.
Do not deploy Google Workflows YAML or publish the 28-binary closure
over existing production aliases.

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

## Native Rust benchmark orchestration

The existing `codebase-ProjectManager` and helper workflows are already
deployed and should be used as-is. **Do not rebuild the 28-workflow
dependency tree** to execute this benchmark. The only potentially
unpublished new workflow names are `evals-AutomationBenchEval` and
`evals-experiments-AutomationBench`; resolve their child calls to
the existing published agent workflow releases.

`scripts/native-dev.py --publish` presently requires `--recursive`
and the experiment is not a standalone registered target, so it cannot
currently publish only these two through its normal flags. Add a
benchmark-only selective build/publish mode or run the orchestration
via an isolated path, without changing any of the 26 production aliases.
The past compiled local root/child IPC smoke is useful evidence, but
no longer a prerequisite. The generated Google Workflows YAML is still
too large to deploy and should not be used.

The current builder model is gpt-6-sol. For the single-dollar budget,
core.int replaces the unsupported double CEL conversion. The experiment
checks that an agent wrote real Scala source before independent grading.

### Latest deployed-agent integration smoke (November 17, 2026)

A fresh GPT-6 Sol native ProjectManager session received public task 1219
and all eight permitted tool descriptions in its first message. It authored
a 6,938-byte source (SHA `1b5ddd68acd5f20db3de243106ce0807c55e73af9c9fbb5d8da229b8688590a4`)
in 32 model rounds, estimated **$1.006506** usage. The agent was manually
cancelled after exceeding the intended aggregate $1 fund. No deployed
ProjectManager/helper workflow was rebuilt or republished.

Independent Scala compilation passed. Rust native compilation initially
blocked on missing `text.split` semantics; see
[compiler-rust PR #41](https://github.com/awfl-us/compiler-rust/pull/41)
(merged after all CI checks passed). With that native fix applied locally, the **unchanged frozen agent source**
compiled in 113.779 seconds and executed its first simulated Gmail tool
call in 0.171 seconds. It passed `max_results` as a string instead of an
integer, then tried decoding the simulator's error response as success.
Result: **strict 0, partial 0, 1 business tool call, runtime_failure**.

**Not a valid blind leaderboard score:** the agent read the repository's
previous hand-authored `EmergencyDrill.scala` task solution before that
file was removed. Future blind runs must exclude all reference solutions
before first agent access, supply only public task/tools, enforce a true
cross-execution aggregate budget, and require native compilation and
official isolated grading. Full evidence and next steps are in
`docs/automationbench-native-handoff.md`. Do not copy the reference
solution into the builder's repository.

## Cloud-agent workflow construction (SWE-bench-style experiment)

`AutomationBenchEval` and `experiments.AutomationBench` now use the same
dataset/agent lifecycle as `SweBenchLite`, rather than staging a reference
implementation:

1. In the **parent evaluation environment**,
   `bootstrap_parent.sh` installs `uv`, syncs Python 3.13 dependencies,
   and verifies the pinned upstream source in a fresh Scala consumer.
   `runner.py dataset --domain operations --task-id 1219 --limit 1
   --format json` loads public dataset rows as a JSON array.
   Rows contain the user prompt and permitted tool descriptions **plus
   typed JSON request schemas** generated from the actual upstream Python
   signatures: each argument has a JSON Schema (`schema`), `required`, and
   a JSON-compatible `default` when defined. This preserves nullable
   fields, arrays, unions, enum choices, and booleans; for example,
   `gmail_find_email.max_results` is `integer | null`, default `10`, **not**
   a string. No `initial_state`, `answer`, or assertion details are sent.
   The simulator's public `/tools` inventory uses the same request schemas.
   The parent retains the upstream simulator and grader.

   For a standalone builder, generate the public-only file **with this
   runner**, rather than copying an old untyped task prompt:

   ```bash
   uv run --project /workspace/automationbench-upstream python \
     /workspace/workflows/files/scripts/evals/automationbench/runner.py prepare \
     --domain operations --task-id 1219 \
     --out /workspace/workflows/AUTOMATIONBENCH_TASK_1219.json >/dev/null
   ```

   Ensure the first successful builder request/preload actually contains or
   points to that generated typed spec; a stale `AUTOMATIONBENCH_TASK_1219.md`
   from prior runs is not a substitute. The separate simulator/grader state
   must remain inaccessible to the builder.
2. `ParallelFor` creates a **new temporary project** per row and starts a
   **Scala GKE cloud consumer** with `producer/start` /
   `imageProfile=scala`. The consumer's initial filesystem is empty;
   the experiment clones the workflows, dsl, and workflow-utils repositories
   into separate directories under /workspace and uses workflows as its
   agent working directory before handing off the task. Do not assume attaching a project has already
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

## Next milestones (native Rust path)

**Current stage: native root/fork/child dispatch verified; callback bridge pending.**
The 28 Rust binaries were rebuilt and verified on November 17. The new
`smoke_native_dispatch.mjs` proved the compiled `native.parallel` lane,
two child `workflow.run` calls and the first HTTP effect without sending it.
A separate run against the actual local Jobs service completed two
`/firebase/create` calls with a Firestore emulator and then stopped at
unimplemented `callback.create` in its temporary driver. No live model
request or independently graded agent submission is verified.

1. Check the deployed `ProjectManager` and agent/helper native
   releases; use the platform's normal hosted execution, Jobs and
   callback path. The temporary local driver's `callback.create`
   limitation does not imply deployed callbacks are broken.
2. If necessary, selectively build/publish **only** the two new
   AutomationBench coordinator names. No full closure rebuild or
   change to existing 26 aliases.
3. Run one operations/1219 agent attempt with `gpt-6-sol`, $1
   fund, the required repositories and instruction preloads. Verify
   actual provider acceptance and a written Scala draft.
4. Freeze that newly generated `AgentSubmission.scala`, compile only
   that candidate, then independently score it on reset simulator
   state. Capture provider spend, build time, runtime trace, official
   assertions and cleanup.
5. Add regression/CI coverage for missing source, native root/child launch,
   provider failures and terminated consumer cleanup, then expand public
   tasks and sandbox hidden tests/credentials.

For commands, release safety, the exact prior failures, and acceptance
criteria, see the [session handoff](../../../../docs/automationbench-native-handoff.md).

The initial concurrent cloud-agent smoke test required a GKE namespace
quota adjustment. The separate awfl-us/server Terraform root now defines
100 pods, 100 jobs, 100 vCPU, 256Gi memory, and 512Gi ephemeral storage
(server PR #136). These are admission limits, not resource reservations.
