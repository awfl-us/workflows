package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.utils.Env
import us.awfl.ista.ChatMessage
import us.awfl.workflows.helpers.ToolDefs._

trait Agent extends us.awfl.core.Workflow with EventHandler with Preloads with Tasks with Cli with Funds {
  override type Result = ChatToolResponse

  // Use a single JSON config at .awfl/config.json; avoid temp files. Keep each command < 400 chars.
  override def preloads: List[PreloadItem] = List(
    // 1) Ensure default .awfl/config.json (pretty-printed, no session-dependent commands here)
    PreloadCommand(
      CelStr(
        "sh -lc \"[ -f .awfl/config.json ] || { mkdir -p .awfl; printf '%s\\n' '{' '  \\\"files\\\": [' '    \\\"AGENT.md\\\"' '  ],' '  \\\"commands\\\": []' '}' > .awfl/config.json; }\""
      ).safe
    ),

    // 2) Emit files listed in .awfl/config.json (robust to missing/empty key)
    // Use single-quoted -c to prevent outer $ expansion; embed single-quoted jq program with the '"'"' pattern
    PreloadCommand(
      CelStr(
        "sh -lc 'jq -r '\"'\"'(.files? // [])[]'\"'\"' .awfl/config.json | while IFS= read -r f; do [ -n \"$f\" ] || continue; echo; echo \"[Preload file: $f]\"; [ -f \"$f\" ] && cat \"$f\" || echo \"Missing $f\"; done'"
      ).safe
    ),

    // 3) Run generic (non-session) commands from .awfl/config.json (robust to missing/empty key)
    PreloadCommand(
      CelStr(
        "sh -lc 'jq -r '\"'\"'(.commands? // [])[]'\"'\"' .awfl/config.json | while IFS= read -r c; do [ -n \"$c\" ] || continue; echo; echo \"[Preload command: $c]\"; bash -lc \"$c\"; done'"
      ).safe
    ),

    // 4) Session-scoped listing as a separate preload step (uses live sessionId)
    PreloadCommand(
      CelStr("sh -lc \"echo; echo 'Files uploaded/specific to the current session:'; ls -la sessions/").safe +
      Env.sessionId.cel +
      CelStr(" 2>/dev/null || echo 'No session directory for this sessionId'\"").safe
    )
  )

  def apply(name: String, query: Value[String], fund: Value[Double], spent: Value[Double]): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    execute(workflowName, obj(EventHandler.Input(query, fund, OptValue(spent), env = obj(Env.get.copy(sessionId = str(workflowName))))))
  }

  val toolParams = ToolDefObj(
    properties = Map(
      "query" -> ToolDefStr,
      "task" -> ToolDefObj(
        properties = Map(
          "title" -> ToolDefStr,
          "description" -> ToolDefStr,
          "status" -> ToolDefEnum(ListValue(List("Queued", "In Progress", "Done", "Stuck").cel))
        ),
        required = ListValue.empty
      )
    ),
    required = ListValue(List("query").cel)
  )

  override def workflows = eventHandler() :: super.workflows
}
