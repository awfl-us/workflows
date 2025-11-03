package workflows.helpers

import dsl.*
import dsl.CelOps.*
import dsl.auto.given
import ista.ToolCall
import ista.ChatMessage
import utils.{Convo, KalaVibhaga, SegKala}
import workflows.tools.Tools
import workflows.EventHandler
import workflows.helpers.ToolDefs.ToolWithWorkflow
import utils.Env
import workflows.traits.ToolWorkflow

/**
 * ToolDispatcher
 * - Extracts the tool-call dispatch loop from EventHandler into a reusable helper.
 * - Provides both a builder method (process) and a standalone workflow wrapper (apply/workflow).
 */
object ToolDispatcher extends core.Workflow {
  case class Input(
    totalCost: BaseValue[Double],
    toolCalls: ListValue[ToolCall],
    toolDefs: ListValue[ToolWithWorkflow],
    env: BaseValue[utils.Env] = utils.ENV
  )
  case class Result(results: ListValue[ToolWorkflow.Result])

  override val inputVal = init[Input]("input")

  // Standalone workflow (uses the default runner set: standard + Tasks)
  def apply(name: String, totalCost: BaseValue[Double], toolCalls: ListValue[ToolCall], toolDefs: ListValue[ToolWithWorkflow]): Call[RunWorkflowArgs[Input], Result] = {
    val args = RunWorkflowArgs(str("helpers-ToolDispatcher${WORKFLOW_ENV}"), obj(Input(totalCost, toolCalls, toolDefs)))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }

  override def workflows = List({
    // val allRunners = Tools.all(Tasks.runners)
    val dispatch = For("forToolCall", input.toolCalls) { toolCall =>
      val function = toolCall.get.function.get
      val functionName = function.name

      val toolFold = Fold("fold", Value.nil[ToolWorkflow.Result], input.toolDefs) { case (b, d) =>
        Switch("matchFunction", List(
          (functionName === d.get.function.name) -> {
            val args = RunWorkflowArgs(str(d.get.workflowName + "${WORKFLOW_ENV}"), obj(ToolWorkflow.Input(toolCall, input.totalCost)))
            Call[RunWorkflowArgs[ToolWorkflow.Input], ToolWorkflow.Result]("callToolWorkflow", "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args)).fn
          },
          (true: Cel) -> (List() -> b)
        )).fn
      }

      val saveResult = {
        given KalaVibhaga = SegKala(Env.sessionId, Value("sys.now()"), Value(0))
        Convo.addMessage(
          "saveResult",
          obj(ChatMessage(str("tool"), toolFold.result.encoded, tool_call_id = toolCall.flatMap(_.id)))
        )
      }

      List[Step[_, _]](toolFold, saveResult) -> toolFold.resultValue
    }

    Workflow(buildSteps[Result](List(dispatch) -> obj(Result(dispatch.resultValue)), _ => List() -> Value.nil))
  })
}
