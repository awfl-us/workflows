# Native Rust Project Manager tool-coverage test — 2026-11-13

This was a local end-to-end run of the Scala-defined `codebase-ProjectManager` and its compiled Rust dependency closure (26 executables). The existing GKE Scala workspace hosted the actual Jobs Express app on localhost, the real CLI consumer, GCS and Firestore. No production deployment or IAM change was made.

## Single-session sequence

The Project Manager used all 11 advertised functions through **14 dependent tool calls**, each with a persisted tool result, then produced a final response.

| Function | Actual evidence |
| --- | --- |
| `LIST_DIRECTORY` | Listed empty disposable test directory |
| `UPDATE_FILE` | Created `check.txt` with `alpha` |
| `READ_FILE` | Read the expected `alpha\n` |
| `APPLY_PATCH` | Added `beta`, response `ok=true` |
| `SEARCH_FILES` | Matched `beta` on line 2 |
| `RUN_COMMAND` | Printed file, started interactive process and a cancellable process |
| `COMMAND_OUTPUT` | Retrieved `INPUT_READY` and then `RECEIVED:hello-from-pm` using cursors |
| `COMMAND_INPUT` | Delivered input to the real returned process ID |
| `COMMAND_CANCEL` | Returned `state=cancelled` and `exit_code=-15` |
| `CREATE_TASK` | Created scoped test task, returning an ID |
| `UPDATE_TASK` | Used that same ID and verified status `Done` |

Independent verification from persisted session messages: **11/11 functions, 14/14 matched tool responses, no tool-result errors, and a final agent answer**.

Timing (wall clock): **306,575 ms** from the test runner, including setup and detached drain. Initial user message to final assistant message was **304,557 ms**; first message to initial tool call 23,448 ms, all tool-call-to-result intervals combined 20,294 ms, successive result-to-next-call intervals 241,551 ms, final result to final answer 19,264 ms. These are measured intervals, not an attribution of individual processing stages to the model versus Jobs or Rust.

## Scala task-tool issue

The first test reached all nine CLI functions and then failed on `CREATE_TASK`. Running the compiled `tools-Tasks` binary independently reproduced `string() requires an integer, double or boolean` before any HTTP request. The Scala task definitions incorrectly applied Google Workflows' `core.string` to values that were *already strings*. The native runtime was correct; the source workflow was not.

Corrected `tools/Tasks.scala`, `helpers/Tasks.scala`, and `helpers/tasks/TaskRunners.scala`: use string values directly, default nullable labels, JSON-encode diagnostic objects; retain `core.string` only for numeric timestamps. Added regression tests.

After recompilation, the native `tools-Tasks` workflow created a task in 177 ms and updated it in 362 ms against the actual local Jobs server. The full 11-tool Project Manager retest passed both task tools, including ID carryover across separate asynchronous iterations.

## Separate remaining issues

The run recorded **83 non-2xx Jobs requests** outside the 14 successful tool-result exchanges. Of 59 detached executions, 14 Project Manager continuations completed; 15 `Summaries` and 15 `helpers-Chain` remained waiting, along with 14 waiting and one failed `context-ContextCollapser-SegKala` execution. Those background workflow/lock issues should be investigated independently. They were **not** counted as successful.

This establishes local compiled Rust multi-step tool integration, **not** complete background-workflow convergence or actual GKE-worker-restart recovery.
