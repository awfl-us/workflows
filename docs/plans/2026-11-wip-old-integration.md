# AWFL `wip-old` integration — cross-repository plan

**Current: iterative implementation and production releases are underway (Nov 20, 2026 UTC).** The entries below retain earlier plan-only and pre-release checkpoints as historical evidence. This is the authoritative integration plan in `awfl-us/workflows`; full defect/test matrix: [2026-11-wip-old-workflows-bugs.md](./2026-11-wip-old-workflows-bugs.md). Do **not** merge the `wip-old` branches directly. All work targets latest `main` and **compiled native Rust workflows**, never Google Workflows execution.


**Active reconciliation checkpoint — Phase 3.23 (2026-11-20):** the original `Summaries` / `ExtractTopics` / `ContextCollapser` agent lifecycle mixins are restored in an **unmerged feature working tree**; Scala compilation and the single-guard/three-step DSL smoke pass. Native ProjectManager execution parity, callback/side-call and isolated PostgreSQL regression gates are **not yet complete**. See [Phase 3.23](#phase-323--background-lifecycle-mixins-recovered-pre-merge-checkpoint-2026-11-20) and [the local-testing guide](../reconciliation-local-testing.md#background-lifecycle-composition-parity-pre-merge-feature). The preceding DSL `0.3.6` and workflow-utils `0.3.7` dependency rollup is released and merged.

> **ACTIVE EXECUTION POLICY — MEMORY MODE (2026-11-19)**
> **Native Rust memory mode is the supported, latency-optimized path for current AWFL work.** All `wip-old` reconciliation, Project Manager testing, typed tools, callbacks, and PostgreSQL checks must target **memory mode**. **Durable mode and migration of its Firestore-specific per-effect journal to PostgreSQL are deferred / on the back burner**, **not a release gate or blocker** for this project. Preserve existing durable behavior as legacy compatibility only; do not spend implementation time porting or expanding durable mode unless explicitly reprioritized. Test PostgreSQL memory-mode leases, callback idempotency, stable effect keys, worker restart/replay, and avoid claiming per-effect exactly-once semantics that memory mode does not provide.

## Review baseline (exact refs reviewed)

| Repo | `main` reviewed | `wip-old` | Reconciliation disposition |
|---|---|---|---|
| [dsl](https://github.com/awfl-us/dsl/tree/plan/wip-old-reconciliation/docs/plans) | `a00ddf6` | `38b3c11` | Prototype ADT matching; preserve the deliberate deferred-error non-product `Spec` fallback (validated on `feat/reconcile-nonproduct-spec`), and selectively restore optional-value fixes. Do not copy the redundant old builder. |
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

Non-goals: restore compiler API/schema file generation; port historical GKE Terraform/RBAC; replace today's PostgreSQL memory-mode/session persistence; reintroduce Google WorkflowExecutions; reintroduce artificial `Tool calls completed` user messages; roll back context cache fixes; replace current CLI SSE supervisor or change model-pricing/effort without tests. Do not mix unrelated Scala-version changes, generated YAML noise, or mass style updates into feature patches.

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

- Build a native `Sadhaka`-like adapter that reuses existing `execute`, callbacks, session/project identity, and memory-mode execution semantics. No hardcoded fund, unbounded recursive continuation, `googleapis.workflowexecutions` calls or assumptions that the first tool call is always the structured response.
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

### Phase 0 PostgreSQL and recovery gates — 2026-11-19

**Implemented on server/workflows test branches, no production changes.** Established a local, loopback-only PostgreSQL 17.12 instance, database `awfl_reconcile` in the Scala development consumer (without any production Cloud SQL credentials). A portable optional Docker Compose file is included in server `scripts/compose.reconcile-postgres.yml`. Developer commands: `./scripts/reconcile-test postgres` (requires `PGTEST_URL`, native report), `./scripts/reconcile-test durable` (requires compiled native report), and `all` (runs Postgres mode when `PGTEST_URL` exists). `all --strict-background` also runs the three fully detached Project Manager fixtures.

- **Real PostgreSQL adapter suite: 11/11 pass.** Covers independent event cursors, subscriptions, document CRUD, native memory-state claim fencing, concurrent workers, stale epochs, terminal replay and indexing.
- **Native Rust + Postgres + localhost HTTP: 3/3 pass** with compiled `helpers-ToolDefs`: (1) completed-result replay through new SQL pool without extra HTTP; (2) child process takes lease and is killed with **SIGKILL**, recovered worker reclaims, persists result and fences prior epoch; (3) child is killed after external HTTP returned but before SQL completion, workflow restarts and **repeats** HTTP with identical idempotency key, and test endpoint deduplicates to one logical result. Combined `postgres` command: **14/14 pass** in ~6 seconds, excluding native build time.
- **Durable native effect-journal regression: 22 passed / 1 skipped.** This is an in-memory Firestore-compatible store using an actual compiled `helpers-ToolDefs` ELF. **It is not PostgreSQL-backed**; memory-mode Postgres intentionally has no implicit per-effect journal, so retry-after-effect requires endpoint idempotency (proven by the third case). The single skipped test needs an unrelated optional binary fixture.
- **Network boundary:** localhost ephemeral HTTP server, actual `createLocalJobsHttpHandlers` URL/identity/allowlist adapter. It is **not** the complete Jobs Express app nor production IAM. The PG mode refuses external DB hosts or databases not named `/awfl_reconcile`. No OpenAI, production DB, Cloud Tasks, or GKE Jobs calls occurred in these test cases.

**Still outstanding before declaring full Phase 0:** Launch the real Jobs Express app with isolated Postgres-backed document Storage (not just a localhost fixture responder), verify multi-turn Project Manager/session concurrency against it; test native durable journal recovery after actual process crash with externally persisted journal (not only in-memory regression). Keep those distinct from the demonstrated Postgres memory-mode recovery. Then resume staged `wip-old` component integration. Do not merge old workflows automatically.

### Final combined Phase 0 local verification

`PGTEST_URL=postgres://awfl@127.0.0.1:55439/awfl_reconcile ./scripts/reconcile-test all --strict-background --server /workspace/reconcile-server --compiler /workspace/reconcile-compiler-rust --report /workspace/reconcile-native-output-fixed/build-report.json` completed with **exit code 0**. Verified **26 ELF workflows** (warm compilation 7.6 s), 17 Python unit tests, 10 passing Node fixture/unit tests with 1 optional skip, 22 passing durable journal tests (1 optional skip), 14 passing PostgreSQL tests (no skips), and three strict background Project Manager scenarios (0 failed detached executions). The entrypoint now verifies the PostgreSQL URL is the loopback-only `awfl_reconcile` database **before running any SQL tests**.

### Scoped production Jobs route test added

The fourth native/Postgres integration case mounts the actual `jobs/firebaseDbApi.js` Express router with production `workflowsUserInject` and `projectIdMiddleware`, injecting isolated `PostgresStorage`. It verifies scoped create/read, duplicate-document 409, and two concurrent `locks/acquire` requests with **exactly one winner**, followed by lock release. **Combined PostgreSQL suite: 15/15 pass**, now including this production-router integration. This is still not a launch of the whole Jobs service (which initializes cloud-adjacent modules and a recovery scheduler). The remaining full app + multi-turn ProjectManager test stays open.

## Phase 1.1 implementation log — 2026-11-19

**First selective `wip-old` implementation slice** created on `awfl-us/workflow-utils` branch `feat/reconcile-typed-json-schema`, based on current `main` (`a7116e5`). The standalone `utils.JsonSchema` derives in-memory schemas for primitives, case classes, sealed sums, AWFL `Value`/`ListValue`/`OptBase`/`OptList`, Option, maps and collections. It is independent of `Ista`/`Kala`, historical file generation and Google Workflows. Unknown types fail **at compile time** (rather than historical `sys.error`). Tests pass under Scala 3.3.1, including negative compile-time rejection. No existing agent workflow, LLM contract or dependency version was altered. The accompanying workflow-utils plan lists the exact API, tests and limitations; contextual `Ista` metadata and tool-response decoding remain subsequent slices. Re-run native ProjectManager fixture gates before wiring derived schemas into agent prompts.

### Phase 1.2 integration checkpoint — 2026-11-19

`awfl-us/workflow-utils` branch `feat/reconcile-typed-json-schema` now extends existing `Ista[T]` with an optional contextual description (existing construction remains compatible) and introduces `JsonSchema.ModelFunction`, `JsonSchema.functionForIsta`, and `JsonSchema.RoutedTool`. The latter keeps workflow routing metadata **out of the model-visible schema**. The original historical `JsonSchema.toToolDef` drops `oneOf`/nullable/map constraints; the current `Llm.ToolDefProperty` also cannot encode `additionalProperties=false`. Accordingly the legacy converter now rejects unrepresentable schemas, while the in-memory model-facing schema preserves the constraints. Scala tests pass. Independent current native Project Manager baseline revalidated, 26 ELF workflows / 15 SQL tests / 22 durable journal passes (one skipped) / 3 strict scenarios. Still **not** integrated into the LLM Jobs endpoint or Project Manager, and no real model contract tests have been performed. Next Phase 1.3: stable serialization, typed argument validation, Jobs tool schema compatibility and native tool call round-trip.

### Phase 1.3 checkpoint — 2026-11-19

`workflow-utils` now has deterministic `JsonSchemaCodec`, Scala `JsonSchema.nativeTool` using `json.decode` for full JSON Schema in the existing `Llm.Tool` wire shape, plus a fixture generator for contract tests. `server` has an opt-in `validate_tool_arguments=true` Jobs LLM response check with strict object/union/map/array validation and 422 error on bad tool-call arguments **after usage accounting**, while default calls stay unchanged. The real Scala-generated schema passes unchanged through `openai.buildChatPayload`; cross-repo test suite 9/9 Node tests and Scala tests pass. No provider call was made.

The full existing native Project Manager baseline is still green (26 workflow closure; 3 strict background scenarios; 15/15 Postgres; 22 durable-journal passes with 1 optional skip). **This still does not prove the new nativeTool's CEL `json.decode` execution, typed dispatch under a real provider, or 422 propagation through a live Jobs app.** Those are the next native acceptance gates before beginning the historical `Convo`/`EventHandler` refactor. Continue to reject old compiler schema-file generation and outdated GKE/CLI changes.

### Phase 1.4 — native `Ista.tool(kala)` milestone (2026-11-19)

Revisited the `wip-old` use-site design and implemented `Ista[T].tool(kala)` without `json.decode`: a compile-time typed `JsonSchema.Schema` is lowered into the existing DSL tool envelope as structured map data. To preserve backwards compatibility, `Llm.ToolFunctionDef.parameters` accepts `BaseValue[?]`; tighter Scala union types fail current DSL Spec derivation, so the weak typing is confined to the JSON transport boundary rather than schema construction. The existing old `ToolDefProperty` call sites still compile. **Real compiled native Rust** executed a new read-only `helpers-TypedIstaSchemaFixture` and returned exactly the expected strict schema (nullable union, typed map, array items, required fields, Kala description), persisted in isolated PostgreSQL. OpenAI payload-builder preservation and returned-argument validation also passed. The workflows `scripts/reconcile-test typed-ista` command recompiles and runs this dedicated fixture. No new compiler or DSL feature was necessary.

Current Project Manager (all existing 52 Scala workflow sources, 26 native artifacts) continues to pass the full deterministic/strict-background/Postgres/durable baseline (3 + 15 + 22 passes, one optional durable skip); 19 runner Python tests pass. **Open:** full Jobs LLM endpoint, typed routing/callback and crash-recovery E2E; do not overstate fixture success as a live tool execution or provider API validation. Code remains on review branches, no deployment.

### Phase 1.5 — native schema through Jobs LLM HTTP (2026-11-19)

**Completed, isolated test lane.** The fresh Scala `workflow-utils_3:0.3.6-SNAPSHOT`
was republished **only to local sbt/Ivy**, then all 53 workflows Scala sources
compiled. `helpers-TypedIstaSchemaFixture` built as one real static Rust ELF
(`publish=false`), ran with PostgreSQL test-only `awfl_reconcile` and
replayed the completed native result. The resulting `Ista.tool(kala)` JSON
was passed *unchanged* through the actual `jobs/llm.js` Express route, with
an injected fake OpenAI adapter and usage recorder. Valid call -> HTTP 200;
invalid nested map value -> HTTP 422 after usage accounting. No real provider
or production credentials used.

The server implementation/test review slice is on
`awfl-us/server:feat/reconcile-typed-llm-http`, commit `bd8c2c9`, based on
the current remote `fix/reconcile-background-fixtures` branch (not a forced
update to the divergent historical branch). HTTP/provider/schema/native tests
passed **12/12** on that review branch; the wider LLM suite passed **13/13**.
Explicit tool call ID validation now rejects missing and duplicated IDs for
opted-in typed callers. Existing unvalidated flows are unchanged.

**Next phase (do not mark complete):** wire typed `Ista` tool dispatch into
existing native agent callback routing, confirm workflow identity stays outside
model-visible tool JSON, reject nonexistent/duplicate callback results, and
replay a callback continuation without duplicating messages/effects. Add
concurrent-session lock/order and crash-after-dispatch recovery cases.
Only after those gates pass, consider selective `Convo` refactoring and
provider canary. Production Jobs end-to-end, real provider API acceptance
and Postgres-backed durable effect journaling remain unproven.

**Environment note:** The Scala GKE consumer was restarted with refreshed
credentials. Source and Git refs restored from GCS, but local node_modules,
sbt/Cargo outputs and some PostgreSQL empty directories did not. A fresh
loopback-only PostgreSQL instance was initialized for this suite. Do not
treat a recovered old build-report.json as proof that its referenced ELF
survived workspace restore.

### Phase 1.6 — typed Ista routing adapter (2026-11-19)

Added `ToolDefs.ToolWithWorkflow.fromIsta[T](ista, kala, workflowName)` on
`feat/reconcile-ista-routing-bridge`, reusing the existing native
`ToolDispatcher` contract instead of introducing another dispatcher.
The model-facing `ista.tool(kala).function` is retained unchanged, and
`workflowName` is service-side metadata only. The read-only
`helpers-TypedIstaSchemaFixture` returns both forms so the server's
native/Postgres + Jobs-HTTP contract test checks strict schema equivalence,
correct workflow identity, and absence of route metadata in the model payload.
The Scala build and static native Rust fixture passed; isolated native
contract test passed 1/1.

**Not yet verified:** executing a typed callback through `ToolDispatcher`,
callback result idempotency, missing/duplicate dispatch behavior, concurrent
session ordering, process-crash replay, or a real LLM provider. Keep all of
those as explicit acceptance gates. No production deployment.

### Phase 1.7 — typed tool dispatch and callback continuation (2026-11-19)

**Local native gate passed.** The read-only `helpers-TypedIstaSchemaFixture`
now derives a strict `READ_FILE(filepath: String)` function from `Ista` and
pairs it with `tools-CliTools` using the existing `ToolWithWorkflow`
adapter. The strict schema is model-facing; `workflowName` remains outside
the LLM tool payload. The isolated Jobs/native integration fixture passes
that *compiled Scala-generated* definition through Project Manager's actual
native Rust workflow closure: service tool retrieval, `EventHandler`,
`helpers-ToolDispatcher`, `tools-CliTools`, callback event/await, persisted
assistant/tool/assistant messages, and an ordered follow-up LLM request.
Assertions confirm a single callback event for the typed tool call ID,
exactly one matching result in the follow-up context, and no duplicate
function names in the model's tool list.

**Issue discovered and fixed:** Project Manager concatenated service-defined
tools with built-in CLI/task definitions, advertising duplicate `READ_FILE`
functions when a typed service definition overrode a built-in tool. The
final `Tools` trait's `ToolsResult` now retains the first definition by
function name. Service tools take precedence, while unrelated `RUN_COMMAND`
and `CHECK_TASKS` remain available. The 26-workflow static Rust closure
recompiled and passed the typed native test. The combined typed schema,
Jobs HTTP, OpenAI-adapter and native-dispatch tests passed **12/12**.
The three strict-background Project Manager baseline scenarios all passed:
`basic` (32 effects), `no-query` (30), legacy `tool-roundtrip` (31);
no failed background executions.

**Repeatable gate:** `scripts/reconcile-test typed-dispatch` compiles the
typed fixture and Project Manager native closure and executes the typed
native/Jobs contract plus all three strict legacy scenarios, requiring
loopback `PGTEST_URL` scoped to `/awfl_reconcile`. Use a normal Git checkout:
the existing sbt-git plugin does not understand linked worktree `.git` files.
No public package publication or production deployment was performed.

**Next unresolved acceptance work:** real Jobs callback persistence and
idempotency under duplicate delivery, concurrent session locks, durable
PostgreSQL effect journaling across process crash/recovery, HTTP 422
propagation from a real native EventHandler request (not just its direct
Jobs route test), and a controlled provider API canary. The current callback
and model are intentionally mocked; the Project Manager's native durable
fixture uses in-memory Firestore records, while the typed-Ista artifact
uses a loopback-only Postgres execution store. Do not claim production
callback exactly-once execution from this gate.

### Phase 1.7 extended strict-background gate — 2026-11-19

Rebuilding the full closure exposed an older numeric boundary failure in
`KalaVibhaga.week` / `.term`: these operated `//` on fractional Unix seconds
(and on a fractional quarter duration). The fix in `workflow-utils` uses
integer seconds for weeks and scaled integer microseconds for fractional
quarters, preserving the intended positive-epoch bucket calculation. That
unblocked additional weekly/quarterly native summary lanes and exposed a
second Rust runtime bug: `ops::multiply` did not promote mixed integer and
floating-point operands as the existing add/subtract operations do. A
compiler-rust review branch adds that promotion with a dedicated unit test.

The isolated Jobs fixture now mocks only the newly reachable named
weekly/quarterly summary and indexer endpoints. Unknown endpoints still fail.
The strict-background test counts are allowed to grow above the earlier
minimum because exercising additional period lanes is desirable; each lane
must still complete without error. The `typed-dispatch` command passed fully
in a normal checkout after rebuilding the 26 static Rust ELF artifacts:
- Typed schema + native Project Manager tool dispatch and callback: **1/1 pass**
- `basic`: 32 effects, 1 foreground / 9 background model calls, 0 failed lanes
- `no-query`: 30 effects, 1 foreground / 9 background model calls, 0 failed lanes
- `tool-roundtrip`: 31 effects, 2 foreground / 16 background model calls, 0 failed lanes
- Runtime mixed multiply regression unit: **1/1 pass**

These are mock-backed local fixture results, not a claim of production
callback durability, a live provider response, or real GCP Jobs success.
The next acceptance tasks remain real Jobs callback idempotency/restart,
concurrent session isolation, and PostgreSQL memory-mode crash/restart replay.

### Phase 1.8 — real Jobs callback/PG HTTP gate (2026-11-19)

The `awfl-us/server` reconciliation branch `feat/reconcile-typed-llm-http`
now includes `test/storage/nativeCallbackRoutes.integration.test.mjs`,
which boots the **existing Jobs Express callback registration and delivery
routes** against a PostgreSQL document store in an isolated loopback
`/awfl_reconcile` database. The integration test exercised twelve
concurrent deliveries: exactly one transaction recorded the result, identical
retries remained idempotent, conflicting retries returned 409, and wrong
project/user scopes were rejected. A subscribed native callback observed
delivery from an independent PG connection, and a newly created callback
client/database pool read the committed result after simulated process
replacement. The generated native `tools-CliTools` callback integration
test also passed with its actual compiled Rust binary.

**Actual bug discovered and fixed:** A native callback ID absent from the
Postgres document store triggered the generic legacy MCP Firestore fallback
and could turn an ordinary 404 into a Firestore permission error / 500.
`workflows/callbacks/index.js` now skips that legacy lookup for
`n<32 hex>` native IDs. The HTTP callback test passes and the combined
callback/typed route regressions passed 13/14 (one separately run optional
native-binary test was skipped in the combined invocation; it subsequently
passed explicitly). Server commit: `bc7ed5e`.

**Crash/lease checks run:** The existing isolated Postgres/native Rust
memory-mode regression suite passed 4/4, including real SIGKILL of a leased
worker, lease-epoch fencing, replay of completed results and reuse of the
effect idempotency key after an HTTP-response crash. Those tests **do not**
prove durable *per-effect journal* resume: `NativeExecutionStore` still
uses Firestore-style transactional references, and `NativeMemoryExecutionPilotStore`
intentionally has no per-effect journal. We must not count that separate
durability gate as complete.

**Next priority:** verify session-level locking and ordering with concurrent
typed tool callbacks, including delayed/failing results. Then choose and
test the intended native durability model for a crash between dispatch,
remote effect commitment, and persistence: either a real provider-neutral
Postgres-backed journal or documented reconciliation using stable effect
keys. Finally exercise invalid typed argument 422 propagation through a
real native EventHandler and controlled model canary. This is not a
production deployment or a merge to the default branches.

### Execution-priority correction (2026-11-19)

**Decision: memory mode only for active development, for latency.** Prior checkpoints mentioning a missing Postgres durable effect journal or a 'durable journal crash gate' describe historical technical gaps, **not current blockers**. Do not port `NativeExecutionStore` per-effect Firestore journaling to PostgreSQL as part of this reconciliation. Concentrate on memory-mode callback/session correctness, stable effect idempotency, Postgres lease fencing and process-restart behavior, and typed tool errors. Preserve the legacy durable mode without expanding it. Revisit only on explicit direction.

### Phase 1.9 — contended native session correction (2026-11-19)

**Real PostgreSQL memory-mode Project Manager failure reproduced and fixed.**
Two independent compiled native roots sharing a session, while the first
LLM response was held, previously both reported `completed`, even though the
persisted conversation contained `user, user, assistant` and the model ran
only once. `EventHandler` had persisted the losing user turn **before** trying
the session lock; `Locks.acquireBool` mapped HTTP 409 to false and the busy
branch returned a successful empty result. This was a silent unanswered turn.

The updated `EventHandler` stores task/user messages only **after** the
session lock is acquired, and a busy execution raises an explicit 409 with
`Session is busy; retry after the active turn completes`. The error handler
updates Failed status, invokes failure callback when present, then re-raises
the original error so the native root becomes terminal `failed`, not an
apparently completed job. No implicit queue or automatic retry was added.

The same two-root native test now verifies: first request completes, second
fails with code 409 and saves no user turn, and **a new execution retry after
the lock is released succeeds**; final roles are
`user, assistant, user, assistant` and exactly two foreground model calls.
The test uses the real Jobs lock Express routes and isolated PostgreSQL
Storage, the actual Project Manager Rust binary, and memory-mode native
execution roots (not the legacy durable harness). `server` test file:
`scripts/reconcileMemoryConcurrency.test.mjs`. The `typed-dispatch` runner
now includes this check. Current native closure recompiles all **26/26**
binaries, the strict `basic`, `no-query`, `tool-roundtrip` scenarios pass,
and the compiled typed-Ista/native-dispatch regression passes 1/1.

Still to verify: end-to-end delayed/failing **tool callback** contention,
actual Jobs LLM 422 propagation through EventHandler under memory mode, and
UI/Jobs consumption of the retriable 409 status. The caller must explicitly
resubmit rejected turns; do not claim transparent queuing or exactly-once
across a long-expired lease. Durable mode remains deferred.

### Phase 1.10 — delayed callbacks, typed validation, and public retry boundary (2026-11-19)

**Verified against the rebuilt 26/26 native closure and isolated PostgreSQL memory mode:**

- Delayed tool callback: hold `callback.await` while another native Project
  Manager root in the same session attempts a completion. The competing root
  ends `failed` with code 409 and saves no user turn. After callback delivery,
  assistant tool call, tool result and assistant continuation remain ordered;
  a subsequent explicit retry adds exactly one user and assistant reply.
  Three foreground calls total (tool call, continuation, retried prompt).
  Regression: `server/scripts/reconcileMemoryCallbackConcurrency.test.mjs`.
- Strict typed-tool arguments: `Llm.chatWithTools` now sets
  `validate_tool_arguments=true` in its Jobs request, leaving non-tool
  completions unchanged. With the real Jobs `/llm/chat` router and a fake
  provider response containing `READ_FILE(filepath=42)`, Jobs issues 422,
  counts provider usage exactly once, and the compiled native EventHandler
  finishes `failed` with code 422, releases its session lock, and does not
  save the invalid assistant/tool response. Regression:
  `server/scripts/reconcileMemoryTyped422.test.mjs`.
- Public execution endpoint: native `failed` code 409 maps to HTTP 409 with
  a readable `Session is busy; retry after the active turn completes` message
  and `retryable:true`; other failures retain the generic server-error status.
  The current web caller `useWorkflowExec.start` awaits the endpoint response
  (its `sync=false` mode polls), and `PromptComposer` preserves draft text on
  a rejected HTTP request. A conflict is **not** an automatically queued turn.
  Regression: `server/workflows/services/executionFailureResponse.test.js`.

`scripts/reconcile-test typed-dispatch` now includes the three native
memory-mode regressions and the HTTP-error mapping regression. All 3 memory
tests and both error-mapping tests passed locally. No durable journal work,
production deployment, or live LLM calls were involved. Production release
and a real-browser acceptance check remain separate work.

### Phase 1.11 — merged and deployed production checkpoint (2026-11-19)

**CURRENT STATUS: RELEASED.** The preceding phases are retained as a
chronological test/reconciliation history; earlier statements that this
change was not merged or deployed no longer describe its release status.

- `workflow-utils/main` at `911722e`, `workflows/main` at `0214823`, and
  `server/main` at `84998ae` contain the merged reconciliation work.
- The full 26/26 Project Manager Rust workflow dependency closure was
  published and activated in `gs://awfl-us-native-workflows/`.
- The `awfl-us/server` production Cloud Run deployment for `84998ae`
  succeeded (GitHub Actions run `44639870788`), including the GKE native
  worker image rollout and OAuth/anonymous-request checks.
- Cloud Run API and Jobs use `AWFL_DOCUMENT_STORAGE_BACKEND=postgres`,
  `AWFL_NATIVE_MEMORY_EXECUTION_ENABLED=1` and
  `AWFL_NATIVE_DOCUMENT_STORE_ENABLED=1`. The GKE deployment was aligned
  through the existing native-execution Terraform state: one in-place
  change and no resources added or destroyed.
- Live Project Manager execution `native_ac889a2e593c45e2b963ed01c310f872`
  completed and returned `AWFL_MEMORY_PG_SMOKE_OK`; production GCP logs
  showed `executionMode=memory` and `storageBackend=postgres`.
- The native Rust Try/Raise CI passed on the final server commit. The
  isolated PostgreSQL storage CI passed after allowing the dedicated CI
  `/benchmark_ci` database; production databases remain disallowed.
- Completed remote feature branches were deleted after verifying merge
  ancestry. See `server/docs/plans/2026-11-19-wip-old-production-release.md`
  for the release record and residual validation limitations.

**Important:** durable per-effect Firestore/PostgreSQL journal work remains
**deferred**, not part of this release. No automatic queuing of 409-conflicted
turns was added; callers must resubmit. The production smoke exercised a
real model response but did not constitute an authenticated browser-based
concurrent-tool acceptance test.

### Phase 2.1 — `wip-old` Sadhaka original design reconciled (2026-11-19)

**Status: implemented and locally verified on `feat/reconcile-sadhaka-native`; not merged or deployed.**

Architectural source-of-truth: `workflow-utils/wip-old` `Prakriya.scala`
(`Ista` and `Sadhaka`) and `workflows/wip-old` `EventHandler.scala`
(`given Sadhaka[Input]`). Before modifying additional `wip-old` functionality,
read its original abstraction and use sites across the relevant repositories;
do not replace good original approaches solely to simplify migration.

**Preserved:** the original `Ista[T]` factory performs inline typed schema
derivation when the concrete `Ista[T]` is constructed; generic
`Sadhaka.complete[In, Out]` consumes the stored tool schema without attempting
to derive abstract `Out`. The current `Ista` retains its `build` parameter and
strict `JsonSchema.nativeTool[T]` output. The fixed $0.10 fund, `sideCall=true`,
asynchronous callback, and first tool-call JSON arguments decoding are kept.

**Compatibility adaptations:** the old `WorkflowId` wrapper is unavailable in
current DSL, so the method accepts `workflowName: Value[String]`. The old
Google Workflows connector was replaced by `CallOperations.WorkflowRun`.
Current `ChatToolResponse` has a non-generic `message` and usage information,
so `Sadhaka.Response[Out]` carries the decoded typed message, total cost and
usage. EventHandler receives the selected `Ista` tool schema as `typedTool`
and adds it to model-visible `tools`, while `toolChoice` selects its name.
This does not expose workflow routing metadata to the provider.

**Evidence:** `workflow-utils` local publish/compile succeeded; the updated
Scala workflows compiled, all 26 Project Manager closure binaries compiled
as static Rust, and the standalone Sadhaka parent and child fixtures compiled
to static native Rust. `server/scripts/reconcileSadhakaNative.test.mjs` runs
both native Rust fixtures through PostgreSQL memory-mode execution and the
actual `workflow.run`, callback.create, HTTP callback, callback.await and
typed JSON decoding; it passed, with one callback delivery, expected
`ConvoSummary` fields, preserved cost/usage and successful terminal replay.
The fixture is **test-only and must not be published**. Project Manager's
existing strict `basic`, `no-query`, and `tool-roundtrip` scenarios also passed
on the newly built 26-workflow closure.

**Remaining before promotion:** a complete native Project Manager-driven
Sadhaka provider call still needs a dedicated integration gate. An initial
mock-run failed in `codebase-ProjectManager-prompts` during preload text
concatenation (`String + Null`) before reaching `/llm/chat`; the narrower
native child fixture isolates and verifies Sadhaka itself but does not
establish end-to-end Project Manager preload/model behavior in that scenario.
Add a regression case for invalid/no tool call and callback errors, and
check caller-side schema selection with a real Jobs LLM response. Do not
conflate this phase with deferred durable journaling.

### Phase 2.2 — full native Sadhaka / Project Manager acceptance (2026-11-19)

**Acceptance tests passed locally; release pending.** The original `wip-old`
`Ista[T]` inline factory and generic `Sadhaka` remain intact. The only
execution migration is to native `CallOperations.WorkflowRun`, retaining
the original $0.10 fund, `sideCall=true`, callback and first-tool-call decode.

The initial `String + Null` preload failure was a **fixture bug**: the
reconciliation harness returned Sadhaka's structured callback payload to
CLI preloads that expected command-output results. Distinct callbacks for
parent completion and CLI commands resolved that problem without modifying
production preload code.

**Necessary integration correction:** `EventHandler` now bypasses ordinary
`ToolDispatcher` for `sideCall=true` output calls. An `Ista` output function
is the *answer*, not a registered executable workflow tool. The model-visible
tool and final callback payload are preserved; ordinary agent tool calls still
dispatch normally. This was verified against both the real Project Manager
and the old original `wip-old` behavior before changing it.

`helpers-SadhakaNativeFixture` accepts a test-only target workflow name;
the native build fixture explicitly registers both the minimal child and the
full Project Manager closure. The resulting **28 static Rust binaries** are
for integration testing only and **must never be published as a closure**.
Production publication must instead use the independent
`codebase-ProjectManager --recursive` closure. The native publisher explicitly
rejects requests to publish Sadhaka fixture roots.

The new `./scripts/reconcile-test sadhaka` gate checks 8 PostgreSQL
memory-mode cases across the compiled Rust parent/child graph. The full
Project Manager sends typed `summaries` tool selection and schema to the
**actual Jobs `/llm/chat` HTTP router**, with a mocked provider (no live
models), and its output callback returns to the Sadhaka caller. It verifies
the strict schema, one provider usage record, the calculated cost, no
internal workflow routing leaked to the provider, no tool dispatch of the
typed answer, and cached terminal replay. Failure cases: no tool call,
malformed JSON, invalid schema, wrong tool name (the last three return 422),
provider error (500), and rejected callback delivery. All terminate failed
without repeating provider calls on replay.

Existing strict `basic`, `no-query`, and `tool-roundtrip` Project Manager
scenarios and native PostgreSQL concurrency/typed-422 regressions are the
remaining release gate. This remains **memory mode**, not deferred durable
effect journaling. The full-graph test's model is mocked; production smoke
must be described separately and cannot claim a live Sadhaka parent unless
one is explicitly published as a production workflow.

### Phase 2.3 — Sadhaka reconciliation released (2026-11-19)

**RELEASED:** Previous Phase 2.1–2.2 language describing pending merge,
release gates or deployment is preserved as historical progress; this
checkpoint supersedes it.

- Merged the reconciled `Ista`/`Sadhaka` implementation to
  `workflow-utils/main` at `be2a04f`, workflow/runtime integration to
  `workflows/main` at `1f862c9`, and the native acceptance suite to
  `server/main` at `62d940b`. No changes were made to the deprecated durable
  per-effect journal.
- `./scripts/reconcile-test sadhaka` passed all **8** local PostgreSQL
  memory-mode compiled Rust/Jobs-router tests (two native child targets,
  successful response plus six fault cases). The `basic`, `no-query`, and
  `tool-roundtrip` Project Manager scenarios passed with strict background
  checking; three existing PostgreSQL concurrency, callback and typed-422
  native tests also passed.
- Built and verified **26 static native Rust binaries** independently of
  test fixtures. The artifact publisher changed and activated **3** workflow
  releases, leaving the other **23** unchanged. The active
  `codebase-ProjectManager` binary SHA-256 matched local build:
  `e894a7da1aa17ae6ea73b07766cd4dfbb077cdec361b9a91314813f92513ce94`.
  `helpers-SadhakaNativeFixture` and `helpers-SadhakaChildFixture` were **not**
  published.
- `awfl-us/server` production GitHub Actions run
  [44652647547](https://github.com/awfl-us/server/actions/runs/44652647547)
  completed successfully, including Cloud Run API/Jobs deployment and GKE
  native worker image rollout. A production `codebase-ProjectManager` smoke
  execution `native_c3b8f5981a7b40ccbc02308d48f6de9d` in session
  `0fc5f510-bd55-45d9-a17c-afb8df0b2649` completed and returned
  `AWFL_SADHAKA_RELEASE_SMOKE_OK`; observed usage cost was `$0.00882875`.
  GCP logs explicitly recorded `executionMode=memory` and
  `storageBackend=postgres`.

**Validation boundary:** The live production smoke exercised the updated
Project Manager workflow only. The complete `Sadhaka` parent → actual Jobs
LLM router → Project Manager → callback → typed result flow was verified
locally with a mocked provider, *not* with a publicly published Sadhaka
parent. The exact parent fixture intentionally remains test-only. Future
public call sites should be integrated and smoke-tested as their own release.
**Durable mode remains deferred** for latency reasons.

### Phase 2.4 — restore original registered-tool dispatch semantics (2026-11-19)

The `wip-old` `helpers/ToolDispatcher.scala` already excluded non-executable
functions: execution and `Convo.addMessage` occurred **only** in the branch
matching the function name **and** a present workflow target. The earlier
native reconciliation had accidentally moved `saveResult` outside that match,
so it attempted to save unmatched model-only output functions. Our Phase 2.2
workaround suppressed *every* tool call for `sideCall=true`, which is broader
than the original design and is now superseded.

**Restored on `fix/restore-registered-tool-dispatch`:** `ToolDispatcher` owns
the matching rule again, requiring a nonempty workflow target. It invokes
and persists a result only for the first matching executable registration;
unmatched or model-only calls contribute no result and no tool message.
Its result list contains actual executed tools only, avoiding null placeholders
in downstream cost calculation. `EventHandler` now passes all tool calls
unchanged into the dispatcher, without a `sideCall`-based filtering step.
It still preserves the original side-call lock, context/message, and follow-up
behavior; durable per-effect journaling remains deferred.

**Regression coverage:** `server/scripts/reconcileRegisteredToolDispatcher.test.mjs`
executes the compiled Rust dispatcher against PostgreSQL memory mode for an
unknown function and a registered function with an empty workflow target.
Neither dispatches or saves a tool result, both replay without effects.
`./scripts/reconcile-test sadhaka` includes this gate alongside all eight
typed parent/child completion and failure cases. Ordinary Project Manager
`tool-roundtrip` must still execute and save exactly one real tool result.
Only merge/deploy following successful full native integration tests and
independent 26-binary production compilation.

#### Phase 2.4 verification and rollout (2026-11-19)

- Native Scala compilation and the compiled **28/28** test dependency closure
  passed; direct Rust `helpers-ToolDispatcher` test verified unknown function
  names and model-only function registrations with an empty workflow target
  cause **no execution, no conversation tool result and no fake result entry**.
  Each case replayed successfully. The only HTTP request was the dispatch
  workflow's standard status-write operation, not a tool-result save.
- `./scripts/reconcile-test sadhaka` passed the direct dispatcher test and
  **8/8 Sadhaka cases** including the real Jobs `/llm/chat` router with a
  mock model and full Project Manager child execution. Ordinary `basic`,
  `no-query`, `tool-roundtrip` scenarios passed, and the `tool-roundtrip`
  transcript persisted exactly one actual tool message. The three existing
  native PostgreSQL memory-mode concurrency/422 regression tests passed.
- Merged `workflows/main` at `bd540ad` and `server/main` at `b327ce1`.
  Rebuilt the **separate 26/26 production Project Manager Rust closure**;
  published the two changed active artifacts (`codebase-ProjectManager`,
  `helpers-ToolDispatcher`), leaving 24 unchanged. Both active SHA-256 hashes
  matched the tested binaries:
  `c7847d49c61344ce14bb87288515f51035e9d688501540234363cf387449bceb`
  and `e3bde9e4e41f1ff16d345d3023aab41cd6fdb086af2d65b009ccd14a70dd1657`.
  No Sadhaka fixture workflows were published.
- A live Project Manager smoke execution
  `native_6bd76294359c40759ec9a3c64541ef62` (session
  `ad5d3050-f2e8-47f2-8749-f0434e769955`) completed with exactly
  `AWFL_REGISTERED_DISPATCH_SMOKE_OK`, cost `$0.00893875`; GCP logs showed
  `executionMode=memory` and `storageBackend=postgres`.
- Production CI deployment run:
  [44657281462](https://github.com/awfl-us/server/actions/runs/44657281462)
  **completed successfully**, including the native GKE image rollout,
  latest Cloud Run routing and OAuth checks. Verified Cloud Run revisions:
  `api-00243-5bm`, `jobs-00239-8sg`; both retained
  `AWFL_DOCUMENT_STORAGE_BACKEND=postgres`,
  `AWFL_NATIVE_MEMORY_EXECUTION_ENABLED=1` and
  `AWFL_NATIVE_DOCUMENT_STORE_ENABLED=1`.

The durable journal remains explicitly deferred. A production smoke using a
normal model response does not replace the local compiled Sadhaka and
unregistered-function acceptance tests.

### Phase 3.1 — `wip-old` `helpers/Convo` boundary (2026-11-19)

**Implemented on `feat/reconcile-convo-native`; release pending independent
production build and smoke test.** The architectural reference is
`workflows/wip-old/src/main/scala/us/awfl/workflows/helpers/Convo.scala`,
cross-checked against the original `EventHandler` call site and current
`workflow-utils/Convo`. Preserve the original separation: a helper owns
topic-context construction, model completion, and assistant-message saving;
the outer EventHandler owns agent lifecycle, executable tool dispatch, and
lock coordination. The new child uses the general native `WorkflowRun`, not
Google Workflows. Its current `ChatToolResponse` type replaces the retired
generic `ChatResponse[ChatMessage]`.

**Critical deliberate adaptation:** user-turn persistence and lock acquire
remain together in the **parent** (lock before write). The `wip-old` helper
saved an initial query itself, which would violate our corrected 409 session
contention and could duplicate turns. In the new boundary, parent persists
the user turn only for a non-nil query, after acquiring the session lock;
child persists the assistant response only if `skipAddMessages=false`.
Query-free tool continuations never manufacture a user turn. Existing context
and prompt composition, Sadhaka typed tool choice, `Ista` schema, callbacks,
tool-result persistence, lock release and status updates remain unchanged.

**Measured native local acceptance:** Scala compile passed and **29/29**
test-closure ELF binaries compiled. Comparison with an independently
SHA-256-verified **26/26** pre-extraction Rust release showed identical
foreground `/llm/chat` request payloads (excluding only `workflow_name` and
volatile execution metadata) and identical persisted messages for `basic`,
`no-query`, and `tool-roundtrip`: respectively 2, 2, and 4 messages and
1, 1, and 2 foreground model requests. All three strict-background scenarios
passed. `Sadhaka` complete-through-native-ProjectManager passed, and the
eight-case Sadhaka/dispatcher acceptance gate passed. Three PostgreSQL
memory-mode session/concurrency, delayed callback and typed 422 regressions
also passed after adapting test-only foreground classification to include the
new `helpers-Convo` caller. The direct dispatcher still excludes unregistered
model-only output tools.

**Next release checks:** build/publish the independent production
`codebase-ProjectManager` closure (**27**, including `helpers-Convo`, excluding
Sadhaka test fixtures), verify active artifact hashes and live PostgreSQL
memory-mode execution. Compare latency/cached prompt performance before
proceeding with Status/Summaries/ExtractTopics/ContextCollapser lifecycle
trait extraction. Mixin ordering and background summary stability remain
distinct acceptance gates. **Durable execution remains deferred.**

#### Phase 3.1 merge and production verification (2026-11-19)

- Merged `workflows/main` commit `2772ddb` and `server/main` commit `5a8396a`.
- Independent production root `codebase-ProjectManager` generated and verified
  **27/27** native static Rust binaries. Artifact publication updated
  `codebase-ProjectManager` and new `helpers-Convo`; 25 existing active
  workflows were unchanged. Active object hashes matched the verified build:
  ProjectManager `4f2047219d1106ccfbf5355dfb7279c56a6d20693ac6547e81736d4a870459e3`,
  Convo `a60394af1ea0927537f8efaa582f5fde89bbf7053514cb3b0a5aeb5a96845c42`.
  No Sadhaka test fixtures were published.
- Live production session `4fc628c8-3666-46d9-a1c8-6d9aaa08af78`, native
  execution `native_05bf14c0d5014148b53b2261382319ea`, completed with
  exactly `AWFL_CONVO_NATIVE_SMOKE_OK` and no execution error. GCP logs showed
  PostgreSQL `memory` mode and a completed `helpers-Convo` native child.
- Server production deployment run:
  [44660220538](https://github.com/awfl-us/server/actions/runs/44660220538)
  **completed successfully**, including API/Jobs rollout, GKE native worker,
  traffic routing and MCP/OAuth checks. Final Cloud Run revisions:
  `api-00244-c7g`, `jobs-00240-4nr`; public API `/health` returned `OK`.
  Previous Phase 3.1 text describing pending release checks is historical.

#### Phase 3.2 preparation — original `Status` trait

Original source: `workflows/wip-old/src/main/scala/us/awfl/workflows/event/Status.scala`.
It implements status initialization and exception status via `buildSteps`
composition, and `Done` updates via `stop`, with `Recursion` as the superclass.
Original `traits/Agent.scala` mixes `Status` after `Funds`, before summary
traits. This is an **architectural reference**, not code to copy blindly:
current `EventHandler` must keep the existing lock-first ordering, 409
propagation, callback error delivery, `tryInitStatus` best-effort semantics,
and terminal failure rethrow rather than masking errors.

Implement `Status` in a separate small phase from Summaries, ExtractTopics,
ContextCollapser or the deferred Small/SmallStrider. Compare exact
Running → Done and Running → Failed updates, failure propagation, callback
status, no duplicate status updates, and the type/ordering of `super.buildSteps`
and `super.stop`. Ensure a query-free continuation and a Sadhaka side call
retain their current lifecycle behavior. Build and run normal tool, callback,
typed 422, session contention and process replay tests before merging.
Do not reintroduce Google Workflows or durable per-effect journaling.

### Phase 3.3 — `wip-old` generic Strider reconciliation (2026-11-19)

**Implemented and tested on `feat/reconcile-strider-native` in
`workflow-utils`, `workflows`, and `server`; deployment checkpoint to be
recorded after merge/release.** Reviewed the original `wip-old` Strider
files (`StriderInput`, `StriderBase`, `StriderTraits`,
`StriderAllLatestBackfill`), the original `SmallStrider`, and the current
Summaries/ExtractTopics/ContextCollapser use sites. Preserved the intended
generic `Strider[In, Params, Out]` and `Sadhaka[Params]` relationship, with
the original agent target carried through `StriderInput[Params]`, native
`All`/`Latest`/`Backfill`, and child segment/session/week/term workflows.
Current `Sadhaka` takes a workflow-name `Value[String]`; no historical
`WorkflowId[Params, ChatResponse[ChatMessage]]` or Google Workflows connector
is needed. All nested execution calls use `CallOperations.WorkflowRun`.

**Necessary compatibility fixes beyond the old implementation:**

- A side-call invoked by Strider must **not** start the same background
  Strider pipelines again, otherwise Summaries → Sadhaka → ProjectManager →
  Summaries recurses. `EventHandler` guards the background pipeline when
  `sideCall=true`, but **does not** suppress model tool dispatch broadly;
  only the registered-executable-tool gate in `ToolDispatcher` controls that.
- The caller-specific `Yoj[In]` must reach the model. `Sadhaka.complete`
  now builds that Yoj and passes it as optional `sideCallContext` to
  `EventHandler`/`helpers-Convo`; regular turns retain the default
  TopicContext. This is important for the CollapserMessages document IDs
  and for typed Summaries and TopicInfo context.
- `Backfill.apply` receives its `agentId` explicitly instead of referring
  to a workflow input value that is unavailable at the caller's use site.
  Segments and Ista write locations are otherwise preserved.
- The old `Small`/`SmallStrider`/`SLEEP` auto-scheduled agent is **deliberately
  deferred**, not silently integrated. It includes an unverified hardcoded
  model and adds recurring paid background work, neither justified for the
  core Strider migration. Keep the original branch as the architectural
  reference should that behavior be reprioritized.

**Verified locally**: `workflow-utils` Scala `compile`, `test`, `publishLocal`
passed; the full `workflows` Scala project compiled. The independently built
native ProjectManager closure verified **27/27** static ELF binaries. In
isolated PostgreSQL memory-mode, six compiled end-to-end tests passed:
`Summaries-Convo`, `assistant-ExtractTopics-Convo`,
`context-ContextCollapser-SegKala`, `Summaries-All`, `Summaries-Backfill`,
and `Summaries`. The first three each produce one typed Sadhaka answer and
the expected persisted result. The three aggregate cases each produce
four typed answers and persist one SegKala summary plus session/week/term
updates in the expected collections. Replaying the same segment through a
new native root produced **zero additional typed model calls and zero
duplicate summary creates**. All three Postgres memory-mode ProjectManager
concurrency/callback/typed-422 regressions passed after test classification
distinguished typed Strider calls from foreground user replies.

The legacy local memory-mode fixture was also corrected to drain detached
children before assertions. With that drain, strict-background end-to-end
native memory tests passed for **basic**, **no-query**, and **tool-roundtrip**;
the latter preserved user → assistant tool call → tool result → assistant
continuation order, without failed background executions. The separately
compiled **29/29**-binary Sadhaka fixture closure passed eight direct output
and failure cases (including missing/wrong tool, malformed JSON, invalid
schema, provider error, and callback error). For reproducible publishing,
`scripts/install-model-aware-workflow-utils.sh` is pinned to the exact new
`workflow-utils` source commit `28e85072f010d16b93f4a30a85d7f27201c193f3`;
it locally publishes `0.3.6-SNAPSHOT`, matching `workflows/build.sbt`.
The native publisher is likewise pinned to the tested `compiler-rust` main
commit `c7b2bfd2dcc37acb590a192c945112b79a90ab2d`, rather than its older
compiler revision. A clean full-workflows Scala compile against that exact
locally published `0.3.6-SNAPSHOT` passed in the full local repository
checkout; the linked Git worktree was not used for this test because sbt's
JGit plugin cannot open that worktree's bare `.git` pointer.

**Native-only test gate:** `PGTEST_URL=<loopback /awfl_reconcile> \
./scripts/reconcile-test strider --strider-report <compiled build-report.json>`.
This intentionally bypasses the legacy *durable* development runner's
parallel child restriction; it targets the supported PostgreSQL native
memory-mode runtime and verifies real child execution and writes.

**Release requirements:** merge utility source first, then workflows and
server tests; publish 27 real production native workflows (no fixture
binaries), verify SHA-256 against the active GCS mappings, run a live
ProjectManager smoke test and check its background typed Strider executions.
No durable-effect-journal changes and no legacy Google YAML pipeline.

### Phase 3.4 — expanded generic Strider native acceptance (2026-11-20)

Extended the server `scripts/reconcileStriderNative.test.mjs` acceptance suite
against the same **27 compiled native Rust workflows** and isolated PostgreSQL
memory-mode database. The original six cases remain green; three additional
tests now exercise `assistant-ExtractTopics-All`,
`assistant-ExtractTopics-Backfill`, and `assistant-ExtractTopics` (Latest).
All three invoke four typed Sadhaka completions and write the correct
conversation, session, weekly, and term records, exactly as Summaries does.
The direct-segment repeat-run assertion now applies to **all three output
types**: ConvoSummary, TopicInfo, and CollapseResponse. Re-running the same
segment causes neither another model call nor another collection write.

**Results:** native Strider **9/9 passed**; combined native Strider + delayed
callback/session-lock + concurrent-foreground-turn regressions **11/11 passed**;
full Scala workflows compilation passed. These are deterministic mock-provider
tests with compiled ELF execution and PostgreSQL persistence, not live-provider
or production-deployment evidence.

**Next gates:** preserve this test evidence when integrating with current
`main`, validate lock release on side-call failures and repeated aggregate
execution, and run a live ProjectManager foreground smoke test with background
typed Striders after normal review. Keep **PostgreSQL memory mode** as the
production-target execution mode. **Durable mode remains deferred**.

### Phase 3.5 — Strider lock-release regression resolved (2026-11-20)

An explicit native failure injection reproduced a defect in the historical
`StriderBase` flow: a failed `/firebase/list` after acquisition caused the
root to fail but **never issued `/firebase/locks/release`**, leaving an
owner-scoped segment lock until its 300-second TTL. The fix wraps the work
and release in DSL `Try`, releases on failure, then rethrows the original
error; normal success still releases once. The first test against the old
compiled ELF failed as expected (`actual: 0` releases versus expected 1).

Rebuilt **27/27 static native Rust ELF workflows** against local
`workflow-utils` commit `5f160341fd142279875e64ff4e4194d4aac1df56`
and `0.3.6-SNAPSHOT` (no publication/deployment). The same negative test
passed against the rebuilt ELF, confirming **exactly one release** while
preserving the failed native result. The full combined memory-mode suite
passed **12/12**: nine successful Strider workflows, the failure/release
case, session-turn concurrency, and delayed callback lifecycle.

The tested utility source is pinned in
`scripts/install-model-aware-workflow-utils.sh` to the new commit. Continue
using PostgreSQL-backed **memory mode**, with durable mode deferred.
**Still not exercised:** failed typed provider after callback dispatch,
repeated aggregate runs under concurrent workers, and live production smoke
validation. Do not claim those gates are complete or merge/deploy without
their review.

### Phase 3.6 — initial production release and live-provider verification (2026-11-20)

- Generic Strider sources and callers merged first: `workflow-utils` `28e8507`,
  `workflows` `3f87055`, `server` `239b2c2`. Workflow native release job
  [44672431037](https://github.com/awfl-us/workflows/actions/runs/44672431037)
  **succeeded** and published the 27 native Rust workflows; server deployment
  [44672459226](https://github.com/awfl-us/server/actions/runs/44672459226)
  **succeeded**, including GKE worker, API `api-00245-mxx`, and Jobs
  `jobs-00241-xpz`.
- Initial live production session `b3103ce3-422e-4169-859b-374dd2eeb056`
  on `gpt-5` returned exact `AWFL_STRIDER_NATIVE_SMOKE_OK` and cost $0.00786.
  Summaries-Convo and ContextCollapser completed, but
  `assistant-ExtractTopics-Convo` failed safely with HTTP 422 for invalid
  typed tool arguments. This **is a failed background acceptance case** and
  is not evidence that the generated schema is incorrect. Invalid output is
  charged and rejected according to existing typed Jobs validation.
- Second live production session `8e675eea-c9b8-434d-97be-daa60e55d3c3`
  on `gpt-6.1-terra` returned `AWFL_STRIDER_TERRA_OK`, cost $0.006303, and
  GCP logs showed all three background Striders completing successfully:
  Summaries-Convo, ExtractTopics-Convo, ContextCollapser-SegKala. No
  recursive background launches were observed in the test.
- Separately, the failed-read **lock leak** was reproduced and fixed in
  `workflow-utils` `5f160341fd142279875e64ff4e4194d4aac1df56`.
  The tested 27/27-ELF fix, 12/12 isolated memory-mode regressions, server
  negative test `005a0ec`, and workflow pin/plan `eec006e` are the
  **follow-up release**, not part of the initial live smoke above. Repeat
  the active-binary and production smoke verification after that release;
  do not conflate the initial release with verification of the lock fix.

Deferred: original Small/SmallStrider autonomous background behavior,
per-effect durability, and the three remaining negative/concurrent gates
listed in Phase 3.5.

### Phase 3.7 — local Strider -> ProjectManager -> generated summary (2026-11-20)

**New repeatable native integration lane:** `scripts/reconcile-test strider-pm`
invokes `scripts/reconcileStriderProjectManager.test.mjs` on the server
`feat/reconcile-strider-native` branch. The test seeds a **real isolated
PostgreSQL** document collection with a conversation, runs the compiled Rust
`Summaries-Convo` through `codebase-ProjectManager` / `helpers-Convo`,
and routes the typed completion through the **real Jobs `/llm/chat` Express
router** with schema validation. The generated `ConvoSummary` is persisted
through the **actual Jobs `/firebase/create` route**, and a second native
execution for the same segment must not make another model call or create
another summary. The default provider adapter is deterministic and derives
its summary from the supplied conversation; it does not make external API
requests. A live OpenAI API adapter is available **only by explicit opt-in**
using `AWFL_STRIDER_PROVIDER=live` and a separately supplied API key. Live
provider behavior has **not** been exercised in this checkpoint.

The realistic Jobs PostgreSQL lock route exposed a preexisting bug absent
from mocked lock fixtures: an inline `Value(uuid.generate())` produced
**different owner UUIDs** for acquisition and release. The first run
returned `released:false`, and a second run got HTTP 409 until the lock's
TTL. The corrected `StriderBase` materializes the owner once in a named
DSL `Try` step and reuses that result. Verified with a fresh **27/27 native
ELF rebuild** and a local `strider-pm` pass: one generated summary, one
Jobs LLM request and usage accounting, no duplicate completion, and two
successful lock acquire/release cycles. The source is at
`workflow-utils:72f8b1fe7e10556e63f660441c5a0622035bc9b8`, now
pinned by `install-model-aware-workflow-utils.sh`.

**Reproduction:** from the workflows checkout, provide
`PGTEST_URL=postgres://...@127.0.0.1:55439/awfl_reconcile`, then run
`./scripts/reconcile-test strider-pm --server /path/to/server
--strider-report /path/to/build-report.json`. Supply
`AWFL_STRIDER_CONVERSATION_FILE=/path/to/messages.json` to replace the
checked-in conversation fixture. The native build must use the pinned utility
revision. No production calls or deployment are involved in the default
mode. **Memory mode remains the target; durable mode remains deferred.**

Still outstanding: a genuine provider run (requires an explicit key),
concurrent aggregate runs using the realistic Postgres locks, and a post-fix production
ProjectManager smoke test after the next reviewed merge/release.

### Phase 3.8 — production acceptance, bounded Ista read logging and follow-up release (2026-11-20 UTC)

**Completed initial deploy:** `workflow-utils` main `72f8b1f`, server main
`48875d5`, workflows main `e0da91e`. GitHub Actions native publish
[44677948750](https://github.com/awfl-us/workflows/actions/runs/44677948750)
and server Cloud Run/GKE deployment
[44677943675](https://github.com/awfl-us/server/actions/runs/44677943675)
both **succeeded**. Active native `Summaries-Convo` artifact changed from
`a773de3c…` to `8c864aa6…`. Cloud Run ready revisions:
API `api-00247-dgr`, Jobs `jobs-00243-j5h`; `/health` returned `OK`.
Production Jobs has `AWFL_DOCUMENT_STORAGE_BACKEND=postgres` and
`AWFL_NATIVE_MEMORY_EXECUTION_ENABLED=1`. **Durable mode remains deferred**.

**Live smoke:** a new native ProjectManager session
`ed65dd8e-573d-4873-8127-0899ab1a6bf2` (initial execution
`native_0a91fe5e224c42f7a4e57a805ba43512`, model `gpt-6.1-terra`,
budget `$0.30`) produced exact `AWFL_STRIDER_POST_DEPLOY_OK` with `$0.006363`
for the foreground response. GCP logs showed `Summaries-Convo`,
`Summaries-Session`, `Summaries-Week`, `Summaries-Term`, and
`context-ContextCollapser-SegKala` completing. However the detached
`assistant-ExtractTopics-Session` failed during native `log.write` with
`invalid authenticated native log effect` (root
`native_f87eee7032405c53bd8b66f908bd5481`). Thus the *initial* smoke
was **not** fully green; do not count the successful foreground response as
proof that all background Striders completed.

**Root cause and local fix:** `workflow-utils` `Ista.read` (`Prakriya.scala`)
logged each **complete stored JSON item**. On larger topic/summary documents
this exceeded the server's authenticated `log.write` text limit of 4,096
bytes. Preserve that server limit. Remove the unbounded per-item diagnostic
log, retaining the instrumented kala and actual HTTP read. In the native
server harness, route all log effects through the real
`createNativeLogEffectHandler` rather than an unconditional mock. Added a
6 KB stored-summary aggregation regression: **prior ELF failed with the
same invalid authenticated native log effect; rebuilt ELF succeeded**.
`workflow-utils` local Scala compile/test/publishLocal succeeded, all
**27 static native Rust workflows** rebuilt successfully, and the combined
Strider/ProjectManager/callback/concurrency/typed-422 suite passed
**15/15**. Follow-up commits: `workflow-utils` `bf24233` and server
`0573b6a`. The pinned workflow-utils revision for the native publisher is
`bf24233170e48d4358540a61dda1bc6c63f9fcbe`.

**Next acceptance and implementation work, in priority order:**

1. **Release and re-smoke the bounded-log fix** after workflows publication
   and server deployment. Verify the new active native artifact and run a
   fresh real-provider ProjectManager test. Require *all* Summaries,
   ExtractTopics and ContextCollapser background workflows to complete;
   check that no `log.write`, orphan callback, recursive Strider launch,
   lingering segment lock, or duplicate summary write remains.
2. **Hard failure and parallel aggregation gates:** in isolated PostgreSQL
   memory mode, execute simultaneous Striders for the same segment and verify
   exactly one write and correct lock ownership/release. Exercise failed
   typed provider and failed callback delivery, idempotent retry, and
   large pre-existing topics. Preserve the server's authenticated effect
   limits instead of weakening checks.
3. **Resume the deferred `wip-old` `Status` trait slice** (Phase 3.2 above)
   independently: compare Running → Done/Failed lifecycle, 409 session-busy,
   typed 422, callback continuation and query-free side calls against current
   EventHandler. Do not import the old Google Workflows implementation or
   alter memory-mode ordering.
4. After `Status`, only selectively reconcile lifecycle composition and
   optional CLI/web work where current behavior genuinely benefits. Keep
   `Small`/`SmallStrider` autonomy, SLEEP, and durable journaling deferred;
   measure latency/cache and model costs before enabling more background work.

### Phase 3.9 — bounded-log follow-up fully deployed and live Strider smoke green (2026-11-20 UTC)

**Production release complete.** The bounded `Ista.read` logging fix from
`workflow-utils` main `bf24233170e48d4358540a61dda1bc6c63f9fcbe`, and
its matching 6 KB authenticated-log regression from server main
`0573b6a5e5f48b2d15de528530a6024b1dbaf383`, are deployed. The native
publisher on workflows main `4d800717016913ff6669f6c3df3c84ca71a97b67`
succeeded: [GitHub Actions run 44679741499](https://github.com/awfl-us/workflows/actions/runs/44679741499).
The API, Jobs and native GKE worker rollout also succeeded:
[server deployment run 44679612939](https://github.com/awfl-us/server/actions/runs/44679612939).
Current Cloud Run ready revisions verified after this deployment:
API `api-00248-6s5`, Jobs `jobs-00244-2kw`; public `/health` returned `OK`.
Active GCS native release manifests include `Summaries-Convo` SHA-256
`4aa245d83b07b20a9aadfb769b009ed845f7cd135f3ded28e2e01e0c4228cdc2`
and `assistant-ExtractTopics-Session` SHA-256
`8290609be798f797752d9cbaf6d11c5c31fc7b6882eb92bb1331f7c653d546a7`.

**New live-provider smoke verified:** `codebase-ProjectManager` session
`d69a590e-ceb8-45ec-acd1-36349a24f61a`, initial submitted execution
`native_a9d43d41fb534264a46cabb64dee5167`, model `gpt-6.1-terra`, fund
`$0.30`, responded exactly `AWFL_STRIDER_BOUNDED_LOG_OK` with foreground
assistant cost `$0.006339`. In GCP native workflow telemetry for this run,
the following three detached roots and every expected stage completed:

- `Summaries` root `native_5c798b6ffe967d911705e1455bd02718`: `Summaries`,
  `-All`, `-Convo`, `-Session`, `-Week`, `-Term`.
- `assistant-ExtractTopics` root `native_c968c8e3e6d08d3fde41324e92068eb2`:
  `assistant-ExtractTopics`, `-All`, `-Convo`, `-Session`, `-Week`, `-Term`.
  Notably `-Session` **succeeded** where the preceding live smoke failed.
- `context-ContextCollapser-SegKala` root
  `native_1c54e3b0868f014a64240080caa63ca2` completed.

No native `workflow_failed`, `execution_failed`, or `effect_failed` log
entries appeared in the observed smoke window (from
`2026-11-20T05:20:45Z`). This establishes one healthy post-fix live-provider
run. It does **not** establish exhaustive long-term reliability or
exactly-once semantics under worker concurrency.

**Next on the reconciliation plan:** (1) execute simultaneous real-Postgres
Striders for the same segment and verify owner-scoped lock contention,
exactly one persisted summary, no orphan callbacks, and retry after failure;
(2) add typed-provider/callback failure and large-topic regressions;
(3) resume the independent historical `Status` trait reconciliation from
Phase 3.2, preserving current EventHandler lock-first ordering and
Running → Done/Failed behavior. Measure background cost/latency and cache
impact before considering further autonomy. **Native Rust PostgreSQL memory
mode stays primary; per-effect durable journaling, Small/SmallStrider and
SLEEP remain explicitly deferred.**

### Phase 3.10 — real PostgreSQL concurrent Strider and failure-path acceptance (2026-11-20)

**Implemented in the existing local Rust/Jobs integration harness:** the
server `scripts/reconcileStriderProjectManager.test.mjs` now covers four
isolated scenarios using compiled `Summaries-Convo`, the actual Jobs LLM
router and PostgreSQL-backed `/firebase` routes. Existing `sequential` is
retained. `contended` holds the winning model response and launches another
compiled native Strider using a **distinct native execution-store instance
and PostgreSQL connection pool**. The second acquire returns a real HTTP
`409`, with no second model completion or premature summary write. Once
released, the winner persists one typed summary; a later repeat performs no
duplicate completion. The test verifies every successful acquire has an
identical release owner and the PostgreSQL segment-lock collection is empty.

`provider-failure` causes the Jobs LLM provider adapter to fail before a
summary is committed. `callback-failure` rejects the native callback await
**after** the first typed model response. Both runs fail without a partial
summary, release the owner-scoped lock, and succeed when retried with a new
native execution, creating exactly one output. All callback awaits use URLs
issued by callback.create in the same fixture. Native log effects use the
**production authenticated log handler**, rather than a no-op. In the
existing compiled Strider test, both a **6 KB summary** and a realistic
**6 KB TopicInfo document** survive the aggregate read path without the old
unbounded-log failure.

**Local verification:** `./scripts/reconcile-test strider` passed: 4/4
real-Jobs/native/Postgres ProjectManager scenarios, 12/12 native Strider
regressions, and the existing three memory-mode session/callback/typed-422
tests. The contended case also passed **five consecutive independent runs**.
No Scala library or compiled workflow modification was required.

**Limitations and next acceptance gate:** worker stores/connections are
independent but run within one Node process; the callback delivery and
auxiliary context endpoints are still deterministic fixtures. This does
**not** establish callback garbage collection or crash-proof exactly-once
external effects, and a production pod-level race remains a separate
deployment-sensitive test. The next feature slice is the original `Status`
trait from Phase 3.2: compare lock-first EventHandler semantics, normal and
error transitions, 409 contention, typed 422 and query-free callbacks before
changing its composition. Durable mode and autonomous SmallStrider remain
deferred.

### Phase 3.11 — historical `Status` lifecycle extracted without changing native behavior (2026-11-20)

Original source reviewed directly: `wip-old:src/main/scala/us/awfl/workflows/event/Status.scala`.
It wrapped `buildSteps` to inject best-effort Running and Failed status updates,
and `stop` to set Done. The **current** native EventHandler already has
important ordering and exception constraints absent from this historical
design: session lock acquisition before saving the user turn, tool dispatch
and lock release before terminal Done, explicit callback failure delivery,
and a final Raise so the root is never silently marked complete after a 409
or typed 422. Blindly mixing in the old `buildSteps`/`stop` overrides could
change those invariants.

**Implementation:** add `event/Status.scala` as a composable trait with
`statusStart`, `statusComplete`, `statusErrorMessage`, and `statusFailure` DSL
step constructors. `EventHandler` mixes in `Status` and uses the constructors
at its **existing exact call sites**, preserving the order and stable native
step IDs `tryInitStatus`, `statusRunning`, `enqueueStatusRunning`,
`statusDone`, `enqueueStatusDone`, `statusFailed`, and
`enqueueStatusFailed`. In particular, an error still releases the session
lock, attempts Failed status, notifies the callback, then rethrows. The
original `Recursion` or `stop` override is intentionally not reintroduced.

**Local validation:** independent standalone checkout avoids the sbt/JGit
linked-worktree bug. `sbt compile` passed. Recompiled the full **27 native
Rust static-ELF ProjectManager closure** with `publish=false` and compared
the generated `src/main.rs` across every workflow to the pre-extraction
compiled closure: **27/27 files are byte-for-byte identical**. Re-ran the
same `./scripts/reconcile-test strider` suite against the new build:
4/4 Strider → ProjectManager/Postgres scenarios, 12/12 native Strider cases,
and all three memory-mode concurrency/callback/typed-422 regressions passed.
This is a behavior-preserving extraction, **not** a claim of improved
status/lifecycle reliability or different production semantics.

**Reusable source equivalence gate:**

```bash
python3 scripts/verify-native-source-parity.py \
  /path/to/pre-status/native-artifacts \
  /path/to/post-status/native-artifacts
```

This compares every generated Rust `src/main.rs` and fails on missing, new,
or changed native workflow sources; it does not replace runtime integration
tests. For this extraction it printed
`AWFL_NATIVE_SOURCE_PARITY_PASS workflows=27 identical=27`.

**Next:** maintain a status-specific parity gate that asserts persisted
Running → Done/Failed events and callback error delivery against Jobs routes,
including side calls and query-free continuations. Then consider whether
additional lifecycle mixins (`Summaries`, `ExtractTopics`, `ContextCollapser`)
can be extracted without changing native step order, background model cost,
or cached prompt behavior. Durable mode remains deferred.

### Phase 3.12 — `Status` extraction published; production smoke (2026-11-20 UTC)

**Published:** workflows main `6b41a58fed9fbd29fc56dd7cc7d65c6f25b0957a`
contains the Status extraction, and `e3978779451684899d06f53a9d122f1083580557`
adds the reusable parity gate. Native publisher
[GitHub Actions 44683242268](https://github.com/awfl-us/workflows/actions/runs/44683242268)
completed successfully for the extraction commit. The active manifests
retain the exact prior artifact digests, consistent with the byte-identical
27/27 generated Rust sources: `codebase-ProjectManager` `cd07f008…`,
`Summaries-Convo` `4aa245d8…`, `assistant-ExtractTopics-Session`
`8290609b…`. No new GKE or Cloud Run service release was needed for this
source-equivalent workflow publication; public API `/health` returned `OK`.

**Live-provider verification:** production ProjectManager session
`0f603f47-9343-46f6-a06e-788299a5d22a`, native root
`native_fc5a07c9e73c4e219e1e8406fe3430f4` (model `gpt-6.1-terra`,
fund `$0.20`) produced exact `AWFL_STATUS_NATIVE_OK` and assistant cost
`$0.006425`. GCP telemetry shows the root native execution completed at
`2026-11-20T05:50:59.300791Z` and all six `Summaries` stages, all six
`assistant-ExtractTopics` stages, and `ContextCollapser-SegKala` completed.
No native effect/workflow/execution failures appeared in the inspected window.

**Boundary:** the session-info API showed `status=unknown` with no latest
execution after completion, despite a persisted assistant reply and a native
completion log. Do not infer from this that the status view is fully
reconciled. Investigate/cover persisted Running → Done and Running → Failed,
including transient lookup status and callback failure paths, in the next
status-specific Jobs integration gate. The Status extraction intentionally
preserves existing behavior rather than claiming to correct this read path.

### Phase 3.13 — restore wip-old `Recursion` and `Status` trait composition (2026-11-20)

**Design decision:** the earlier Phase 3.11 extraction retained the status operations but explicitly invoked them inside `EventHandler`; that did **not** restore the original composable architecture. Revisit the actual `wip-old` source `event/Recursion.scala` and `event/Status.scala` instead. `Recursion.stop` is an overridable terminal hook with no runtime dependencies. The original `Status` overrides `buildSteps` for Running/Failed and `stop` for Done. It can be adapted to the current native Rust memory-mode execution.

**Implementation (feature branch `feat/restore-recursion-status`):** reintroduce `event/Recursion.scala`, make `Status extends Workflow with Recursion`, and override the **generic** `Workflow.buildSteps[T: Spec]` to inject `statusStart` and the `Failed` status. `Status.stop` supplies terminal `Done` and delegates to `super.stop`. `EventHandler` now invokes just `stop` in the terminal non-tool-feedback branch; it no longer manually sequences status operations. The existing base `Workflow.buildSteps` still registers executions and rethrows original failures. A narrow `beforeStatusFailure` hook lets `EventHandler` release the identical owner-scoped Convo session lock *before* the Failed update; afterward the existing failure callback is delivered and the original error is rethrown. Query-free continuations, side-call lock bypass, 409 busy semantics and typed 422 handling remain native.

**Local compile and native validation:** Scala compilation succeeded; the full 27-workflow static Rust-musl ProjectManager dependency closure was compiled and verified. Comparison of generated Rust source files against Phase 3.12 changed **only `codebase-ProjectManager`** (26/27 sources unchanged), as expected. The local Strider and real-Postgres ProjectManager scenarios passed (4/4 + 12/12). Memory-mode error tests verify actual outbound status-update calls: a successful user turn follows `Running → Done`, an overlapping busy turn `Running → Failed` with 409, and invalid typed provider output `Running → Failed` with 422. A tool-call turn that delegates to a detached continuation correctly leaves its initial execution at `Running`; the terminal continuation records `Done` under its own ID. Keep explicit parity assertions for this rather than falsely requiring an initiating execution to emit Done.

**Deferred:** the Jobs session-info `status=unknown` read-path issue observed in Phase 3.12 is separate from the lifecycle trait; the trait refactor does not claim to repair it. Continue using native Rust PostgreSQL memory mode; durable-mode journaling remains deferred. Next check persisted status reads against Jobs after the deployment and then selectively review other lifecycle mixins without reintroducing Google Workflows.

### Phase 3.14 — composable Recursion/Status published and production-verified (2026-11-20)

**Merged and deployed:** workflows main `9e9f547794837e83e2d39f6768497f0c572ea9e7` restores the wip-old `Recursion`/`Status` trait composition, and server main `1d5ab8700852ccc1d97a4710b7e469941301a1b0` adds status-transition regressions. Native publisher [GitHub Actions 44754599812](https://github.com/awfl-us/workflows/actions/runs/44754599812) and Cloud Run/GKE worker deploy [44754608471](https://github.com/awfl-us/server/actions/runs/44754608471) both **succeeded**. The active native `codebase-ProjectManager` artifact SHA-256 changed from `cd07f008afbbb8a7a8b04b26e6fc726692cb59b3347488c74a9b962b5f38d1b8` to `ad2ce7987be79c1dc5df1ac1f159f8b60bf7c1766b1fdba32f26ad773904776d`, runtime ABI 3, protocol 2. `Summaries-Convo` artifact stayed unchanged. Production ready Cloud Run revisions: API `api-00250-22k`, Jobs `jobs-00246-kpj`; `/health` returned `OK`.

**Live-provider smoke:** project `gNoQN9KUw5IbtsXLag0M`, session `a98778db-2eae-4ce7-b4b7-32d09437f0cd`, native root `native_7dd212b6701448bbabb254f0cf8e33fd`, model `gpt-6.1-terra`, fund `$0.25`, returned exactly `AWFL_RECURSION_STATUS_OK` with assistant cost `$0.006522`. GCP native telemetry shows root completion plus all six `Summaries` stages (root `native_4ed094911065a8efdb63dba86d50d8a6`), all six `assistant-ExtractTopics` stages (root `native_77efada94b63c175b23c0d718e2db5c7`), and `context-ContextCollapser-SegKala` (root `native_1d18939a0e03641d36770b4bbb04f52c`). **Zero** `awfl_native_workflow_failed`, `awfl_native_execution_failed` or `awfl_native_effect_failed` log entries appeared in the checked window (from `2026-11-20T14:40:25Z`). The local final gates were 4/4 Strider → ProjectManager/Postgres, 12/12 compiled Strider, and 3/3 memory-mode 409/422/callback-continuation lifecycle tests; Rust native build verified 27 static musl ELFs. Only ProjectManager's generated Rust source changed against the prior 27-workflow closure.

**Known separate issue remains:** after foreground and background completion, `get_session` on the smoke session returned `status=unknown, latest_execution=null`. This does **not** show the lifecycle trait itself failed: at submission it returned a real Running execution, the assistant response was persisted, and the native completion was logged. The session-info read/aggregation path still needs its own investigation and persisted-status reconciliation. Do not claim it is fixed by this change. Keep native Rust/PostgreSQL memory mode; durable journaling and autonomous SmallStrider stay deferred.

### Phase 3.15 — fix session-info status lookup after native child fan-out (2026-11-20)

**Cause reproduced:** `workflows/exec/statusService.js` scanned only `min(50, limit*5)` newest `workflowExecsBySession` registrations. With `limit=1`, any five newer child workflow registrations **without** a corresponding `workflowExecStatus` document displaced the terminal user-turn registration, causing the Agents MCP `get_session` and `review_session` response to incorrectly return `status=unknown`, `latest_execution=null`. The parent status update was not necessarily lost. Native ProjectManager can create many such children for Striders and workflow composition, so the bug appeared after a successful foreground response.

**Fix:** page in creation-descending order through registration documents until the requested number of **status-bearing** executions is found or the session is exhausted. Use Firestore `startAfter(documentSnapshot)` with a matching cursor implementation in the Storage-backed `legacyBridge` compatibility query; PostgreSQL uses its existing `startAfter` document path. Cap pathological scans at 10,000 registrations and fail explicitly rather than returning a false `unknown`. Preserve the distinction between no registrations and statusless registrations, and preserve the established response shape and project/user scoping. Both Agents MCP and the legacy GET latest-status route use this shared helper; no change to native Rust workflow source or status-write semantics.

**Reproduction/evidence:** new server `scripts/reconcileSessionStatusPostgres.test.mjs`, restricted to loopback `/awfl_reconcile`, creates 125 newer statusless children and two older status records, with same-second registration ties. Before the fix the real PostgreSQL-backed test returned **no** statuses; afterward it returned `Done` for the foreground session, and `Failed` as the second status. A truly statusless session still returned `unknown`. Added a matching mock Firestore-shaped pagination regression in `agentsMcp/tests/service.test.js`; combined eight unit/integration cases passed. Local runner `strider` and `strider-pm` invoke the new PostgreSQL test.

**Rollout acceptance:** deploy the server and re-query the previous production smoke session `a98778db-2eae-4ce7-b4b7-32d09437f0cd` (and another previous terminal session) via Agents MCP. Require `status=Done` and a non-null `latest_execution` belonging to the session. The latest status-bearing execution may be a later EventHandler continuation rather than the originally submitted native root. Check independent native failure/status gates and Cloud Run/GKE deployment status. **Durable mode stays deferred; native Rust Postgres memory mode is unchanged.**


### Phase 3.16 — session-status pagination deployed and verified against existing production sessions (2026-11-20)

**Production release succeeded:** server main `4ad68af8a260457199663afad82d70f5cd3c2731`. The [server deployment action 44762892442](https://github.com/awfl-us/server/actions/runs/44762892442) completed successfully, including API and Jobs Cloud Run plus the native GKE worker. The independent [MCP protocol/consumer test action 44762892511](https://github.com/awfl-us/server/actions/runs/44762892511) passed. Ready service revisions: `api-00251-d8w`, `jobs-00247-cr8`; the public `/health` returned `OK`. **No workflow compilation or database schema migration was required.** The existing native Rust PostgreSQL memory-mode executor was unchanged.

**Before/after production proof without rerunning any LLM or workflow:** using connected Agents MCP `get_session` for the same existing production project `gNoQN9KUw5IbtsXLag0M`:

- Session `a98778db-2eae-4ce7-b4b7-32d09437f0cd` (the Recursion/Status live smoke) was `status=unknown, latest_execution=null` before deployment. After deployment, it returned **`status=Done`**, a non-null `latest_execution` ID `native_dd8c76391393af996f70a9f0dd5d85e7`, and `ended=true`.
- Session `d69a590e-ceb8-45ec-acd1-36349a24f61a` (the previous Strider smoke) was also `status=unknown, latest_execution=null` before deployment. After deployment it returned **`status=Done`**, a non-null `latest_execution` ID `native_0902ec3e4de2b43aeb3ecddd84828b77`, and `ended=true`.

Those returned execution IDs need not equal the original `native/...` launcher IDs: this shared status endpoint intentionally chooses the latest **status-bearing** session execution, which may be an EventHandler continuation. The bug was the disappearing status, not incorrect compilation or failed lifecycle writes. If a consumer needs specifically the original submitted root execution, define and query that distinction separately rather than changing `latestSessionExecutions` semantics silently.

**Acceptance gates:** isolated PostgreSQL reproduction changed from failing (zero status-bearing results behind 125 newer child registrations) to passing with a true `Done` result plus the earlier `Failed` status; all 9 Agents MCP / PostgreSQL test cases passed. The standard local native `strider` gate passed all 4 Strider → ProjectManager/Postgres scenarios, the new status pagination test, all 12 compiled Strider cases, and the three existing memory-mode 409/422/callback tests.

**Next work:** (1) decide whether session clients need both `latest_execution` (latest status-bearing continuation) and an explicit root/user-turn execution ID; (2) verify real callback persistence/cleanup and a multi-pod contention race; (3) resume selective `wip-old` lifecycle/composition reconciliation with original-branch design parity and memory-mode latency measurements. Durable mode remains explicitly deferred; do not reintroduce Google Workflows.


### Phase 3.17 — DSL-first dependency correction; retain intended deferred-error Spec (2026-11-20)

**Priority correction:** before any further `Summaries`, `ExtractTopics`, or `ContextCollapser` lifecycle extraction, finish the `dsl` `wip-old` foundation. The earlier disposition incorrectly rejected the `nonProductSpec[T]` implicit as purely unsafe. Original `wip-old` intent, confirmed against `Spec.scala`, `Steps.scala:Match`, `macros/README.md`, and commit `172e46a`: opaque `Value[ADT]` needs a `Spec[ADT]` while it is transported; the fallback deliberately throws only when an unsupported `.get` attempts to materialize a non-product type in Scala. Primitive unsafe access remains a separate, existing mechanism.

**Checkpoint committed, not merged or production-published:** `awfl-us/dsl` branch [`feat/reconcile-nonproduct-spec`](https://github.com/awfl-us/dsl/tree/feat/reconcile-nonproduct-spec), commit `9a706d474e5eed0b10e8769f598e328bc3fe0d94`. It restores the low-priority `Spec[T]` deferred-error fallback, `OptBase.present`, and the nested optional `mapValue`/`map.get` correction. It does **not** restore the historical `nonProductBuilder` because the fallback is already reachable through `SpecBuilder.specBasedBuilder`, and does not yet merge the old ADT Match macro. Local tests validate opaque ADT transport, explicit exceptions on unsupported projection, nested product values, existing primitive guards, optional field CEL construction. `dsl` compile + tests + local publish passed. A separate, noncommitted `0.1.0-SNAPSHOT` dependency override compiled all 33 `workflow-utils` Scala sources and tests and all 58 ProjectManager/workflows Scala sources successfully. No published `dsl:0.3.5` artifact or production native workflows changed. Detailed gate: [`dsl/docs/plans/2026-11-dsl-wip-old-integration.md`](https://github.com/awfl-us/dsl/blob/feat/reconcile-nonproduct-spec/docs/plans/2026-11-dsl-wip-old-integration.md).

**Next required work:** prove or rewrite the original `IsAdt`/`Match` semantics (first matching branch, correct subtype discrimination and bindings, no fake object equality), compile and execute with current native Rust, and compare workflow output, callback behavior, and prompt cache. Release the validated DSL version and downstream dependencies before resuming lifecycle composition. Keep `Small`/`SmallStrider`, `SLEEP`, and durable journaling deferred; memory-mode remains primary.


### Phase 3.18 — Original `IsAdt`/`Match` candidate-JSON design, native Rust parity (2026-11-20)

The original `dsl/wip-old` macro and `Match` algorithm (per-case candidate reconstruction, `ForRange`/`Try`, full `json.encode_to_string` equality, source-ordered `Switch`) are retained, **not** replaced with subtype-tag-only conditions. On current native Rust memory mode, reconstructed structural Kala variants are selected without changing the serialization format. `IsAdt` itself does not emit or require a discriminator. `workflow-utils/wip-old` actual `Locks.buildSessionKey` and `TopicContextYoj.encodeKalaVal` syntaxes informed the Scala and native integration fixtures.

`dsl/feat/reconcile-adt-match` restores the macro and API, fixes implicit `Spec[T]` application for RHS expressions such as `str(id.cel + "@admin")`, adds case-class literal-field reconstruction, and adds a clear failure for non-exhaustive matches rather than returning `{}`. It retains the previous `nonProductSpec[T]` and optional-value fixes. `compiler-rust/test/native-json-candidate-match` adds **tests only**: two Scala fixture generators plus Python that builds actual Rust binaries and checks 31 cases including valid Kala variants, malformed input errors, literal field matching, wildcard priority, branch-local `http.post` effect dispatch, and effect continuation via injected result. All native tests passed; no Google Workflows, durable execution, or native compiler production serialization edits.

**Caveat:** exactly matching structural JSON from two semantically distinct variants cannot identify subtype without a separate encoding contract; this was also true in `wip-old`. Deep pattern destructuring and guards were outside the original supported shape and remain separate possible work. Preserve the deliberate deferred-error `Spec` fallback. **Next:** versioned DSL publishing and downstream recompilation after full compatibility checks; only then resume `Summaries`, `ExtractTopics`, `ContextCollapser` lifecycle reconciliation.


### Phase 3.19 — DSL ADT Match merged and v0.3.6 release initiated (2026-11-20)

**Merged:** `awfl-us/dsl` `main` at [`f47f643`](https://github.com/awfl-us/dsl/commit/f47f643b5871bc7a83bda81ffeda90c91a8c389f), including the earlier deferred-error non-product `Spec`/nested optional-value slice and the original candidate-JSON `Match` with literal fields, typed and constructor binders, and fail-closed errors. The resolver macro now *fully applies its `Spec[T]` context argument*, fixing compound RHS expressions `str(id.cel + "@admin")`. The change uses no subtype tag and does not change native Rust compiler production code.

**Validation:** `dsl sbt test publishLocal` PASS. `workflow-utils` `compile test` PASS; ProjectManager/workflows `compile` PASS on all 58 Scala sources, both with a *temporary* local `dsl:0.1.0-SNAPSHOT` dependency override (no persistent build changes). Native Rust frontend generated five fixtures, Python built and ran their binaries, **31/31 assertions PASS**, including malformed input failures, source-order/wildcard, literal field matching with computed RHS, unselected effect isolation, and injected-effect resume. Rust tests on [`compiler-rust/test/native-json-candidate-match`](https://github.com/awfl-us/compiler-rust/tree/test/native-json-candidate-match) at [`5ad2eac`](https://github.com/awfl-us/compiler-rust/commit/5ad2eaca0d6af9aad4ca71c3ac4b361cbaac40d9) remain **unmerged** until the compiler's locked DSL dependency can be updated from `0.3.5` to the new released `0.3.6`.

**Publication:** created [DSL v0.3.6 release](https://github.com/awfl-us/dsl/releases/tag/v0.3.6) on exact main commit `f47f643`; GitHub Actions publishing job [`44778448776`](https://github.com/awfl-us/dsl/actions/runs/44778448776) initially in progress. Do **not** claim Maven Central availability until release CI and external artifact resolution are verified. After release, update compiler-rust dependency, test without override, and merge integration fixtures. Only then selectively reconcile the live `workflow-utils` usage sites and workflow lifecycle traits. Native Rust **memory mode** remains the execution target; durable mode is intentionally deferred.


### Phase 3.20 — Published DSL, restored live Kala usage, dependency rollup (2026-11-20)

**DSL publication verified:** [`v0.3.6`](https://github.com/awfl-us/dsl/releases/tag/v0.3.6) Maven Central publish action [`44778448776`](https://github.com/awfl-us/dsl/actions/runs/44778448776) succeeded, and the real `dsl_3/0.3.6` POM was fetched with HTTP 200 from Maven Central. The native compiler conformance tests from `test/native-json-candidate-match`, including all **31 native Rust assertions**, were rerun *without* local overrides against the published dependency and merged to [`compiler-rust/main` `b7a2cca`](https://github.com/awfl-us/compiler-rust/commit/b7a2ccaf4ab9bada0a6843847859a5af106bab89). Only tests and the frontend's DSL dependency changed; native compiler production code unchanged.

**Real original usage sites integrated:** `workflow-utils/main` at [`d7680d3`](https://github.com/awfl-us/workflow-utils/commit/d7680d371a44a1e231f18700ffc535dc4ca07db6) adds `Locks.buildSessionKey(Value[KalaVibhaga])`, `TopicContextYoj.encodeKalaVal(Value[KalaVibhaga])`, and the `KalaVibhaga extends IsAdt` marker. Current `Locks.sessionKey(using KalaVibhaga)`, `TopicContextYoj.run` overloads, model metadata and other unrelated production changes are preserved. `Context.segKala` and peers now return `BaseValue[Ctx.Kala]`, so the old `obj(...)` wrapper was deliberately not copied. Smoke and full `workflow-utils` compile/tests passed using **published DSL 0.3.6 without override**. Two *actual* utility methods compiled into native Rust and passed **8/8** runtime checks for Seg/Session/Week/Term Kala, using the real `TermKala(termBegin, termEnd)` JSON field names, with no HTTP effects. The native integration fixtures are committed to [`compiler-rust/test/adt-real-utils-sites` `ae907b6`](https://github.com/awfl-us/compiler-rust/commit/ae907b64454d4c6e2cea2581ca5ef018ecea9093); they remain branch-only until `workflow-utils 0.3.7` is confirmed published.

**Next release:** created [`workflow-utils v0.3.7`](https://github.com/awfl-us/workflow-utils/releases/tag/v0.3.7) at `d7680d3`; Maven Central publishing job [`44780839221`](https://github.com/awfl-us/workflow-utils/actions/runs/44780839221) was in progress when checked. Verify both CI completion and Maven Central artifact availability, then compile the compiler-rust native real-utility test branch *without any overrides* and merge; `workflows` `feat/adt-versioned-dependency-rollup` pins DSL `0.3.6`, workflow-utils `0.3.7` and removes the superseded `0.3.6-SNAPSHOT` override. Validate in a **normal Git clone**, because sbt-dynver/JGit cannot load the linked `reconcile-workflows-routing` worktree (NoWorkTreeException). Do not merge the dependency rollup until all stable artifacts resolve and ProjectManager compiles.

**Deferred unchanged:** `SmallStrider`/autonomous recurring work, Google Workflows execution, and durable per-effect storage. Native Rust memory mode remains primary. Next feature work after dependency parity: original `Summaries`, `ExtractTopics`, `ContextCollapser` lifecycle traits.


### Phase 3.21 — versioned dependency and native CI gate prepared (2026-11-20)

**Published DSL 0.3.6** is independently resolved from Maven Central; `workflow-utils` 0.3.7 release `44780839221` remains publishing at this checkpoint, so **do not merge its dependent build updates prematurely**. The prepared `compiler-rust/test/adt-real-utils-sites` branch pins published DSL `0.3.6` and planned workflow-utils `0.3.7`, retains actual `Locks.buildSessionKey` and `TopicContextYoj.encodeKalaVal` frontend sources, adds a CI gate that regenerates and runs the native Scala-to-Rust ADT fixtures, and pins the separate real-workflow source audit to the integrated `workflow-utils` commit `d7680d3`. The Python Rust harness respects `CARGO_TARGET_DIR` to reuse CI's shared target directory.

**Concrete acceptance evidence:** matcher-native **31/31** and actual real-utility-native **14/14** assertions passed locally, **45/45 total**. The real-utility suite now also checks three malformed variants: Locks catches the no-match as an explicit `log.write` effect (its original outer `Try`), whereas `TopicContextYoj` returns `failed` with the non-exhaustive match message; neither returns a forged valid result. No HTTP was dispatched in these tests. Scala `workflow-utils` compiled and passed tests against the published DSL 0.3.6 without overrides. The ProjectManager/workflows version-pin branch at `4c78ddf` compiled all **58 Scala sources** with published DSL 0.3.6 and *local* workflow-utils `0.1.0-SNAPSHOT` as a temporary override. This was done in a **fresh normal Git clone** because sbt-dynver/JGit cannot initialize inside the linked `reconcile-workflows-routing` worktree (`NoWorkTreeException`), unrelated to source code.

**Release gate to complete:** wait for `workflow-utils v0.3.7` GitHub Actions publisher to finish and independently resolve the Maven Central POM/JAR; then rerun `compiler-rust` Scala frontend and the 45 native tests with **no dependency overrides**, merge compiler-rust's CI/integration branch, compile ProjectManager/workflows with only the published DSL 0.3.6 + workflow-utils 0.3.7, and merge the dependency rollup. Runtime remains native Rust memory-mode; durable journaling is deferred. Next original-branch lifecycle slice: `Summaries` mixin with side-call gate preserved (old unguarded `buildSteps` append would duplicate today's post-turn background Strider and reenter on Sadhaka side calls if ported literally).


### Phase 3.22 — ADT dependency publication and native acceptance complete (2026-11-20)

**Release verified:** `dsl v0.3.6` is available on Maven Central. `workflow-utils v0.3.7` at commit `d7680d3` published successfully via [Actions run 44780839221](https://github.com/awfl-us/workflow-utils/actions/runs/44780839221). Independent requests to Maven Central returned HTTP **200** for both the `workflow-utils_3-0.3.7.pom` and `.jar` (not just the release tag).

**Stable-dependency gates:** `compiler-rust` (branch `test/adt-real-utils-sites`, after native-source audit and rustfmt fix) generated five ADT test fixtures using **only** DSL `0.3.6` and workflow-utils `0.3.7`, *no local overrides*, then compiled and ran 31 matcher + 14 real-utils/malformed-input native Rust cases (**45/45 PASS**). `workflows` dependency-rollup commit `4c78ddf` (DSL `0.3.6`, workflow-utils `0.3.7`, removing local snapshot override) compiled all **58** ProjectManager/workflows Scala sources with no overrides from a fresh ordinary Git clone (**PASS**). Rust `cargo fmt --all -- --check` passed. CI on compiler branch [run 44784091408](https://github.com/awfl-us/compiler-rust/actions/runs/44784091408): `rust` succeeded, `real-workflow-compatibility` succeeded, and the new ADT matcher/real Kala suite passed within `scala-to-native-smoke`; later, the existing `Compile and execute native Parallel and ParallelFor fork/join` step failed on `clippy::let_and_return` in an unrelated generated Parallel fixture. Pre-existing compiler-rust main CI was already red, e.g. [run 44628316791](https://github.com/awfl-us/compiler-rust/actions/runs/44628316791). This remaining generic generated-Rust Clippy issue is a separate follow-up, not a failing Match assertion.

**Integration policy:** merge native ADT tests and compiler CI gate, plus versioned ProjectManager build/dependency updates; leave live production workflow binaries unchanged until a separately verified publish/deploy. Preserve the original `wip-old` structural JSON Match algorithm (with explicit non-exhaustive failure), no made-up subtype discriminator. The next lifecycle slice is the historical `Summaries` / `ExtractTopics` / `ContextCollapser` trait organization: preserve the current `sideCall` guard and avoid double background Strider scheduling; durable mode is intentionally on the back burner for latency.

### Phase 3.23 — background lifecycle mixins recovered; pre-merge checkpoint (2026-11-20)

**Work in progress, not merged or deployed:** `workflows` feature checkout `feat/reconcile-background-lifecycle` (working tree based on `2de413f`; implementation and test files remain uncommitted at this checkpoint). This slice follows the **original `wip-old` agent composition**: `traits/Agent.scala` mixes in `Summaries`, `assistant.ExtractTopics`, and `context.ContextCollapser`. The original source used a separate `buildSteps` override in each trait to append a Strider. That placement is **not safe to copy literally** into current native memory-mode `EventHandler`: it already schedules all three background jobs after the main turn, and typed Sadhaka `sideCall=true` must not start recursive Striders.

**Implemented in the feature working tree:** each recovered trait overrides `protected backgroundStriderSteps`, appending exactly its own step to `super.backgroundStriderSteps`. The one `EventHandler.maybeBackgroundStriders` switch remains the launch boundary after the response/callback/tool handling: when `sideCall=true` it schedules **nothing**, and on an ordinary acquired user turn it runs the three contributed steps **once**, in current order `runSummaries` → `chainBlock` (`ExtractTopics.runSync` → `SaveReflection`) → `collapseMessages_try`. The current `ContextCollapser` 7-minute window/1-minute overlap is preserved, rather than silently applying the different 20-minute/14-minute historical configuration. `SmallStrider` is still deferred. This is structural reconciliation, not a request to change the background functionality or deploy the workflow.

**Local validation passed:** `sbt -batch compile` compiled the five changed Scala sources and 21 dependent sources; `sbt -batch 'Test/runMain us.awfl.workflows.BackgroundLifecycleSmoke'` passed after building the actual `Agent` / `EventHandler`. The smoke fixture inspected the built DSL step tree and asserted (a) precisely three ordered background contributions, with step names `runSummaries`, `chainBlock`, `collapseMessages_try`; (b) exactly one `maybeBackgroundStriders` switch; (c) zero steps in the `sideCall` branch; and (d) exactly the same three steps in the ordinary branch. This checks *DSL construction*, **not** native execution or production behavior. The earlier published-dependency gate remains green: DSL 0.3.6 + workflow-utils 0.3.7, 45/45 native ADT tests and ProjectManager 58-source compile, both already merged to `compiler-rust/main` (`b760f5e`) and `workflows/main` (`2de413f`).

**Remaining gates before merging the mixins:** (1) compare the newly built EventHandler DSL/compiled Rust control-flow structure against `main` and ensure no changes to status, failure callbacks, tool feedback, or event response ordering; (2) regenerate ProjectManager and the three child binaries with `scripts/native-dev.py`, then run deterministic `basic`, `no-query`, `tool-roundtrip` and strict-background tests; (3) run real isolated PostgreSQL `strider-pm`, concurrent Strider, and failure/callback tests before a production publish; (4) record any latency difference relative to the memory-mode baseline. Keep the feature branch separate until those runtime gates pass. Never reintroduce Google Workflows execution or per-effect durable journaling.

**Separate CI follow-up:** [compiler-rust main run 44784715745](https://github.com/awfl-us/compiler-rust/actions/runs/44784715745) had green `rust` and real-workflow-compatibility jobs, but its `scala-to-native-smoke` failed later in the existing generated Parallel fixture with a `clippy::let_and_return` warning under `-D warnings`, after the new ADT native fixtures passed. This is not evidence that the background lifecycle change failed (it is not merged into that compiler run); fix the generator/fixture and rerun independently.

### Phase 3.24 — original background lifecycle mixins, no generated-native delta (2026-11-20)

**Original design reconciled:** The historical `wip-old` `traits/Agent.scala` assembled `EventHandler with Summaries with ExtractTopics with ContextCollapser` (and `Status`/`SmallStrider`). The old background traits unconditionally appended steps in `buildSteps`, which is **not safe on the current native Sadhaka `sideCall=true` path**: it could trigger new background Striders from within the Striders' own typed completion calls. The current `EventHandler` instead owns one `maybeBackgroundStriders` `Switch` inside the lock-acquired response path. That guard and the existing `Status with Recursion` composition must stay in place. `SmallStrider` and per-effect durable journaling remain deferred for latency, as previously decided.

**Selective implementation:** Each `Summaries`, `ExtractTopics`, and `ContextCollapser` mixin now overrides `protected def backgroundStriderSteps` and appends precisely its original work. `Agent` mixes them in that order. The default `EventHandler.backgroundStriderSteps` is empty, and the sole existing `maybeBackgroundStriders` switch still contains an empty `sideCall` branch and a normal branch with all three steps. No unconditional `buildSteps` append was imported. Existing native completion, callback, status, and lock-release code remains untouched. Strider arguments and windows are preserved verbatim: Summaries `runSummaries`; extraction `runSync` chained to `SaveReflection`; collapse `windowSeconds = 60*7`, `overlapSeconds = 60`.

**Validation:** `sbt -batch compile` on the **full 58-source workflow project** PASS. `sbt -batch 'Test/runMain us.awfl.workflows.BackgroundLifecycleSmoke'` PASS: checks one `maybeBackgroundStriders` switch, side-call branch containing **no** background work, normal branch containing **exactly three** steps in the order `runSummaries, chainBlock, collapseMessages_try`, and a real `Agent` mixin composition. Current branch `scripts/native-dev.py codebase-ProjectManager --recursive --compiler <compiler-rust> --output <candidate> --check` **PASS**, 27 native crates and 15 dynamic workflow call sites; root Rust crate `cargo check` PASS. A separate **pre-refactor `workflows/main` checkout at `2de413f`** with the same published DSL 0.3.6 / workflow-utils 0.3.7 and the same compiler generated 27 crates independently. `scripts/verify-native-closure-parity.py <baseline> <candidate>` compared all 27 `Cargo.toml` files and 27 `src/main.rs` outputs byte-for-byte: **54/54 identical**. This is a strict execution-code parity check, not a simulated workflow; it does not require a new live deployment to establish unchanged compiler output.

**Next:** merge this lifecycle-only change after retaining the byte-for-byte parity gate; confirm that existing `publish-projectmanager-native.yml` only reuses verified native artifacts. Do not reintroduce the old unconditional scheduling or import Google Workflows. The separate compiler-rust CI job can still fail in generated Parallel-for Clippy (`let_and_return`) even when Rust unit tests, ADT suite, and workflow compatibility pass. Fix that generic emitter lint in its own compiler change rather than hiding it in workflow mixin reconciliation.

### Phase 3.25 — native publisher classpath release blocker (2026-11-20)

The automatic `workflows/main` lifecycle publish [run 44787963894](https://github.com/awfl-us/workflows/actions/runs/44787963894) **failed before artifact publishing**: `NoClassDefFoundError: us/awfl/dsl/IsAdt` during `ContextCollapser` class initialization. Root cause: the workflow publisher still checked out `compiler-rust` `c7b2bfd`, whose `frontend/build.sbt` overrides transitive `dsl` to `0.3.5`, while `workflows/main` and the validated lifecycle build use `dsl` `0.3.6` (which defines `IsAdt`). Scala workflow compilation preceding the failure succeeded, but the older frontend classloader was incompatible. The publish result is **not** evidence of an activated new native workflow.

Fix under validation: pin the publisher to the already merged and tested `compiler-rust` `b760f5e`, whose frontend declares and overrides `dsl` `0.3.6` and uses `workflow-utils` `0.3.7`; remove the obsolete `install-model-aware-workflow-utils.sh` CI step, which published historical `0.3.6-SNAPSHOT` locally but is no longer necessary. Reconcile `tests/test_native_dev.py`'s hard-coded static target count (12 -> 13) with the current ProjectManager closure, independently of the publisher failure. Acceptance: unit tests, GitHub publish success, active GCS release manifest, then live native ProjectManager smoke with callback/session status checks. **Do not claim deployment or live parity before those checks.** Preserve native Rust PostgreSQL memory-mode priority and defer durable journal/SmallStrider.

### Phase 3.26 — production native ProjectManager publish and smoke verified (2026-11-20)

**Publisher recovered:** `workflows/main` commit `4bc60f0` fixed the stale compiler frontend DSL classpath; [run 44788859554](https://github.com/awfl-us/workflows/actions/runs/44788859554) succeeded. The active GCS manifest `gs://awfl-us-native-workflows/native-workflows/active/codebase-ProjectManager.json` was independently read and reported `runtimeAbi=3`, `protocolVersion=2`, `parallelVersion=1`, operations `http.post`, `log.write`, `native.parallel`, and `workflow.run`, source hash `206ef53af1bcbaa1289b150726a1a7a189fd6ca6029a6524220c253fee23c5b1`.

**Live native PostgreSQL memory-mode smoke:** launched a new `codebase-ProjectManager` session in project `gNoQN9KUw5IbtsXLag0M` with `gpt-5`, $1 fund, and a read-only exact-response prompt. Session `07f411a5-1a57-4853-b6a1-afc29f325e73` emitted assistant message `AWFL_NATIVE_PM_SMOKE_OK` (recorded LLM cost `$0.008975`); the session-info read settled at **Done**, latest execution `native_f7095f97e0206588cb0e82279cac7f43`, `ended=true`, `error=null`. Note that the ProjectManager workflow executed auxiliary background `CliTools` events despite the prompt requesting no tool calls; this test therefore confirms foreground response, native execution, eventual status, and callback/continuation completion, **not** absence of auxiliary effects or a full callback-concurrency/strict background audit. First smoke used model `gpt-6-Terra` and failed specifically because the model API returned HTTP 404; it is not a publisher regression. Future verification should use an accessible model rather than assuming historical model IDs still work.

**Independent compiler CI follow-up:** [compiler-rust PR #44](https://github.com/awfl-us/compiler-rust/pull/44) changes `StateMachineEmitter.laneSnapshot` to emit a direct empty BTreeMap instead of generating `let mut captured; captured` for empty fork lanes, fixing the `clippy::let_and_return` warning in generated Parallel fixtures. Native smoke and Rust tests passed in PR CI; merge only after all required compatibility checks pass. Native memory mode stays the supported execution path; durable journaling and SmallStrider are deferred.

### Phase 3.27 — native lifecycle callback contract coverage (2026-11-20)

**Selective reconciliation:** compared `wip-old` and current `EventHandler`, `Summaries`, `ExtractTopics`, `ContextCollapser`, `helpers/Convo`, and `event/Status` source boundaries before adding new lifecycle tests. The original mixin architecture already landed in Phase 3.24; do not re-copy its older routing/LLM code into current main. Current `EventHandler` is responsible for one session lock and `sideCall` guard; the helper `Convo` remains the context/completion/persistence boundary. Treat additional production changes as evidence-driven, not broad restructuring.

**New automated gate:** [server PR #176](https://github.com/awfl-us/server/pull/176) adds provider-neutral native callback tests for two simultaneous idempotent creates, two simultaneous deliveries, single committed result, conflicting retry rejection, cross-project owner rejection, registered callback lookup, callback delivery in the critical gap between initial read and subscription, initial-snapshot unsubscribe, post-delivery unsubscribe, and timeout unsubscribe. The direct callback suite is **9 passed / 0 failed**; selecting native callback, native integration, and native memory-worker suites yielded **12 passed, 4 fixture-gated skipped, 0 failed**. These are Node tests against a storage contract fixture, **not real PostgreSQL multi-pod evidence**. The legacy Firestore callback test coverage already existed; this closes a provider-neutral regression gap without introducing durable journaling.

**Required remaining gates, in order:**
1. Run `scripts/reconcileMemoryCallbackConcurrency.test.mjs` and `scripts/reconcileMemoryConcurrency.test.mjs` against an isolated loopback PostgreSQL database named `/awfl_reconcile` with `PGTEST_URL` and a freshly compiled `AWFL_NATIVE_LOCAL_BUILD_REPORT` (the tests refuse other database targets). Exercise competing executions across independent store/worker instances or pods to establish true cross-process lease and callback contention, not just a fake transaction queue; verify only one user turn proceeds under lock and eventual success once released.
2. Verify `callback.create`, authenticated POST delivery, `callback.await` and post-completion status in the live memory-mode stack, and document retention/cleanup semantics for persisted callback records. **Do not delete callback records eagerly**, because duplicate-delivery acknowledgements and retries currently depend on their results remaining addressable; agree on expiry/GC requirements before changing retention.
3. Verify `EventHandler` exactly-once Running→Done/Failed lifecycle for regular completion, thrown tool/HTTP errors, budget exhaustion and interrupted callback, including released session lock and one terminal status; read session execution status after background child fan-out. Measure native execution count and latency; do not equate assistant-message appearance with completion.
4. Compare original `wip-old` `EventHandler`/`Convo`/Status composition against current main once these gates are green. Preserve first-party current tooling and memory-mode native Rust execution; no Google Workflows. Investigate context cache stability/summary dedup separately rather than restoring historical sliding-window logic by assumption.

**Boundary:** `SmallStrider`, `SLEEP`, and durable per-effect journaling remain deferred; PostgreSQL native Rust memory mode remains the target. Compiler-rust Parallel Clippy fix is tracked separately in PR #44; do not mark it merged while its duplicate compatibility CI check is failing or pending.

### Phase 3.28 — real PostgreSQL callback and lock integration gates (2026-11-20)

[Server PR #176](https://github.com/awfl-us/server/pull/176) now includes **both** real PostgreSQL 15 callback and session-lock test cases in addition to the 9/9 callback contract tests. [Storage-pilot CI run 44794128306](https://github.com/awfl-us/server/actions/runs/44794128306) completed successfully, including cross-client callback delivery after the subscriber's LISTEN is established, concurrent idempotent callback creation and delivery, duplicate/conflict/owner checks, two separate Express Jobs instances racing to acquire one session lock (exactly one HTTP 200, one 409), loser cannot release winner's lock, and reacquisition after valid release. The tests use independent PostgreSQL connection pools, a loopback-only CI database and random isolated user paths; their added data is removed. Native CI and Terraform gates in that workflow also passed.

**Scope distinction:** These tests exercise real SQL isolation and LISTEN/NOTIFY behavior with independent backend instances, not Kubernetes multi-pod scheduling, executable native ProjectManager child workflows, or cleanup/TTL for callback documents. Do not claim they demonstrate recovery after a pod crash or durable effect journaling. The existing PostgreSQL native-memory ProjectManager callback/lock smoke fixtures must still run with compiled native releases (`AWFL_NATIVE_LOCAL_BUILD_REPORT`) against isolated `/awfl_reconcile`; confirm exactly-once terminal status and callback cleanup/retention policy before declaring full Phase 3.27 lifecycle acceptance. Continue native Rust memory mode; durable and SmallStrider remain deferred.

### Phase 3.29 — compiled ProjectManager native PostgreSQL lifecycle acceptance (2026-11-20)

**Real compiled gate added and verified:** server/main merged [PR #177](https://github.com/awfl-us/server/pull/177) (`d893969`) to compile the current full native ProjectManager dependency closure using the publishing toolchain, **without GCP credentials, artifact publishing, or production modification**, then run the existing native Rust memory-mode fixtures against the isolated PostgreSQL 15 `/awfl_reconcile` database. The initial CI attempt [44795573443](https://github.com/awfl-us/server/actions/runs/44795573443) failed while generating artifacts because a stale compiler revision lacked `us.awfl.dsl.IsAdt`; no PostgreSQL test was executed in that failed run. [PR #178](https://github.com/awfl-us/server/pull/178) (`e21e482`) aligns the compiler checkout with the known successful ProjectManager publisher (`b760f5e`).

**Acceptance evidence:** [corrected CI run 44796201644](https://github.com/awfl-us/server/actions/runs/44796201644) **passed**; `AWFL_NATIVE_DEV_PASS root=codebase-ProjectManager workflows=27 publish=false` (165.9s compile/verify). **9/9 compiled native PostgreSQL tests passed, 0 failed, 0 skipped**: callback lock held across tool persistence and detached continuation; two simultaneous ProjectManager turns with retriable 409 and a successful retry; real Jobs typed HTTP 422 causing a terminal native EventHandler failure; PostgreSQL result persistence and completed replay without duplicated Jobs HTTP; confirmed HTTP 422 terminal replay; SIGKILL of leased worker followed by recovery in a new SQL client with stale epoch fencing; restart after HTTP response with stable effect idempotency key; real scoped Jobs `/firebase` lock fencing; and `get_session` status pagination through 125 newer statusless children. No live model API, production database, or Google Workflows were used.

**Lifecycle transition evidence:** in the callback/feedback fixture, original detached root status was `Running`, blocked contender `Running→Failed` with retriable 409, retried turn `Running→Done`, and 24 detached continuations reported Done. In the simpler concurrent fixture, first and retried turns each recorded `Running→Done`, blocked contender `Running→Failed`. Foreground message roles and exactly-once user-turn persistence were asserted, with no missing replay HTTP effects. A detached root legitimately remains Running when a separate terminal continuation is responsible for Done; do not demand a second terminal update on the original root.

**Remaining acceptance scope:** callback `await` timeout/cancellation and runtime callback document retention/TTL policies still require explicit integration/operability rules; `callback.create` records intentionally remain available for idempotent retries, and prematurely deleting them would break duplicate-delivery behavior. Budget exhaustion and injected exception during callback failure delivery are not yet proven by these nine compiled tests. A CI-hosted multi-client SQL test is not a GKE multi-pod deployment test. Native Rust PostgreSQL **memory mode is unchanged**; no durable per-effect journaling and no `SmallStrider`/`SLEEP` work.

**Next actual wip-old reconciliation:** compare original and current `EventHandler`/`Convo`/`Status` composition and resolve only demonstrated behavioral gaps (especially callback-failure/timeout and budget-exhaustion paths), then revisit the typed tool/schema and context cache/dedup issues. Avoid importing obsolete Google Workflows calls or restructuring already byte-identical Summaries/ExtractTopics/ContextCollapser without a regression.

### Phase 3.30 — original `wip-old` Convo/EventHandler/Status ownership audit (2026-11-20)

Compared `workflows/wip-old` and current main implementations of `helpers/Convo.scala`, `EventHandler.scala` and `event/Status.scala` after the [compiled PostgreSQL acceptance run 44796201644](https://github.com/awfl-us/server/actions/runs/44796201644). Preserve the original composition **by behavior, not by blindly restoring obsolete code**:

- **Conversation boundary:** original `wip-old` `ConvoInput` carried the query and `Convo` attempted `addMessage` before its model call; current `EventHandler` acquires the session-scoped lock and persists the initial user turn before invoking native `helpers-Convo`. This ordering is intentional: under lock contention a rejected turn must not be persisted. The compiled PostgreSQL tests confirm one 409 failure, one later successful retry, and no unanswered/duplicate user turn. `helpers-Convo` still owns context construction, completion and assistant persistence; query-free tool continuations must not synthesize new user messages.
- **Native completion and typed routing:** current `helpers-Convo` returns `ChatToolResponse` rather than the original `ChatResponse[ChatMessage]` to preserve tool-call and usage information; it accepts the actual model-visible `ToolChoice` and optional side-call context. `EventHandler` still owns executable-tool dispatch, callback completion and the `sideCall` restrictions (no lock acquisition, stored assistant, recursive tool feedback or new background Striders for typed Sadhaka calls). The old Google Workflows invocation must not return.
- **Status/failure ownership:** original `Status` wraps the workflow with a best-effort Running event, Failed on exception, and Done at `stop`; current `Status` keeps this structure and adds `beforeStatusFailure`, used by `EventHandler` to release its owner-scoped session lock **before** recording failure and delivering the failure callback. The native core owns the final rethrow. Tool-feedback roots can legitimately emit only Running, while their separate terminal continuation emits Done. The compiled fixture's exact per-execution Running/Done/Failed transition assertions passed; no broad status rewrite is justified.

**Disposition:** do not reintroduce the original helper's pre-lock user-message write, earlier `OptBase[Tool]` shape or Google Workflows execution. No source refactor made at this checkpoint. Next targeted checks: budget exhaustion, failed callback delivery and await timeout/cancellation, and callback record retention/expiry design. Keep durable journaling and SmallStrider deferred; native Rust memory mode remains the target.
