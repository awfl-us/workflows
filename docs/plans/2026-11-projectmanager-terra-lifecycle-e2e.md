# ProjectManager GPT-6.1 Terra: production E2E and lifecycle acceptance — 2026-11-20

## Scope and execution mode

Live production AWFL project `gNoQN9KUw5IbtsXLag0M` (`awfl-us/workflows`), compiled native Rust Workflows, PostgreSQL document storage, **memory-mode** execution. Every accepted ProjectManager run used the exact model ID **`gpt-6.1-terra`**. The smoke instructions prohibited repository edits, commits, deployments and subagent creation. These were normal session-agent workflow launches/continuations, not local mocks. The separate Scala/Rust/Postgres compiled CI gate remains [server run 44796201644](https://github.com/awfl-us/server/actions/runs/44796201644) (9/9 lifecycle tests). Durable execution is not enabled or being restored.

## Two production regressions found and corrected

1. **Tools list / enum interop:** A real `gpt-5.2` diagnostic request for `READ_FILE` initially failed HTTP 422. `workflows/tools/index.js` supplied synthetic `enum: []` to ordinary tool fields, while `jobs/llm/typedToolArguments.js` rejected the keyword. The exact valid `{filepath:'README.md'}` call failed the local contract test. [Server PR #179](https://github.com/awfl-us/server/pull/179), `d3ba024`, removes invented empty enums and validates genuine enum constraints. [CI 44804013588](https://github.com/awfl-us/server/actions/runs/44804013588) and [production deploy 44804158466](https://github.com/awfl-us/server/actions/runs/44804158466) passed. **This was necessary but insufficient** for the full compiled workflow.
2. **Provider-visible schema mismatch:** The first live tool-requested `gpt-6.1-terra` run after PR #179 still returned HTTP 422. Legacy Scala `Llm.ToolDefProperty` has optional metadata serialized as `enum:null`, `items:null`, `required:null`. Jobs already ran `stripNullsDeep()` on provider request params, but validated the returned tool call against the *original unstripped* tool definitions. A valid `READ_FILE` call was rejected with `Invalid enum schema at $` when checked against raw definitions and accepted against provider-visible definitions. [Server PR #180](https://github.com/awfl-us/server/pull/180), `854d0ca`, now validates with the **same normalized `params.tools` sent to OpenAI**. Its mocked real HTTP route tests verify both valid legacy metadata acceptance and strict rejection of invalid values. Safe path-only validation diagnostics were added; raw tool arguments are never logged. [CI 44805716836](https://github.com/awfl-us/server/actions/runs/44805716836) and [production deploy 44805855526](https://github.com/awfl-us/server/actions/runs/44805855526) passed.

The initial incorrectly specified `gpt-6-Terra` model ID returned provider HTTP 404. The actual required identifier is **`gpt-6.1-terra`**, confirmed by successful real production runs.

## End-to-end live acceptance: real file tools and continuations

- Session `a8ad5e08-c53b-46c8-b0b5-3d7949dd00ec`; initial run `native_95400afe8ded428b9599dacf6c12b37b`; final continuation `native_782e2548d226fdc769c07075c396f0a9`, **Done**.
- Terra produced two typed `READ_FILE` tool calls and Jobs accepted their JSON without 422. Tool callbacks and tool messages were persisted. The initial `README.md` and plan-relative paths returned `File not found or not regular` because the attached consumer's working directory was `/workspace` rather than the checked-out repository; the agent recovered using `LIST_DIRECTORY`, `SEARCH_FILES`, and targeted `READ_FILE` calls under `workflows-lifecycle/`.
- Successfully read actual `workflows-lifecycle/README.md` and `workflows-lifecycle/docs/plans/2026-11-wip-old-integration.md`. Verified first README heading `# AWFL Workflows (Scala)` and existence of the plan. Final assistant reply ends `AWFL_PM_TERRA_TOOL_E2E_OK`. This covers native PM → helpers-Convo → actual LLM → typed tool dispatch → external tool callback → persisted tool feedback → follow-on PM execution → persisted final assistant reply.
- **Follow-up:** configure/advertise an appropriate default project workdir so the first relative tool read succeeds. This did not prevent completion and is a distinct UX/workspace mapping improvement, not a typed schema regression.

## Aged-session background lifecycle acceptance

- Reused ProjectManager test session `07f411a5-1a57-4853-b6a1-afc29f325e73`, added a read-only `gpt-6.1-terra` turn at `2026-11-20T19:58:20Z`. Initial execution `native_9f2903a8d9074c24b4c6d96581957fa5`; final session status **Done** at `native_c180892c16440dcabb65d44c29c0d8d7`; assistant replied `AWFL_PM_TERRA_AGED_SEGMENT_OK`.
- Background launches followed at approximately `19:58:25Z`. Exact native roots and matched **workflow starts/completions/failures** from GCP production logs:

| Background root | Pipeline observed | Started | Completed | Failed |
| --- | --- | ---: | ---: | ---: |
| `native_ad1bc267882ddb7f7707e8443e069106` | `Summaries`, `-All`, `-Convo`, `-Session`, `-Week`, `-Term` (including nested PM/Sadhaka/CLI helpers) | 26 | 26 | 0 |
| `native_eef6284ed4234f4b9876d1dc92947f27` | `assistant-ExtractTopics`, `-All`, `-Convo`, `-Session`, `-Week`, `-Term`, `helpers-links-SaveReflection` (and nested helpers) | 28 | 28 | 0 |
| `native_a56cc543ae0d203f25c270009602819c` | `context-ContextCollapser-SegKala` (and nested helpers) | 11 | 11 | 0 |

All three roots had completed native HTTP, callback, and workflow effects without logged failures. The session title subsequently changed to `Aged-segment smoke test`, consistent with the summaries' post-write title update. **Caveat:** individual summary/reflection/collapse row contents were not independently retrieved from PostgreSQL, so the evidence is workflow/effect completion plus observed session metadata, not a row-by-row output-data audit. The three detached roots are independent of the initial PM root in logs due `skip_polling`.

## Historical failures versus new regressions

The 24-hour log scan also found 13 completed `Summaries` roots, 12 completed `assistant-ExtractTopics-Convo` runs and 12 completed `context-ContextCollapser-SegKala` runs. Two **earlier** failures were at `2026-11-20T04:17:14Z` (`ExtractTopics-Convo`: typed tool 422, corrected above) and `05:06:06Z` (`ExtractTopics-Session`: `invalid authenticated native log effect`). Neither recurred in the three newest accepted background roots. A live stress/replay test for the prior authenticated log-effect error, if desired, is separate from this green E2E checkpoint.

## Remaining targeted lifecycle acceptance (not claimed complete)

Explicit budget-exhaustion behavior, callback delivery failures, timeout/cancellation, callback retention/TTL, and independent verification of each persisted reflection/summary/collapse document. Keep context summarization **performance/dedup window tuning** and OpenAI prompt-cache stability outside `wip-old` reconciliation, per scope decision. Continue native Rust + PostgreSQL memory mode; durable effects/journaling, `SmallStrider`, and `SLEEP` stay deferred.
