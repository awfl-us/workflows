# Tasks tools (CREATE_TASK, UPDATE_TASK)

Overview
- These tools let agents create and update user-scoped tasks via the /jobs/tasks HTTP endpoints.
- They are registered by default in CliEventHandler so any agent can use them without extra wiring.

Runners
- CREATE_TASK
  - Description: Create a task in the current user's scope. Session is inferred from the current CLI workflow session; the agent should not provide it.
  - Params: { title?: string, description?: string, status?: string }
  - Status must be one of: Queued | In Progress | Done | Stuck (validated server-side).
  - Returns: a chat message summarizing the created task.

- UPDATE_TASK
  - Description: Update a task by id in the current user's scope. Session is inferred from the current CLI workflow session; the agent should not provide it.
  - Params: { id: string, title?: string, description?: string, status?: string }
  - Returns: a chat message summarizing the updated task.

Behavior notes
- Function.name must match runner name exactly (CREATE_TASK, UPDATE_TASK).
- On success, the runner posts a system message containing a one-line summary of the task.
- On failure, the runner posts a short error message: "Task create failed: …" or "Task update failed: …".

Examples
- CREATE_TASK
  - { "tool_call": { "function": { "name": "CREATE_TASK", "arguments": { "title": "Draft spec", "status": "Queued" }}}}
- UPDATE_TASK
  - { "tool_call": { "function": { "name": "UPDATE_TASK", "arguments": { "id": "taskId1", "status": "In Progress" }}}}

Implementation
- Scala source: workflows/src/main/scala/workflows/helpers/Tasks.scala
- CLI integration: CliEventHandler pre-registers Tasks.runners along with standard tools.
- HTTP backing: functions/tasks.common.js

Idempotency and safety
- CREATE_TASK uses POST to /jobs/tasks; server assigns id and timestamps.
- UPDATE_TASK uses PATCH to /jobs/tasks/:id; only provided fields are updated.
- All calls are user-scoped via OIDC audience BASE_URL/jobs and the server-side auth middleware.

Auto-promotion behavior
- When UPDATE_TASK sets a task's status to Done, TaskRunners will try to promote work:
  - If there is no In Progress task for the session, it fetches the oldest Queued task (limit=1, order=asc) and updates it to In Progress.
  - If an In Progress task already exists, or if there are no queued tasks, no promotion occurs.
- This runs only on Done updates and is best-effort; failures are surfaced as short system messages.
