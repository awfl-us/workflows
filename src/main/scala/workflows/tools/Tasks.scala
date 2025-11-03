package workflows.tools

import dsl.*
import dsl.CelOps.*
import dsl.auto.given
import ista.ToolCall
import workflows.EventHandler
import workflows.helpers.tasks.TaskRunners
import workflows.traits.ToolWorkflow

/**
 * tools/Tasks
 * - Accepts CliTools.ToolWorkflowInput and executes CREATE_TASK or UPDATE_TASK via TaskRunners.
 * - Returns CliTools.ToolWorkflowResult with the operation's message as the encoded string.
 * - Keeps response format minimal and stable for ToolDispatcher usage.
 */
object Tasks extends workflows.traits.ToolWorkflow {
  val supported = List("CREATE_TASK", "UPDATE_TASK")

  override def workflows = List({
    val toolCall = input.tool_call
    val cost     = input.cost

    val nameV: BaseValue[String] = toolCall.get.function.get.name
    val nameStr: Cel = CelFunc("string", nameV.cel)

    // Build branches
    val (createSteps, createMsg) = TaskRunners.runCreate(toolCall = toolCall )
    val runCreateBlock = Block("run_create_task", createSteps -> createMsg)

    val (updateSteps, updateMsg) = TaskRunners.runUpdate(toolCall)
    val runUpdateBlock = Block("run_update_task", updateSteps -> updateMsg)

    val unsupportedMsg = str((("Unsupported tool: ": Cel) + nameStr))

    val sw = Switch("select_task_tool", List(
      (nameStr === ("CREATE_TASK": Cel)) -> { List(runCreateBlock) -> runCreateBlock.resultValue },
      (nameStr === ("UPDATE_TASK": Cel)) -> { List(runUpdateBlock) -> runUpdateBlock.resultValue },
      (true: Cel) -> { List() -> unsupportedMsg }
    ))

    Workflow(List(sw) -> obj(ToolWorkflow.Result(sw.resultValue, Value(0))))
  })

  // Helper to invoke this workflow from elsewhere
  def apply(
    name: String,
    toolCall: BaseValue[ToolCall],
    cost: BaseValue[Double],
  ): Call[RunWorkflowArgs[ToolWorkflow.Input], ToolWorkflow.Result] = {
    val args = RunWorkflowArgs(str("tools-Tasks${WORKFLOW_ENV}"), obj(ToolWorkflow.Input(toolCall, cost)))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }
}
