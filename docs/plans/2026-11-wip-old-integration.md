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
