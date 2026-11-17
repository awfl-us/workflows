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
