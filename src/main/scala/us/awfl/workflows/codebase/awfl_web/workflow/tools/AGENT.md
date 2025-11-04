ToolBuilder Agent Guide

Scope
- Location: workflows/src/main/scala/workflows/codebase/workflows/ToolBuilder.scala (agent)
- Reads contextual docs under awfl_web/workflow/tools first.

Role
- Design, scaffold, and evolve awfl tool/workflow utilities and their CLI integration.
- Define tools via ToolDefs JSON and ensure they are discoverable from the tools service, then executed by the workflow named in workflowName.
- Favor idempotent operations, environment isolation, and proper locking semantics (see AGENT.md in workflows agent for patterns).

How tools work now
- Definition and discovery
  - Tools are defined in ToolDefs (Firestore: users/{userId}/tools/defs and tools/defs/items; file fallback: functions/workflows/tools/defs/*.json).
  - Discovery endpoint returns a normalized list of tools:
    - GET /jobs/tools/list (workflow-scoped) and GET /workflows/tools/list (app-scoped)
    - Response: { items: [ { type: 'function', function: { name, description, parameters }, workflowName } ] }
    - parameters are normalized to: { type: 'object', properties, required }
    - Names filtering (exact, case-sensitive): names=foo,bar | names[]=foo&names[]=bar | name=foo | only=foo
  - Do not change the response envelope or item shape; workflowName must be present on each item.

- Routing and execution
  - Each tool item specifies workflowName. ToolDispatcher invokes that workflow with CliTools.ToolWorkflowInput and expects a CliTools.ToolWorkflowResult (typically a concise message).
  - Pattern A: Specific workflow per tool family
    - Example: workflowName "tools-Tasks" handles CREATE_TASK and UPDATE_TASK.
    - The workflow routes on function.name (e.g., "CREATE_TASK") and executes helpers; unknown names must return a short message: "Unsupported tool: <name>".
  - Pattern B: Generic agent proxy
    - workflowName "tools-Agent" invokes the workflow whose name equals function.name (include any ${WORKFLOW_ENV} suffix).
    - ToolDefs entry example:
      {
        "type": "function",
        "function": {
          "name": "codebase-workflows-ToolBuilder",
          "description": "Run ToolBuilder with a query (via Agent)",
          "parameters": {
            "type": "object",
            "properties": { "query": { "type": "string" }, "task": { "type": "object" } },
            "required": ["query"]
          }
        },
        "workflowName": "tools-Agent"
      }

Guardrails
- function.name must be an exact, case-sensitive match:
  - Pattern A (family workflow): match the routed tool name (e.g., CREATE_TASK, UPDATE_TASK).
  - Pattern B (Agent proxy): match the target workflow’s deployed name (include ${WORKFLOW_ENV} if used).
- workflowName is required on each ToolDefs item; keep outputs idempotent and deterministic.
- Provide short unsupported-tool messages for unknown calls: "Unsupported tool: ${name}".
- Keep work idempotent; prefer upsert patterns and defensive checks. Use environment isolation and avoid side effects outside allowed paths.

Preloads
- Cat and include all files under awfl_web/workflow/tools in the system prompt.
- Optionally include diagnostics (yaml_gens listing, UTC time).

Conventions
- Place ToolDefs under functions/workflows/tools/defs; prefer one file per tool family.
- Use names filtering to limit surfaced tools per session in CliEventHandler.
- Respect ${WORKFLOW_ENV} for workflow names at invocation and discovery time.
- Keep Tools.list contract stable: do not alter envelope, item keys, parameter schema, or ordering.

Examples
- Tasks ToolDefs (excerpt):
  {
    "type": "function",
    "function": {
      "name": "CREATE_TASK",
      "description": "Create a task",
      "parameters": {
        "type": "object",
        "properties": { "title": { "type": "string" }, "description": { "type": "string" }, "status": { "type": "string", "enum": ["Queued","In Progress","Done","Stuck"] } },
        "required": []
      }
    },
    "workflowName": "tools-Tasks"
  }

Next steps
- Add tool-specific guides in this directory (one file per tool or family) describing expected inputs/outputs and edge cases.
- When adding tools, verify discovery via tools/list with names filtering, and smoke-test routing via the target workflow.
- Consider schema validation for ToolDefs (pre-commit/CI) and snapshot tests to prevent response shape drift.