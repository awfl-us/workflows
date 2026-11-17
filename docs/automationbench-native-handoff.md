# AutomationBench native Rust — session handoff and execution plan

**Status verified November 16–17, 2026.** This is the authoritative task-specific handoff for [workflows PR #25](https://github.com/awfl-us/workflows/pull/25), branch `feat/automationbench-eval`. Read [AutomationBench runner README](../files/scripts/evals/automationbench/README.md), [native development loop](native-development-loop.md), and [native publication/runbook](native-rust-publication.md) alongside this document. The Scala DSL remains the authoring interface; **Rust is the intended evaluator/ProjectManager runtime. Do not deploy the generated Google Workflows YAML**.

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
**rebuild the 28 native executables** before the next smoke. This is not
a new source compilation failure.

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
- A later local host test was **not completed**. An independent `awfl-us/server` main checkout had no `package-lock.json`; `npm ci` failed immediately, then `npm install --no-save --package-lock=false --ignore-scripts --no-audit --no-fund` was interrupted when the GKE consumer ended (`execution_lost`). No local child-workflow integration result can be claimed from that attempt. The original compilation and IPC startup checks did pass before the consumer ended.
- Existing AWFL native ProjectManager has previously completed authenticated tool/final-response smoke tests, but this is **not** evidence the new benchmark coordinator works end to end. Native production routing is already enabled for published supported workflows; unpublished names may silently fall back to Google unless the execution backend is verified.

## Next session: ordered execution plan

1. **Inspect current repo and environment.** Attach to project `gNoQN9KUw5IbtsXLag0M` with Scala image. Check consumer health, files, git branch, active executions and GKE pod lifecycle before restarting expensive work. Preserve unrelated dirty files (`functions/workflows/types/services-Llm-ChatToolResponse.json`, deleted `infra/terraform.tfstate*`) — do not stage/reset them. Source branch is `feat/automationbench-eval`, PR #25. If the consumer is missing, bootstrap a new one and reuse clone/build caches where possible. Investigate recurrent GKE `execution_lost`/node failure via logs rather than asserting it is an npm problem.
2. **Confirm/restore repeatable compile and test.** From `/workspace/workflows` with sibling `/workspace/compiler-rust`:
   ```bash
   python3 scripts/native-dev.py evals-AutomationBenchEval --recursive \
     --output eval_runs/automationbench/native-coordinator-dev
   python3 files/scripts/evals/automationbench/smoke_native_coordinator.py \
     --report eval_runs/automationbench/native-coordinator-dev/build-report.json
   ```
   Expect 28 verified binaries and a first `native.parallel` suspension. Keep the `--output` and `--report` paths identical. No `--publish` for this step.
3. **Test actual child/fork execution locally.** Use `awfl-us/server` `scripts/nativeLocalRun.mjs` and the 28-binary build report as a local release registry, or the equivalent isolated native executor. Use an in-memory store and bounded replay/loopback Jobs effects. If preparing a new server checkout, note `npm ci` needs a lockfile (the repo lacked one); avoid relying on a nonexistent lockfile. Only treat a call as live if it reached the actual local Jobs service; replay fixtures are not live. Follow [server native local-dev guide](https://github.com/awfl-us/server/blob/main/docs/native-local-development.md). Verify that the evaluator's `ParallelFor`, child `workflow.run`, and cleanup route correctly. Exercise an intentionally absent submission to assert the missing-file guard, not a grader parse failure.
4. **Isolate release publication and backend routing.** The production GCS alias set is shared. Prefer a local native release registry or a **dedicated, explicitly isolated release namespace/bucket and authorized canary** with IAM/Terraform ownership where needed. Alternatively, publish only the two new benchmark aliases *after* verifying compatibility with the already-published 26 dependencies and proving no unrelated production aliases change. Verify native selection by observing an execution ID starting `native_` and native runtime logs; merely requesting the benchmark name is insufficient because the launcher can fall back to Google if an artifact is missing.
5. **Validate the requested model and one real task.** Run **one** operations/1219 agent-building attempt with `gpt-6-sol`, the current $1 cap, clean task-local Scala consumer, three repos, both preloads, and explicit output path. Check the first model invocation/provider acceptance and early tool steps before letting it run on. Inspect why it writes/does not write the first draft; do not increase the fund without evidence. Keep builder source/credentials separate from the grader.
6. **Compile, execute, score and account.** Freeze the **actual agent-generated** `AgentSubmission.scala` (not a read error), compile it via `grade_submission.py` → `build_native.sh` → `native-dev.py --class`; independently run the frozen binary against reset AutomationBench state and capture official strict/partial assertions, tool calls, run ID, builder model, token/API spend, wall time and build/runtime timing. Explicitly label build failures and partial completion; no substitution of the known handcrafted 8/8 workflow.
7. **Close only after genuine evidence.** Add regressions for missing-source handling, Rust root + child/ParallelFor preflight, provider-error path and cleanup. Confirm transient consumer/job cleanup, no accidental production alias mutations, and repeat the one-row run. Then move to additional public rows, independent trials and hidden-scenario isolation.

## Risk and scope boundaries

- **Do not deploy YAML or broad-publish the 28-binary closure.** The entire point is to test the native backend without touching the existing 26 production workflow aliases.
- Scala object initialization during native compilation is executable code. The current grader compiles agent-created files on a credential-bearing host: fine only for trusted development output; **isolate grading credentials and filesystem before adversarial/hidden evaluations**.
- A successful Rust binary build or startup response is not a successful child execution, agent run, or official benchmark score.
- GKE namespace limits in `awfl-us/server` Terraform were raised separately to 100 pods, 100 jobs, 100 vCPU, 256Gi memory and 512Gi ephemeral storage. These quotas are ceilings, not resource reservations; they do not prevent consumer node failure.
- Keep native compiler work in `awfl-us/compiler-rust`, Jobs/native executor fixes in `awfl-us/server`, and benchmark definitions/harness/docs in `awfl-us/workflows`. Any infrastructure/IAM changes must go through Terraform.

## Acceptance criteria for a completed next-phase smoke

A new `gpt-6-sol` agent session **actually** runs through the native executor, reads the correct guidance, writes a nonempty Scala submission, and produces either an explicit compile error or a frozen native artifact whose independent AutomationBench score is recorded. Native root/child identities and effects are captured, costs are measured, and **the 26 existing production GCS aliases are unchanged**. A scored outcome, even zero, is valid if the grader independently evaluated an agent-generated artifact; missing input or infrastructure error is not.

## Useful links

- [Workflows PR #25](https://github.com/awfl-us/workflows/pull/25) — benchmark code and native preflight.
- [Native execution developer loop](native-development-loop.md) — Scala/Rust compile and local Jobs guidance.
- [Native publication runbook](native-rust-publication.md) — release verification and GCS alias handling.
- [AutomationBench runner README](../files/scripts/evals/automationbench/README.md) — dataset, model, grader and simulator mechanics.
- [Native benchmark smoke source](../files/scripts/evals/automationbench/smoke_native_coordinator.py).
- [Compiler Rust roadmap](https://github.com/awfl-us/compiler-rust/blob/main/docs/current-state-and-roadmap.md).
