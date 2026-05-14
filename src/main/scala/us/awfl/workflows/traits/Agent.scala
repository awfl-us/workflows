package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.utils.Env
import us.awfl.ista.ChatMessage

trait Agent extends us.awfl.core.Workflow with EventHandler with Preloads with Tasks with Cli with Funds {
  override type Result = ChatToolResponse

  // Keep commands short and simple; no Python. Ensure default config, then parse with sed/tr.
  override def preloads: List[PreloadItem] = List(
    // 1) Ensure default .agent/config.json with sessionId injected (kept well under 400 chars)
    new PreloadCommand(
      CelStr("sh -lc \"[ -f .agent/config.json ] || { mkdir -p .agent; printf '%s\\n' '{\\\"files\\\":[\\\"AGENT.md\\\"],\\\"commands\\\":[\\\"echo Files uploaded/specific to the current session:\\\",\\\"ls -la sessions/").safe +
      Env.sessionId.cel +
      CelStr("\\\"]}' > .agent/config.json; }\"").safe
    ),
    // 2) Flatten JSON to a single line to make sed robust across pretty-printed files
    PreloadCommand(
      CelStr("sh -lc \"tr -d '\\n' < .agent/config.json > .agent/config.min\"").safe
    ),
    // 3) Extract files -> .agent/files.list
    PreloadCommand(
      CelStr("sh -lc \"sed -n 's/.*\\\"files\\\"[^[]*\\[\\(.*\\)\\].*/\\1/p' .agent/config.min | sed 's/\\\",\\\"/\\n/g' | tr -d '[]\\\"' > .agent/files.list\"").safe
    ),
    // 4) Emit files
    PreloadCommand(
      CelStr("sh -lc \"while IFS= read -r f; do [ -n \\\"$f\\\" ] || continue; echo; echo \\\"[Preload file: $f]\\\"; [ -f \\\"$f\\\" ] && cat \\\"$f\\\" || echo \\\"Missing $f\\\"; done < .agent/files.list\"").safe
    ),
    // 5) Extract commands -> .agent/commands.list
    PreloadCommand(
      CelStr("sh -lc \"sed -n 's/.*\\\"commands\\\"[^[]*\\[\\(.*\\)\\].*/\\1/p' .agent/config.min | sed 's/\\\",\\\"/\\n/g' | tr -d '[]\\\"' > .agent/commands.list\"").safe
    ),
    // 6) Run commands
    PreloadCommand(
      CelStr("sh -lc \"while IFS= read -r c; do [ -n \\\"$c\\\" ] || continue; echo; echo \\\"[Preload command: $c]\\\"; bash -lc \\\"$c\\\"; done < .agent/commands.list\"").safe
    )
  )

  def apply(name: String, query: Value[String], fund: Value[Double], spent: Value[Double]): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    execute(workflowName, obj(EventHandler.Input(query, fund, OptValue(spent), env = obj(Env.get.copy(sessionId = str(workflowName))))))
  }

  override def workflows = eventHandler() :: super.workflows
}
