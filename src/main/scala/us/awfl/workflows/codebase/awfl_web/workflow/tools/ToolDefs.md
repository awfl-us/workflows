# Tool discovery (ToolDefs) and tools/list service

Overview
- ToolDefs is a small helper workflow that assembles the tool definitions exposed to the model.
- It fetches tool defs from the tools service first, and falls back to local Tools.defs when the service is unavailable.
- The service returns an LLM-compatible array of Tool objects: [{ type: 'function', function: { name, description, parameters } }].

Routes and auth
- Client route: GET /workflows/tools/list
- Jobs route: GET /jobs/tools/list (reuses the same implementation with workflow-scoped auth)
- Scala Http helpers automatically prefix /jobs, so prefer us.awfl.utils.get("tools/list?…") from us.awfl.workflows.

Usage from Scala (service-first with fallback)
- ToolDefs.scala calls: get[List[services.Llm.Tool]]("tools/list?names=…").
- In CliEventHandler, provide an optional Input.toolNames (CSV or JSON array) to limit the surfaced tools.
- Example:
  val toolDefs = ToolDefs("toolDefs", sessionId, str("READ_FILE,UPDATE_FILE"))

Response shape
- JSON array of LLM tools, OpenAI-style:
  [
    {
      "type": "function",
      "function": {
        "name": "READ_FILE",
        "description": "Read a file from disk",
        "parameters": {
          "type": "object",
          "properties": { "filepath": { "type": "string" } },
          "required": ["filepath"]
        }
      }
    }
  ]
- The service normalizes parameters to { type: 'object', properties, required }.

Filtering by name
- Query parameters supported:
  - names=foo,bar (CSV)
  - names[]=foo&names[]=bar (repeated)
  - name=foo (single)
  - only=foo (alias)
- Filtering is exact and case-sensitive; only listed tool names will be returned.

Sources and precedence
- User-scoped Firestore: users/{userId}/tools/defs
- Global Firestore: tools/defs/items
- Local files: functions/workflows/tools/defs/*.json
- Precedence and merge order: user -> global -> files, de-duplicated by name.

Fallback behavior
- If GET tools/list fails, ToolDefs falls back to local Tools.defs (standard + Tasks family) so the model still has working tools.
- Keep work idempotent; prefer upsert patterns when writing definitions.

Guardrails
- function.name must match Tools.Runner.name exactly.
- Provide a short unsupported-tool message for unknown calls (e.g., "Unsupported tool: ${name}").

Integration points
- CliEventHandler
  - Input.toolNames: optional CSV/JSON array to limit tool exposure per request.
  - Uses ToolDefs to provide the final tool list to Convo.completeWithTools.
- ToolDispatcher
  - Executes tool calls by matching function.name to registered Tools.Runner.
- Tools.Runner
  - Use Tools.invokerRunner to expose workflow-backed tools; register in CliEventHandler runners.

Examples
- Limit exposed tools for a session:
  CliEventHandler.Input(toolNames = str("READ_FILE,UPDATE_FILE,RUN_COMMAND"))
- Service call constructed by ToolDefs (helpers add /jobs):
  GET /jobs/tools/list?names=READ_FILE,UPDATE_FILE
