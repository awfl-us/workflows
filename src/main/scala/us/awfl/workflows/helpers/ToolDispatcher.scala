package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ToolCall
import us.awfl.ista.ChatMessage
import us.awfl.utils.{Convo, KalaVibhaga, SegKala}
import us.awfl.workflows.EventHandler
import us.awfl.workflows.helpers.ToolDefs.ToolWithWorkflow
import us.awfl.utils.Env
import us.awfl.workflows.traits.ToolWorkflow

/**
 * ToolDispatcher
 * - Extracts the tool-call dispatch loop from EventHandler into a reusable helper.
 * - Provides both a builder method (process) and a standalone workflow wrapper (apply/workflow).
 */
object ToolDispatcher extends us.awfl.core.Workflow {
  case class Input(
    totalCost: BaseValue[Double],
    toolCalls: ListValue[ToolCall],
    toolDefs: ListValue[ToolWithWorkflow],
    env: BaseValue[us.awfl.utils.Env] = us.awfl.utils.ENV
  )
  case class Result(results: ListValue[ToolWorkflow.Result])
  private case class MatchedResults(results: ListValue[ToolWorkflow.Result])

  override val inputVal = init[Input]("input")

  // Standalone workflow (uses the default runner set: standard + Tasks)
  def apply(name: String, totalCost: BaseValue[Double], toolCalls: ListValue[ToolCall], toolDefs: ListValue[ToolWithWorkflow]): Call[RunWorkflowArgs[Input], Result] = {
    val args = RunWorkflowArgs(str("helpers-ToolDispatcher${WORKFLOW_ENV}"), obj(Input(totalCost, toolCalls, toolDefs)))
    Call(name, CallOperations.WorkflowRun, obj(args))
  }

  override def workflows = List({
    // Match only executable registrations. Typed output functions may be
    // exposed to the model without a backing workflow, and must not create
    // synthetic tool results (the original wip-old ToolDispatcher behavior).
    val dispatch = Fold("dispatchRegisteredTools", obj(MatchedResults(ListValue.empty[ToolWorkflow.Result])), input.toolCalls) { (results, toolCall) =>
      val function = toolCall.flatMap(_.function).get
      val functionName = function.name

      val toolFold = Fold("findExecutableTool", Value.nil[ToolWorkflow.Result], input.toolDefs) { case (b, d) =>
        Switch("matchFunction", List(
          ((b.cel === Cel.nil) &&
            (functionName === d.flatMap(_.function).flatMap(_.name)) &&
            (d.get.workflowName.cel !== Cel.nil) &&
            (d.get.workflowName.cel !== str(""))) -> {
            val args = RunWorkflowArgs(str(d.get.workflowName + "${WORKFLOW_ENV}"), obj(ToolWorkflow.Input(toolCall, input.totalCost)))
            val runWorkflow = Call[RunWorkflowArgs[ToolWorkflow.Input], ToolWorkflow.Result](
              "callToolWorkflow", CallOperations.WorkflowRun, obj(args))
            given KalaVibhaga = SegKala(Env.sessionId, Value(time.now()), Value(0))
            val saveResult = Convo.addMessage(
              "saveResult",
              obj(ChatMessage(str("tool"), runWorkflow.result.encoded, tool_call_id = toolCall.flatMap(_.id)))
            )
            List[Step[?, ?]](runWorkflow, saveResult) -> runWorkflow.resultValue
          },
          (true: Cel) -> (List() -> b)
        )).fn
      }

      val appendMatched = Switch("appendMatchedTool", List(
        (toolFold.resultValue.cel !== Cel.nil) ->
          (List() -> obj(MatchedResults(ListValue[ToolWorkflow.Result](
            list.concat(results.get.results.cel, toolFold.resultValue.cel))))),
        (true: Cel) -> (List() -> results)
      ))
      List[Step[?, ?]](toolFold, appendMatched) -> appendMatched.resultValue
    }

    Workflow(buildSteps[Result](List(dispatch) -> obj(Result(dispatch.resultValue.flatMap(_.results))), _ => List() -> Value.nil))
  })
}
