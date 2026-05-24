package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.utils.Env
import us.awfl.ista.ChatMessage
import us.awfl.workflows.helpers.ToolDefs._
import us.awfl.services.Llm.ToolChoice
import us.awfl.workflows.tools.Tasks.Task

trait Agent extends us.awfl.core.Workflow with EventHandler with Preloads with Tasks with Cli with Funds {
  override type Result = ChatToolResponse

  // Use a single JSON config at .awfl/config.json; avoid temp files. Keep each command < 400 chars.
  override def preloads: List[PreloadItem] = List(
    // Consolidated preload script (excludes the session files listing)
    PreloadScript("agent.sh"),

    // Session-scoped listing as a separate preload step (uses live sessionId)
    PreloadCommand(
      CelStr("sh -lc \"echo; echo 'Files uploaded/specific to the current session:'; ls -la sessions/").safe +
      Env.sessionId.cel +
      CelStr(" 2>/dev/null || echo 'No session directory for this sessionId'\"").safe
    )
  )

  def apply(
    name: String,
    query: Value[String],
    fund: Value[Double],
    spent: Value[Double] = Value(0),
    task: OptBase[Task] = OptValue.nil,
    toolChoice: OptBase[ToolChoice] = OptValue.nil[ToolChoice],
    env: BaseValue[Env] = obj(Env.get.copy(sessionId = str(workflowName)))
  ): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    execute(workflowName, obj(EventHandler.Input(query, fund, OptValue(spent), task = task, toolChoice = toolChoice, env = env)))
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
