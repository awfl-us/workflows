# AutomationBench native Rust — session handoff and execution plan

**Status verified November 16–17, 2026.** This is the authoritative task-specific handoff for [workflows PR #25](https://github.com/awfl-us/workflows/pull/25), branch `feat/automationbench-eval`. Read [AutomationBench runner README](../files/scripts/evals/automationbench/README.md), [native development loop](native-development-loop.md), and [native publication/runbook](native-rust-publication.md) alongside this document. The Scala DSL remains the authoring interface; **Rust is the intended evaluator/ProjectManager runtime. Do not deploy the generated Google Workflows YAML**.

## Updated execution strategy: reuse deployed agent workflows

**The existing ProjectManager and its agent/helper workflows are deployed.
Do not rebuild or republish them for AutomationBench.** The previous
28-binary closure build remains valid historical compiler and local-dispatch
evidence, but is not a prerequisite or the target deployment architecture.

Separate these pieces:

- **Agent runtime:** invoke the already-published `codebase-ProjectManager`
  and child workflows (including `tools-CliTools`) through the regular
  authenticated AWFL execution API. Verify the deployed release IDs/ABI and
  actual native selection; use existing Jobs/callback handling. The ad-hoc
  loopback driver's `callback.create` limitation is not evidence of a
  problem with deployed agent callbacks.
- **Benchmark coordinator:** only `evals-AutomationBenchEval` and
  `evals-experiments-AutomationBench` are new orchestration artifacts.
  If not already runnable, build and activate only these two, resolving
  their `workflow.run` references to the published agent versions.
  `scripts/native-dev.py --publish` presently requires `--recursive`;
  the experiment isn't a standalone registered root. Selective publication
  therefore needs a small explicit tooling change or an isolated evaluator
  execution path. **Never use broad `--recursive --publish`: 26 existing
  production aliases differ from the local builds.** Compare active alias
  sets before/after and require no existing alias mutations.
- **New candidate:** compilation is still required for the agent's *new*
  `AgentSubmission.scala`, frozen and independently scored by the grader.
  This is not rebuilding the agent itself.

## Follow-up verification: native dispatch and local Jobs (November 17, 2026)

- Reattached the workflows Scala consumer and **rebuilt all 28 static Rust/musl binaries**, after the earlier ephemeral Cargo artifacts disappeared. Exact result:
  `AWFL_NATIVE_DEV_PASS root=evals-AutomationBenchEval workflows=28 publish=false elapsed=762.0s`.
  The existing root IPC startup smoke passed again with first effect `native.parallel`. No GCS release alias was published.
- Added a reproducible **real-binary nested-dispatch preflight** at
  `files/scripts/evals/automationbench/smoke_native_dispatch.mjs`. With a
  clean sibling server checkout it executes the compiled evaluator and records
  **one native.parallel lane**, **two nested workflow.run calls**
  (`evals-experiments-AutomationBench` then `tools-CliTools`),
  and the first `http.post /firebase/create` effect. It deliberately
  throws *before* the first HTTP call; the controlled error is expected and no
  external writes are made. All binaries are SHA-256 checked against the
  local build report. Server `localWorkflows.test.js`: 7 passed, 2 optional
  integration tests skipped, 0 failed.
- A clean checkout of server `main` ran the **actual Jobs Express service**
  on `127.0.0.1:5055`: `/health` and `/jobs/events/health` returned 200.
  Its initial Firestore write to the real database failed
  `7 PERMISSION_DENIED` under this development consumer's credentials.
  Restarting Jobs with local `FIRESTORE_EMULATOR_HOST=127.0.0.1:8085`
  allowed **two real Jobs HTTP `/firebase/create` calls to succeed**,
  then the workflow reached `callback.create`. The temporary local effect
  driver intentionally does not implement native callbacks. **This is a local
  driver/integration boundary, not a confirmed compiler bug.**
- Pinned Zapier upstream simulator setup succeeded:
  `AUTOMATIONBENCH_PARENT_READY pin=4a8e1061254004d9dac807054eed33fad7d1ff14`.
  The public `operations/1219` row loads.
- **No `gpt-6-sol` model invocation, agent-produced Scala submission,
  independent official grade, or benchmark spend is established.** The
  `fund=1` and `model=gpt-6-sol` appeared in the evaluator's native
  startup log, but it did not reach the model provider.

**Historical diagnostic only — NOT a prerequisite for future benchmark runs.**
The earlier development session used this full-closure command solely to
prove compiler/IPC behavior (from `/workspace/workflows`, with an isolated
server checkout). Do not run it just to invoke the already deployed agent:

```bash
python3 scripts/native-dev.py evals-AutomationBenchEval --recursive \
  --output eval_runs/automationbench/native-coordinator-dev
python3 files/scripts/evals/automationbench/smoke_native_coordinator.py \
  --report eval_runs/automationbench/native-coordinator-dev/build-report.json
AWFL_NATIVE_SERVER=/workspace/automationbench-server node \
  files/scripts/evals/automationbench/smoke_native_dispatch.mjs
```

**Next gate:** resolve the existing deployed ProjectManager/child releases
and run through the normal authenticated native Jobs/callback path. Only
prepare the two new benchmark coordinator releases if missing; do not build
existing agents or substitute local callbacks. Then run one task 1219 with
`gpt-6-sol` and $1 fund, freeze the new Scala submission, compile that
candidate and independently grade it. Record true model spend and cleanup.

## Consumer access and checkout preservation (latest)

The AWFL workflows project is `gNoQN9KUw5IbtsXLag0M`. Its previous
Scala consumer `consumer-mwjyx9h8-2p1mnb29` failed four startup attempts
(`BackoffLimitExceeded`) while reporting Firebase credential bootstrap
`HTTPError` and refusal to run with stale credentials. Stopping the
terminally failed consumer and attaching a new `scala` consumer **restored
shell and git access**. Last verified workspace ID:
`8474d9e8837d656d7b3ff317ead44911`; **reattach by project ID**
rather than relying on that session-scoped workspace ID.

The restored `/workspace/workflows` checkout is on
`feat/automationbench-eval`, fast-forwarded to the latest pushed docs.
The build report at
`eval_runs/automationbench/native-coordinator-dev/build-report.json`
survived the restart, **but its referenced Rust binaries did not** (the
compiler-rust Cargo target directory was ephemeral). A rerun of
`smoke_native_coordinator.py` failed immediately on the missing binary;
the 28 binaries were subsequently rebuilt and tested; the previous
full-closure build is complete. **Do not repeat it as a requirement for
running the deployed agent.** This was not a source compilation failure.

Three unrelated local dirty paths **must be preserved, not staged or reset**:
`functions/workflows/types/services-Llm-ChatToolResponse.json`,
`infra/terraform.tfstate`, `infra/terraform.tfstate.backup`.
All benchmark code and docs were committed separately to the branch.

## Actual verified state

- The benchmark wraps Zapier's public [AutomationBench](https://github.com/zapier/AutomationBench) pinned to `4a8e1061254004d9dac807054eed33fad7d1ff14`. Task **operations/1219**, “HQ Emergency Drill”, is the initial one-row smoke. The parent loads the prompt and allowed-tool descriptions, not the initial world, answers or assertions. Python runner resets an independent simulator and applies upstream's official assertions.
- The Scala `evals-AutomationBenchEval` root and `evals-experiments-AutomationBench` experiment are registered in `scripts/native-dev-targets.json` with the ProjectManager dependency closure. `scripts/native-dev.py ... --recursive` **successfully generated and verified 28 static x86-64 musl Rust executables** on November 17: `AWFL_NATIVE_DEV_PASS root=evals-AutomationBenchEval workflows=28 publish=false elapsed=398.2s` (cold Scala/Cargo build). No GCS artifact aliases were published.
- `files/scripts/evals/automationbench/smoke_native_coordinator.py` ran the compiled root directly under native IPC protocol 2 and verified a `suspended` response with the `native.parallel` effect, an effect ID, and a continuation. **This is only a no-effects startup smoke, NOT a complete benchmark or a scored submission.**
- The initial native compile failed on the Google-specific `double(...)` CEL call. The current single-dollar fund value is `1` and its conversion uses native-supported `core.int`; the second build passed.
- The agent environment now clones **three separate repositories**: `/workspace/workflows`, `/workspace/dsl`, and `/workspace/workflow-utils`. Original `AGENT.md` and `AUTOMATIONBENCH_AGENT.md` should be preloaded together. The instructions require an early draft at `src/main/scala/us/awfl/workflows/evals/automationbench/AgentSubmission.scala`.
- `validateAgentSubmission` checks that the candidate file exists and exceeds 200 bytes before the evaluator reads or grades it. In an earlier run, `READ_FILE` returned a 136-byte “file not found” error string and the grader treated it as candidate source. This guard prevents that false handoff, not bad/invalid Scala.
- The builder is configured to use `gpt-6-sol`, budget **$1** for the current task. **No live `gpt-6-sol` request and no successful AI-produced, independently graded workflow have been verified.**
- Existing reference fixtures: manually authored native Rust-compiled Task 1219 workflow scored 8/8 assertions with strict and partial score 1.0 **after public-task debugging**; a separately built n8n graph also scored 8/8. **Neither is a blind agent-generated benchmark result.** The Python harness and n8n graph had 13 passing adapter/graph regressions in the prior development session.
- The old Google Workflows YAML experiment was about **143 KB**, above the 128 KB source limit; its deployment was rejected. **Do not resume that path.**
- The local 28-binary manifest was compared against published aliases: **two new benchmark names** (`evals-AutomationBenchEval`, `evals-experiments-AutomationBench`), **26 changed existing aliases** (including `codebase-ProjectManager`), zero unchanged. Do **not** run `python3 scripts/native-dev.py evals-AutomationBenchEval --recursive --publish` against the shared production bucket; it would update production workflow aliases.
- An earlier local host test was **not completed**. An independent `awfl-us/server` main checkout had no `package-lock.json`; `npm ci` failed immediately, then `npm install --no-save --package-lock=false --ignore-scripts --no-audit --no-fund` was interrupted when the GKE consumer ended (`execution_lost`). That particular attempt produced no child-workflow result; the subsequent
November 17 run **did** confirm two real Jobs writes against a local
Firestore emulator, as documented above.
- Existing AWFL native ProjectManager has previously completed authenticated tool/final-response smoke tests, but this is **not** evidence the new benchmark coordinator works end to end. Native production routing is already enabled for published supported workflows; unpublished names may silently fall back to Google unless the execution backend is verified.

## Hosted deployed-agent smoke: public operations/1219 (November 17, 2026)

**Release publication and isolation are verified.** The two prebuilt native
benchmark artifacts were activated individually through
`scripts/publish-native.sh` with the verified `AWFL_NATIVE_PREBUILT_BINARY`;
neither the compiler nor the 26 existing deployed ProjectManager/agent
dependencies were rebuilt or published. A before/after download of active
GCS release mappings found **28 -> 30** releases: exactly two new aliases
and **0 changed or deleted preexisting aliases**:

- `evals-AutomationBenchEval`, SHA
  `98d9cf1368483c5412e13f0a0c68b7f9317a12aaa65aec8cea0a5d57ae4696ff`.
- `evals-experiments-AutomationBench`, SHA
  `7f68bbca160130c8ff9b5af2e4702f0454afd23997632fe5f73adfe45aa2bbff`.

The normal hosted native `codebase-ProjectManager` launcher was then tested
against public `operations/1219` in an isolated temporary project
(`AxrE6ElSkfvBGe37bPKN`). The Scala workspace contained the
workflows/dsl/workflow-utils repositories and both AGENT preloads.

**Two production Jobs bugs were discovered and fixed without touching agent
workflow releases:**

1. The initial GPT-6 Sol function call failed with HTTP 400 because Chat
   Completions does not accept function tools with reasoning enabled. Server
   [PR #140](https://github.com/awfl-us/server/pull/140) sent
   `reasoning_effort: none` for Sol function-tool calls (preserving the
   original tool-call protocol) and omitted the unsupported temperature
   override. Production deployment succeeded.
2. The $1 budget meter had no `gpt-6-sol` rate and fell back to expensive
   legacy GPT-4 pricing, also adding cached input twice. Server
   [PR #141](https://github.com/awfl-us/server/pull/141) added Sol's
   $2/M input, $0.20/M cached input, $10/M output rates, with correct
   cached-token subtraction and unit tests. Cloud Run Jobs revision
   `jobs-00191-n5n` was verified serving 100% traffic.

**Actual builder outcome:** native workflow executed GPT-6 Sol tool calls,
created a task, inspected DSL code, wrote
`src/main/scala/us/awfl/workflows/evals/automationbench/AgentSubmission.scala`,
and successfully ran `sbt compile` on 53 Scala sources. It ended
`Done` after **25 model rounds**, with **$0.810136 recorded API-cost
estimate** (not a billed/invoiced amount) inside the $1 cap.
Frozen final source SHA:
`ce7ef16e58ffcf5c52c66bdaef91e753dbd5b8967d1579988111026e746b7b66`;
size **1,175 bytes**. Stored under ignored
`eval_runs/automationbench/direct-builder-1219-20261117/`.

**Important benchmark-validity problem:** the first `create_session` included
the full 7,673-character public `task_spec`, but its model request failed
before tool use. The subsequent `send_message` resumed with a short
instruction rather than retransmitting the full task specification.
Although the prior user message was visible in session history, the model
repeatedly reported it lacked the task-specific public prompt and allowed
tool list. An identical **public-only** task-spec file was added to the
ephemeral builder workspace and local preload mid-run, but the agent still
left a generic `operations.list` placeholder and explicitly stated the
real business operations were not implemented. This is **not a valid blind
task-success claim**, even if the Scala code compiles. The developer
harness must ensure the public prompt and allowed tools reach the actual
builder model context on the first successful execution, not just sit in
prior session history. No grader assertions or initial simulated state
were disclosed to the agent.

**Next run preflight:** create a *fresh* full-spec task invocation (or run
the published benchmark coordinator with its typed `arg1`/`arg2` inputs)
rather than relying on a resumed short follow-up. Verify the first
model-visible user prompt includes the actual public allowed tool names
and test that the agent can reference one of them before running an entire
$1 attempt. Existing `mcp__AWFL_Agent_Sessions__create_session` accepts a
chat query, not the typed evaluator root inputs; do not treat it as an
equivalent root invocation. Do not pre-seed business answers.

## Scoring adapter repair and fresh full-context attempt (November 17, 2026)

The first frozen agent-generated file
(`ce7ef16e58ffcf5c52c66bdaef91e753dbd5b8967d1579988111026e746b7b66`)
compiled independently to native Rust in **279.064 seconds**. The first
grading attempt exited before any simulator call with
`Error: MissingField("env")`, originating from the AWFL
`PostRequest` helper's automatic OIDC audience expression. This was a
**grader input-adapter bug**, separate from the agent's placeholder logic;
its initial strict/partial 0/0 should not be attributed to business
performance.

Fixed `files/scripts/evals/automationbench/native_driver.py` to add
`input.env.BASE_URL` set to the **local simulator bridge URL**. This
grants no cloud credentials, secrets or trusted native globals. The
regression suite reports **10 passed**. Re-evaluating the **same
immutable compiled artifact without rebuilding it** now succeeds at
native runtime in **0.194 seconds**, with **no runtime error, 0 business
calls, strict 0/partial 0**: the agent's `operations.list` placeholder
is not a permitted business operation and does not fulfill task 1219.
The latter is a genuine zero-credit submission, not a runner crash.

**Fresh reattempt:** `codebase-ProjectManager` was launched under a
**new session** `e2b8529c-7f96-4c8b-8f20-7ba72ea1ce55` with model
`gpt-6-sol`, $1 cap, and all **7,673 characters** of the public task
spec plus eight allowed tool definitions in the **first, 9,717-character
user message**, not a follow-up or history reference. The first session
message was re-read and verified to contain the public task name and
last allowed tool. Before launch the prior generated Scala file was
archived outside the active source path; this session creates its own
submission. The agent's initial task creation correctly described
Gmail, Monday.com, Google Calendar and Slack operations. **Record the
final runtime, cost, file hash, grader score and cleanup when this
session completes.** No published agent workflows were modified.

## Second full-context attempt: budget enforcement failure (November 17, 2026)

A fresh `codebase-ProjectManager` session was started with the exact
public `operations/1219` specification and **all eight permitted
business-tool definitions** in its *first* 9,717-character user
message, so the prior missing-task-context explanation does **not**
apply to this run. The first message was read back from session
history and verified to contain the full tool list. The old
`AgentSubmission.scala` was archived outside the builder's active
source path. Three preloads (`AGENT.md`,
`AUTOMATIONBENCH_AGENT.md`, `AUTOMATIONBENCH_TASK_1219.md`)
were present, and the deployed agent's first task explicitly
recognized the Gmail, Monday.com, Calendar and Slack operations.

- Project: `AxrE6ElSkfvBGe37bPKN`
- Session: `e2b8529c-7f96-4c8b-8f20-7ba72ea1ce55`
- Backend: deployed native `codebase-ProjectManager`; model
  `gpt-6-sol`, requested per-execution `fund=1`
- Observed: **30 assistant/model rounds** with many shell/DSL
  investigations, **$1.03422 cumulative estimated model cost**.
- At the stop point, the agent had **not written any**
  `AgentSubmission.scala`, despite repeated task updates saying it
  would do so. No compiled candidate or independent score exists.
- The `fund=1` parameter did **not** enforce a $1 **cumulative
  session-level** spend cap across the ProjectManager's recursive
  executions. Once cumulative recorded usage exceeded $1, the
  session's latest native execution was cancelled using the normal
  AWFL stop mechanism (confirmed cancellation of
  `native_b4f91ce3eee42f51043d4eba4cccf69f`). Session indexing
  may update asynchronously after cancellation; verify status before
  any subsequent action.

**Interpretation:** the full public context was available; the
agent consumed the budget researching DSL internals and never
produced a submission. This is an **incomplete/unscored attempt**,
not official 0/8 benchmark performance and not a compilation
failure. It should not be compared numerically to independently
graded submissions.

**Before retrying paid runs:** enforce a single **aggregate
builder-session budget** in the coordinator (sum model costs from
the complete session, not the `fund` supplied to each recursive
execution) and stop the whole execution tree when exceeded.
Create a bounded *source-production checkpoint* early in the
run; minimize broad repo/DSL archaeology by supplying a short
task-appropriate DSL + bridge example in the PUBLIC agent guidance.
Do not give the agent the hidden simulator world or official
assertions. Only after these safeguards, run a new $1 blind trial,
compile the agent-authored source and apply the official reset-world
grader. Do not rebuild already published ProjectManager workflows.

## Third fresh attempt: produced source, native compiler gap, scored failure (November 17, 2026)

The full public task/tool schema was provided in the **initial 9,809-character
user message**, and the agent independently read the identical
`AUTOMATIONBENCH_TASK_1219.md` public-only preload. The deployed native
`codebase-ProjectManager` was reused with no agent workflow recompilation.

- **Project/session:** `pmbench-152f82a5e40c4048ba38946a22ad094f` /
  `0939f630-848c-4be2-aca8-8055442789c0`.
- **Model/fund:** `gpt-6-sol`, `fund=1`, **32 model rounds**, sum of session
  cost records **$1.006506** (estimated, not invoice data). The coordinator's
  per-execution fund did **not** stop recursive ProjectManager's aggregate
  usage at exactly $1; the session was explicitly cancelled on exceeding
  $1, and no further model calls were made.
- **Frozen agent-authored file:** 6,938 bytes, SHA-256
  `1b5ddd68acd5f20db3de243106ce0807c55e73af9c9fbb5d8da229b8688590a4`;
  saved under ignored
  `eval_runs/automationbench/direct-builder-1219-retry-20261117/`. The
  agent constructed real `gmail_find_email`, Monday.com date/status,
  Google Calendar and conditional Slack effects and source-derived date
  processing, not a generic placeholder.
- **Scala compilation:** First `sbt` run timed out; second surfaced four
  Scala DSL errors (`CelIndex`, `=!=`, and two incorrect `Fold` list
  types). The agent patched them just before the budget stop. The **frozen
  final source subsequently passed Scala compilation** in the independent
  grader.
- **Rust compilation gap found and fixed locally:** The compiler knew
  `FunctionId.TextSplit` but lacked an expression emitter and runtime
  `text.split` implementation. Added literal Unicode-safe split semantics
  with leading/trailing/adjacent empty segments and rejection of empty
  separators and non-string arguments in
  [compiler-rust PR #41](https://github.com/awfl-us/compiler-rust/pull/41),
  feature commit `9bd72f4` (**merged** into main as `5850a5a` after CI
  Rust, Scala-to-native and workflow compatibility checks passed). Local
  Rust unit suite **26 passed, 0 failed**; Scala
  `TextSplitSmoke` passed. Rebuilt and independently graded the **same
  frozen source**, without an additional model request or updating any
  deployed workflow aliases. The compiler fix remains in a separate PR
  unless explicitly merged/deployed.
- **After compiler fix, native build:** **success**, **113.779 seconds**;
  execution reached the simulator and made **one business tool call** in
  **0.171 seconds**, then failed at runtime:
  `gmail_find_email` passed `max_results: "50"` as a **string** where
  the simulator expected an integer, causing
  `TypeError: slice indices must be integers or None or have an __index__ method`.
  The compiled workflow then called `json.decode` on the resulting
  error response, failing with
  `Type("json.decode requires text or bytes")`.
  **Official strict score 0.0 / partial credit 0.0**, category
  `runtime_failure`; no Monday/Calendar/Slack mutations.
  The temporary builder Scala GKE consumer was stopped after frozen
  source/score outputs were copied to the parent, with no active commands
  remaining. The compiler change was merged but **not** deployed to any
  published ProjectManager workflow or release alias.
  Grader output: `grade-native-split/score_summary.json`, with traces
  under `grade-native-split/score/`. Grader's
  `builder_cost_usd` is null because the direct builder path is outside
  its coordinator; cost recorded separately above.

**Benchmark validity caveat:** The new agent read the existing hand-authored
`EmergencyDrill.scala` reference implementation in its checkout *before*
it was removed. This means the run is a useful **end-to-end integration
smoke**, **not a clean blind benchmark result**, despite the official
grader producing a score. The user-facing score must be labelled accordingly.

**Before any further scored paid attempt:** (1) exclude task-answer reference
files such as `EmergencyDrill.scala` **before the agent launches**, not
after; (2) enforce the **aggregate** $1 session budget and a bounded
source/compile checkpoint; (3) provide a small public-only
native-compatible CEL/DSL and typed JSON arguments example (the existing
`Map[String, Value[String]]` design forced numeric `max_results` into
a string); (4) handle simulator tool errors without attempting to decode
them as successful results; (5) verify the final frozen source compiles
through **both Scala and Rust**, not Scala alone. Then launch one fresh,
fully blind task and report its independent score. No need to rebuild or
republish deployed agent workflows.

## November 17 follow-up: recovered execution and independent regrade

- Native replacement execution `native_c89a119d1a1b4a5fbd6d6bf160a514c7`
  in project `pmbench-152f82a5e40c4048ba38946a22ad094f` reached
  `completed` in Firestore with **98 effects**, `workflowName` =
  `codebase-ProjectManager`, and no stored error. The original execution
  `native_88e45cd532c7ee884a68caaca47d7b7f` remained `cancelled` after
  19 effects. This confirms recovery progress, **not benchmark correctness**.
- The recovered benchmark workspace's `AgentSubmission.scala` contains
  **6,938 bytes**, SHA-256
  `1b5ddd68acd5f20db3de243106ce0807c55e73af9c9fbb5d8da229b8688590a4`.
  This **exactly matches** the previously built and graded direct-builder
  retry; the recovered execution did not provide a new frozen candidate.
  Do not count it as a fresh blind attempt or another model score.
- Reused that **unchanged native Rust artifact** and ran the independent,
  pinned official `operations/1219` simulator/grader on a reset state,
  without launching a builder or compiling new submission code. Artifacts
  are under `eval_runs/automationbench/pmbench-independent-regrade-20261117/`
  (ignored local outputs). Result: **strict 0.0, partial 0.0**,
  **1 business call**, `runtime_failure`, **0.254 s** workflow runtime.
  First `gmail_find_email` used `max_results: "50"` as a **string**, causing
  the simulator to report a Python slice-index type error; the native
  workflow then failed with `json.decode requires text or bytes` when it
  tried to treat the error as a successful result. No Monday, calendar,
  or Slack business call occurred.
- Temporary evaluator consumer startup also encountered a **stale project
  lock** and repeated HTTP **403** at `/consumer-credentials/storage`,
  resulting in delayed/unclaimed shell commands. A fresh Scala consumer
  was able to inspect the saved workspace, but this infrastructure issue
  is distinct from the independently reproduced submission failure.
- **Next valid attempt:** start with an answer-free agent workspace,
  correctly typed public-only tool schemas, a fresh source and hash,
  aggregate budget enforcement, and a separate isolated grader. Do not
  repair the already frozen submission and claim the repaired artifact as
  the original agent result. No additional GPT-6 Sol builder attempt was
  launched for this regrade, so this operation incurred **no new model
  budget** (existing historical usage is documented above).

## Next session: ordered execution plan — deployed agents first

1. **Check published runtime, no builds.** Attach the existing AWFL project,
   preserve unrelated dirty files, and inspect the published native
   `codebase-ProjectManager`, `tools-CliTools` and helper workflow
   releases/identities. Verify backend selection, ABI, Jobs and callback
   contracts through the existing hosted service. No compilation or
   publication of deployed agents.

2. **Use deployed children.** Invoke `ProjectManager` and its helper
   workflows through the normal authenticated workflow execution path,
   rather than loading 28 locally generated binaries into a temporary
   runner. Prove one real child execution/callback through the hosted
   runtime. Only investigate callback code if that deployed path fails.

3. **Prepare just the benchmark coordinator if needed.** Check availability
   of `evals-AutomationBenchEval` and
   `evals-experiments-AutomationBench`. If absent, build **only these
   benchmark-specific artifacts** and use existing deployed releases for
   their `workflow.run` dependencies. Implement a selective publisher
   or isolated orchestrator: the current `native-dev.py --publish`
   requires `--recursive` and the experiment is not an independent
   registered target. Require the before/after alias diff to contain
   **no** ProjectManager/helper names. No YAML, no 28-binary rebuild.

4. **One paid task 1219.** In the parent evaluator, initialize the
   pinned public simulator; create one fresh Scala agent consumer, clone
   workflows/dsl/workflow-utils, preload both instruction files and
   execute the **deployed** ProjectManager with `gpt-6-sol`, $1 fund.
   Verify model acceptance, tool progress, and a genuine nonempty
   `AgentSubmission.scala`; record builder run ID/tokens/cost and
   terminate the temporary resources.

5. **Compile ONLY the agent-authored submission for grading.** Freeze
   `AgentSubmission.scala`, then run the existing
   `grade_submission.py` -> `build_native.sh` ->
   `native-dev.py --class` path. Execute the result against a reset
   upstream simulator and record official strict/partial scores, traces,
   compile errors and timings. Do not use the hand-authored 8/8 fixture.

6. **Verify cleanup and isolation.** Confirm the temporary cloud project,
   consumer, callback state and benchmark resources were cleaned up,
   capture real spend, and verify all 26 existing production aliases
   remain unchanged. Then expand to additional public tasks.

## Risk and scope boundaries

- **Do not rebuild existing deployed agents, deploy YAML, or broad-publish
  the 28-binary closure.** Use the already deployed native runtime; preserve
  all 26 existing production workflow aliases.
- Scala object initialization during native compilation is executable code. The current grader compiles agent-created files on a credential-bearing host: fine only for trusted development output; **isolate grading credentials and filesystem before adversarial/hidden evaluations**.
- A successful Rust binary build or startup response is not a successful child execution, agent run, or official benchmark score.
- GKE namespace limits in `awfl-us/server` Terraform were raised separately to 100 pods, 100 jobs, 100 vCPU, 256Gi memory and 512Gi ephemeral storage. These quotas are ceilings, not resource reservations; they do not prevent consumer node failure.
- Keep native compiler work in `awfl-us/compiler-rust`, Jobs/native executor fixes in `awfl-us/server`, and benchmark definitions/harness/docs in `awfl-us/workflows`. Any infrastructure/IAM changes must go through Terraform.

## Acceptance criteria for a completed next-phase smoke

A new `gpt-6-sol` agent session **actually** runs through the deployed native ProjectManager/agent workflows, reads the correct guidance, writes a nonempty Scala submission, and produces either an explicit compile error or a frozen native artifact whose independent AutomationBench score is recorded. Native root/child identities and effects are captured, costs are measured, and **the 26 existing production GCS aliases are unchanged**. A scored outcome, even zero, is valid if the grader independently evaluated an agent-generated artifact; missing input or infrastructure error is not.

## Useful links

- [Workflows PR #25](https://github.com/awfl-us/workflows/pull/25) — benchmark code and native preflight.
- [Native execution developer loop](native-development-loop.md) — Scala/Rust compile and local Jobs guidance.
- [Native publication runbook](native-rust-publication.md) — release verification and GCS alias handling.
- [AutomationBench runner README](../files/scripts/evals/automationbench/README.md) — dataset, model, grader and simulator mechanics.
- [Native benchmark smoke source](../files/scripts/evals/automationbench/smoke_native_coordinator.py).
- [Compiler Rust roadmap](https://github.com/awfl-us/compiler-rust/blob/main/docs/current-state-and-roadmap.md).
