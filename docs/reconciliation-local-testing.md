# `wip-old` reconciliation: local-first test loop

**State (2026-11-19): Phase 0 harness implemented on `feat/reconciliation-test-harness`, not deployed.** This is a development convenience on top of `scripts/native-dev.py` and server `scripts/nativeLocalDev.mjs`, not a new runtime. It neither calls OpenAI nor connects to production storage; all model, Jobs HTTP, callback, GCS, and message data for deterministic scenarios are mocked. Rust binaries are **real** generated binaries, execution uses the existing native durable store with its in-memory adapter.

## Workspace

Use the existing **Scala-capable AWFL GKE development consumer** or a developer machine with Java/sbt, Cargo + musl target, Python, and Node. Check out `dsl`, `workflow-utils`, `workflows`, `compiler-rust`, and `server` as siblings (or use `--server` and `--compiler`). The test wrapper is in the workflows checkout. Use local `sbt publishLocal` for changed libraries; do not release them or patch production. `build.sbt` dependency coordinates must be verified before a cross-repo test; test-only overrides must stay uncommitted or on an isolated feature branch.

```
./scripts/reconcile-test doctor
./scripts/reconcile-test unit
./scripts/reconcile-test compile
./scripts/reconcile-test basic
./scripts/reconcile-test no-query
./scripts/reconcile-test tool-roundtrip
./scripts/reconcile-test all
```

In a non-sibling workspace, append `--server /path/to/server --compiler /path/to/compiler-rust --report /path/to/build-report.json`. `compile --check` only verifies generated Rust closure/manifests. A full `compile` regenerates/builds the 26-workflow ProjectManager closure with warm sbt/Cargo caching. The default report lives under `.awfl-reconcile/`, which **must remain untracked**. No `--publish` is ever passed.

`unit` runs the existing Python native-development tests and server Node native-local tests together with the new isolated scenario-handler tests. `basic`/`no-query`/`tool-roundtrip` invoke real compiled native Project Manager via `server/scripts/reconcileScenario.mjs`. There are no hidden cloud calls or billable LLM requests. The deterministic mock records documents, tool/LLM requests, and message roles; unrecognized Jobs HTTP endpoints fail closed. Existing replay identity and origin checks still apply.

### Test scenarios and expected outputs

- `basic`: fresh query, one mocked assistant response, exactly one new user message and one assistant message; zero missing HTTP fixtures. Verified with 32 native root effects and one fake LLM call.
- `no-query`: existing user history but no new user input (tool-follow-up shape); must **not insert a synthetic user message**. Verified with 30 root effects and one fake LLM call.
- `tool-roundtrip`: the mocked LLM requests `READ_FILE` and receives its result through the native CLI callback path. A detached workflow continues without adding a synthetic user message; the second LLM request contains exactly the matching assistant `tool_calls` and `tool_call_id` result in order. Verified **four persisted messages** (user, assistant tool call, tool result, assistant final) and exactly two LLM requests, all with fake tools/LLM.
- `--strict-background` (with any scenario): additionally waits for all detached child native executions to settle and fails if any ends in `failed`. **Currently red**: 3 detached background executions fail for single-turn runs and 6 for the tool-roundtrip (two turns) with `unsupported subtraction operand types` during the simulated SegKala summarization/topic chain. The root passes. This is a specific reproducible testing gap / possible workflow bug; do not declare full agent parity until it is resolved and proven with accurate fixtures. Without strict flag, the output includes `failedBackgroundExecutions` and tests only foreground parity.

Both deterministic scenarios use `drainDetached` before inspecting local execution state. To investigate native failures, set `AWFL_NATIVE_LOCAL_TRACE=1` on the test command, or run `node server/scripts/reconcileScenario.mjs --report ... --scenario basic` directly. Do not suppress detached failures in integration acceptance gates.

## What is genuinely mocked (vs. not)

| Component | Deterministic smoke mode |
|---|---|
| Scala workflow and compiler-rust output | Actual compiled source/binaries |
| Native worker, fork/join, execution journal | Actual native runtime + in-memory durable store |
| `/firebase/*`, `/context/topicContextYoj/run`, agent and tool endpoints | **Stateful fixture responder**, not full real Jobs Express/Postgres |
| `/llm/chat` | **Mock** deterministic response; no usage billing |
| Callback create/await and allowed `scripts/agent.sh` GCS read | **Mock** with restricted object path |
| Detached native children | Real native scheduler and binaries; strict mode currently reports failure |
| GCP IAM, actual Postgres transactions, real model, network routing | **Not tested** by this mode |

Do not infer Postgres correctness, real Jobs API shape, callback delivery, LLM schema acceptance, or production cache behavior from these fixtures. These require separate integration lanes.

## Next steps / gates

1. **Fix or accurately fixture the background SegKala chain:** inspect the `StriderInput.segmentEnd`, `Segments.forSession`, `TopicContextYoj` values and native child inputs, then make `--strict-background` green. Keep the regression fixture once fixed.
2. **Completed basic READ_FILE roundtrip**, including exact tool IDs, role order, callback and no-query continuation. Extend it with malformed tool calls, missing callback delivery, budget exhaustion, duplicate results and native crash/replay assertions.
3. Add local isolated **Postgres-backed Jobs integration** on port 5055 with test-specific credentials/storage, not production `awfl_prod`. This is the next fidelity tier; do not mistake it for the fixture mode. Retain a separate mock LLM adapter to make it deterministic.
4. Once the native fixture + local Jobs tiers pass, canary one opted-in native execution against an isolated project and real provider, inspecting Postgres persisted messages, usage/cost, and GCS cache captures. No broad Cloud Run deployment for each edit.
5. Gate every `wip-old` implementation slice on existing main behavior, unit schema tests, native fixture parity, strict detached success, and eventual isolated Postgres/live canary. The existing `docs/plans/2026-11-wip-old-workflows-bugs.md` is the tracking matrix.

### Initial verified developer run

On 2026-11-19 a full native rebuild of current main completed with **26 static musl binaries** and `publish=false` (146.8 seconds cold); `basic` and `no-query` finished in approximately 0.4–0.5 seconds each; `tool-roundtrip` finished in approximately 0.9 seconds in the warm local native runner. Foreground/root assertions pass; strict-background reports 3 failed detached children in each single-turn case and 6 in the two-turn tool-roundtrip. Do **not** count them as fully passing workflow integration until fixed.

## Strict background baseline (patched runtime)

The full background fixture requires compiler-rust with mixed Float/Integer
subtraction (`fix/native-mixed-numeric-subtract` until merged). The prior
compiler produces `unsupported subtraction operand types` when the native
summary workflow subtracts integer window seconds from floating-point epoch
seconds. Build your native closure against that checkout, then run:

```bash
./scripts/reconcile-test basic --strict-background --server ../server --compiler ../compiler-rust
./scripts/reconcile-test no-query --strict-background --server ../server --compiler ../compiler-rust
./scripts/reconcile-test tool-roundtrip --strict-background --server ../server --compiler ../compiler-rust
```

All three pass on the patched runtime with schema-shaped mocked JSON for
background summarization, topic extraction and collapse. Foreground calls are
counted separately from interleaved background model calls. This uses the
**real native Rust binaries and in-memory storage**, not production services.
Postgres, real local Jobs and crash/restart testing remain separate gates.
