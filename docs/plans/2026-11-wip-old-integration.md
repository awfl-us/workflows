# AWFL `wip-old` integration — cross-repository plan

**2026-11-19 — plan only, no production change.** Authoritative integration plan in `awfl-us/workflows`. Full defect/test matrix: [2026-11-wip-old-workflows-bugs.md](./2026-11-wip-old-workflows-bugs.md). Do **not** merge the `wip-old` branches directly. All work targets latest `main` and **compiled native Rust workflows**, never Google Workflows execution.

## Review baseline (exact refs reviewed)

| Repo | `main` reviewed | `wip-old` | Reconciliation disposition |
|---|---|---|---|
| [dsl](https://github.com/awfl-us/dsl/tree/plan/wip-old-reconciliation/docs/plans) | `a00ddf6` | `38b3c11` | Prototype ADT matching; selectively keep optional-value fixes; reject risky implicit `Spec` fallbacks. |
| [workflow-utils](https://github.com/awfl-us/workflow-utils/tree/plan/wip-old-reconciliation/docs/plans) | `a7116e5` | `e73cafd` | Main target: in-memory `JsonSchema` + Kala-based `Ista` tool descriptions, native typed completion; refactor under existing contracts. |
| [compiler](https://github.com/awfl-us/compiler/tree/plan/wip-old-reconciliation/docs/plans) | `611c4a4` | `73cd076` | **No-op by default.** Its disabled API/schema writer is not needed. No schema-generation work required. |
| [workflows](https://github.com/awfl-us/workflows/tree/plan/wip-old-reconciliation/docs/plans) | `cacac48` | `f60dbab` | Highest-risk refactor: `EventHandler`→`Convo`, composable lifecycle traits, structured outputs; implement in slices with native E2E coverage. |
| [server](https://github.com/awfl-us/server/tree/plan/wip-old-reconciliation/docs/plans) | `59c3ede` | `bbbd5bf` | **Reject old GKE/RBAC/IAM** and model price/effort changes. Catalog API only if web needs. |
| [cli](https://github.com/awfl-us/cli/tree/plan/wip-old-reconciliation/docs/plans) | `856db29` | `4ba04b7` | **Reject large CLI reorganization / SSE supervisor**; optional opt-in local project without remote. |
| [web](https://github.com/awfl-us/web/tree/plan/wip-old-reconciliation/docs/plans) | `aa7b5d3` | `3923043` | Optional dynamic model picker and correctly reactive Stats project selection; test API format. |
| compiler-rust, compiler-yaml, .github | current default branches | none | **No historical branch**. Native Rust compiler/server tests may need narrowly scoped changes, not historical feature merges. |

Each of the seven repos with historical branches has `docs/plans/2026-11-wip-old-reconciliation.md` committed on its own `plan/wip-old-reconciliation` branch. Review those repository-specific dispositions before implementing anything.

## Goals and non-goals

Goals: (1) derive useful LLM function/tool schemas from typed Scala values, with contextual `Ista[T]` descriptions; (2) support typed, validated agent completions through the **current native Rust** workflow and callback system; (3) selectively extract reusable agent lifecycle and `Convo` logic while retaining current behavior; (4) optionally improve project onboarding and model UX only where value is clear.

Non-goals: restore compiler API/schema file generation; port historical GKE Terraform/RBAC; replace today's Postgres/native-effect-journal/session persistence; reintroduce Google WorkflowExecutions; reintroduce artificial `Tool calls completed` user messages; roll back context cache fixes; replace current CLI SSE supervisor or change model-pricing/effort without tests. Do not mix unrelated Scala-version changes, generated YAML noise, or mass style updates into feature patches.

## Phase 0 — inventory and baselines (blocker before edits)

- Create a small integration branch from the then-current `main` for each actual code slice; do not develop on `wip-old` or use these plan-only branches for code. Record current `dsl`, `workflow-utils`, `compiler-rust`, `workflows`, and `server` release versions/commits in a lockstep test matrix. Preserve current `EventHandler.Input.query: OptValue[String]` and null tool-completion continuation.
- Run **existing native Project Manager** and a tool-calling fixture: first turn, tool dispatch, no-query continuation, summarization, nested callback, status, budget, and final response. Record conversation messages, usage rows, costs, logs, and saved OpenAI prompt captures. Verify no side effects on a read-only fixture.
- Establish native Rust compile/publish smoke test for a tiny typed-workflow fixture; test direct server Jobs execution, not Google Workflows. Use controlled sandbox/project only. Track baseline success rate, tool counts, callback delivery, duration, and cached input ratio.

## Phase 1 — typed values and tool definitions (low-risk, independently deliverable)

1. Start with `workflow-utils.JsonSchema` (from wip) as an **in-memory schema service**; write schema/decoder correctness tests before wiring it into agents. Use present `dsl` and present model/tool dispatcher; no dependency on compiler's disabled schema-file path.
2. Add `Ista[T]` metadata/tool-definition construction incrementally and a single service-facing `ToolWithWorkflow` contract. Verify workflow routing identity is outside model-visible tool JSON. Maintain compatibility with present `Llm.ChatToolResponse`; don't force generic response changes into every workflow.
3. Independently validate `dsl` optional-value fixes; keep the Scala macro `Match` optional, behind semantic/Rust execution tests. A typed tool must not depend on successful macro integration.
4. Release/test library versions deliberately (dsl, workflow-utils); compile all dependent Scala workflows before advancing. Unchanged compiler and compiler-yaml are acceptable.

**Gate:** representative Scala types produce valid schema and round-trip arguments; tool dispatcher routes correct workflow; no new hidden `sys.error` for unrecognized sealed/nonproduct types.

## Phase 2 — typed agent completion (native Rust)

- Build a native `Sadhaka`-like adapter that reuses existing `execute`, callbacks, session/project identity, and effect journal. No hardcoded fund, unbounded recursive continuation, `googleapis.workflowexecutions` calls or assumptions that the first tool call is always the structured response.
- Constrain/validate a typed output tool schema; parse response arguments with explicit missing/multiple/malformed-tool-call errors. Keep API-level tool-choice semantics separate from the server-side tool-routing metadata.
- Test first-run native completion, async callback, retries/replay, cancellation, budget exhaustion, auth and project scope, structured parse failure, idempotency. Do not publish a library version until current Project Manager still passes.

**Gate:** successful typed completion through real server and native worker, with no duplicate committed effects or unexpected extra user message.

## Phase 3 — small, behavior-preserving workflow decomposition

- Introduce `helpers/Convo` as a private subworkflow first. Preserve old `EventHandler` behavior by comparing persisted messages and model request payloads before and after. Validate parent/child workflow invocation and native runtime call semantics; no new message when `query=None`.
- Introduce lifecycle `Status`, `Summaries`, `ExtractTopics`, and `ContextCollapser` traits **one at a time** with explicit mixin order and workflow step ordering tests. Existing post-response summarization may run without blocking response, but must never run twice, overwrite a newer summary, or change the prompt prefix between adjacent requests unexpectedly.
- **Do not copy `Parallel("followup", ...)` as-is**: processing tool calls and releasing the session lock have ordering/invariant requirements. Keep lock held through required commits/dispatch; exercise concurrent session arrivals.
- Defer `Small`, `SmallStrider`, and `SLEEP` until the base trait graph passes. Test whether added recurring background work merits cost and complexity; avoid hardcoding unverified `gpt-6-luna`.
- Do not regenerate or deploy Google Workflows `yaml_gens` or `functions/workflows/defs` as a migration step; build and publish native Rust binaries with current compiler-rust pipeline when ready.

**Gate:** native Project Manager parity under tools, callbacks, side calls, message ordering, budgets, status, recovery, and cache. See bug matrix.

## Phase 4 — optional independent improvements

- CLI: only consider opt-in `.awfl/config.json` local project creation with no Git remote; no automatic GitHub push, no old `main.py` extraction, no replacement SSE supervision.
- Server: only minimal model-catalog contract if web consumes it; do not import old GKE or historical prices. Respect intentionally deployed 17K Terra limit during cache testing.
- Web: dynamic models and cross-project Stats request scoping may be separate PRs, preferably after server contract tests; don't block phases 1–3.

## Release protocol / exit criteria

- One PR per small slice; release dependency versions before downstream. Each PR includes tests, migration note, rollback steps, and example native execution IDs/logs. Old `wip-old` generated artifacts are not acceptable proof of current native compatibility.
- Scala `sbt test`/compile; compiler-rust compile; native execution E2E with Postgres effect journal and callback replay; model-provider tool-choice contract test; authenticated end-to-end via Jobs/agent session; no unexpected file writes.
- Use the saved context replay/diff simulator and real GCS captures as a regression gate: baseline prompt order, optional query behavior, cached-prefix stability, 17K threshold and budget/cost. No unexplained extra `user` turn or mutable historical tool results.
- Stop / revert a slice on duplicate user messages, orphan tool calls, duplicate writes, cross-project leakage, incorrect fund/status, native replay mismatch, or unexplained cache-regression. Do **not** merge all old branches as a single change set.

## Dependency sequence

`dsl` optional primitives and/or validated Match (optional) → `workflow-utils` in-memory schema + typed tools → `workflows` native typed-completion fixture → `workflows` incremental Convo/lifecycle extraction. Current `compiler-rust`/server act as validation targets. CLI/web are independent. Old compiler API/schema generation is expressly **not a dependency**.

## Phase 0 status update — 2026-11-19

**In progress, with a functioning first local tier.** Implementation branch: `feat/reconciliation-test-harness` in workflows and server. See [local testing guide](../reconciliation-local-testing.md) for commands, mocks, limitations and evidence. The current ProjectManager native closure compiled to 26 ELF binaries without publishing; the deterministic `basic` and optional `no-query` foreground scenarios execute through the actual Rust runner with exactly one persisted user and assistant response (respectively one pre-existing user, and no injected new user). A Python CLI (`scripts/reconcile-test`) centralizes doctor/unit/compile/scenario/all.

**Remaining Phase 0 blockers:** strict background-drain mode reveals three failed detached native child executions (`unsupported subtraction operand types`) in the test fixture's SegKala summarization/topic chain; determine whether the input or fixture shape is wrong before labeling a production defect. Finish the deterministic tool-call/CLI callback/continuation roundtrip and isolated Postgres-backed Jobs lane. Do not start typed `wip-old` feature porting until background native parity is established. No code deployment intended.

**Follow-up Phase 0 result:** The semantic `READ_FILE` tool roundtrip now passes the real compiled ProjectManager native code path with mocked LLM/CLI callbacks: `user → assistant(tool_calls) → tool(tool_call_id) → assistant`, two model requests, no synthetic user turn, ~0.9 second root. Tool result is present verbatim in the second LLM prompt. The detached-chain background issue persists (6 failed nested jobs during this two-turn fixture). Continue holding the strict-background gate and add crash/recovery and isolated Postgres coverage before full compatibility claims.

## Phase 0 execution log — 2026-11-19 (local Scala-capable consumer)

- Development implementation on `feat/reconciliation-test-harness`: `scripts/reconcile-test` (`doctor`, `unit`, `compile`, `basic`, `no-query`, `tool-roundtrip`, `all`) and `docs/reconciliation-local-testing.md`. Server companion has deterministic `scripts/reconcileScenario.mjs`, callback/LLM/Jobs fixtures, native execution-store runner and Jest/Node tests. This setup does not call OpenAI or production databases.
- Verified current compiled closure: `native-dev.py codebase-ProjectManager --recursive --compiler /workspace/task-tool-compiler-rust --output /workspace/reconcile-native-output` succeeded; **26 statically linked native Rust workflows** verified; initial build 146.8 seconds, subsequent builds reuse Cargo artifacts. Dependencies use current Scala 3.4.2 and workflow-utils 0.3.6-SNAPSHOT in the Scala consumer.
- Verified `reconcile-test doctor` and `unit`, and ran live compiled binary fixtures using the exact build report. Foreground passes: `basic` (32 effects, 1 mocked LLM call, [user, assistant], 502 ms), `no-query` (30 effects, 1 mocked LLM call, [user, assistant], 488 ms), and `tool-roundtrip` (32 root effects, 2 mocked LLM calls, [user, assistant, tool, assistant], 907 ms). `tool-roundtrip` includes a native `READ_FILE` callback, preserves `tool_call_id`, and does **not** insert a synthetic new user turn. Timings are local illustrative values, not benchmark promises.
- **Outstanding blocker (not green):** detached background workflows fail in the mock environment: 3 native child failures after simple scenarios and 6 after tool roundtrip. Inspection of persisted native execution states identified `unsupported subtraction operand types` during background `workflow.run`. No unmocked HTTP requests were reported. Root-cause classification (fixture input type vs native runtime or workflow code) still requires tracing; **do not claim full background lifecycle parity**. Run `--strict-background` as an explicit red gate until resolved.
- The developer runner now discovers all Python `test_*.py` tests rather than silently omitting reconciliation CLI tests. All Phase 0 work remains on feature branches; do not deploy workflow code to production solely to satisfy this harness.

**Next Phase 0 work:** pinpoint the subtraction input in a detached summarization/ExtractTopics execution (inspect the materialized native input and generated Rust op), implement a typed/replay-safe fixture correction or runtime fix with a new regression, then make `--strict-background` pass. After that, add a local isolated Postgres/Jobs integration mode and crash/replay assertions before starting `wip-old` component integration.

### Phase 0 follow-up: strict background gate green with native runtime correction (2026-11-19)

- Traced the detached-workflow failure to **numeric subtraction** in `compiler-rust/runtime/src/operations.rs`: `time.now()` / `segmentEnd` uses Float seconds while `windowSeconds` is Integer. Native `subtract` handled only Integer–Integer or Float–Float. Added Float–Integer / Integer–Float promotion consistent with `ops::add`, preserving integer overflow handling; `cargo test -p awfl-native-runtime`: **27 passed**. This fix is isolated on the compiler-rust `fix/native-mixed-numeric-subtract` branch pending integration.
- Recompiled all **26 native Rust workflow binaries** against the patched runtime and verified static ELF artifacts; build report `/workspace/reconcile-native-output-fixed/build-report.json` in the Scala GKE dev consumer. The initial cold rebuild took about 262 s; warm iterations use Cargo artifacts.
- Corrected the deterministic LLM fixture to return actual schema-shaped JSON for background `context-ContextCollapser-SegKala`, `Summaries-Convo`, and `assistant-ExtractTopics-Convo` completions. Distinguish foreground `codebase-ProjectManager` requests from interleaved background model requests in test assertions; failing unknown schema or route remains explicit.
- **Strict gate now green:** `basic` (1 foreground + 5 background calls, 0 failed native children, user→assistant), `no-query` (1 + 5, 0 failed children, one existing user message; no synthetic new turn), and `tool-roundtrip` (2 + 10, 0 failed children, user→assistant→tool→assistant with matching tool call/result IDs). `--strict-background` tested all three on the actual compiled native ELF.
- Remaining Phase 0 scope: isolated PostgreSQL and local Jobs HTTP service integration, crash/restart replay tests, robust multi-turn fixtures, timeout/error cases, and a comparison against real GCS/OpenAI logs. **Do not mark those gates complete** or deploy the historical workflow refactor on this evidence alone.
