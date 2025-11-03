package workflows.traits

import dsl.*
import dsl.auto.given
import workflows.EventHandler
import services.Llm.ChatToolResponse
import ista.ToolCall
import utils.Env
import utils.ENV

trait ToolWorkflow extends core.Workflow {
  override type Input = ToolWorkflow.Input
  override type Result = ToolWorkflow.Result
  
  override val inputVal = init[Input]("input")

  // def apply(name: String, sessionId: Value[String], query: BaseValue[String], model: Field, fund: BaseValue[Double], background: Boolean): Call[RunWorkflowArgs[EventHandler.Input], ChatToolResponse] = {
  //   val args = RunWorkflowArgs(str(s"${workflowName}$${WORKFLOW_ENV}"), obj(EventHandler.Input(sessionId, query, model, fund, background = OptValue(background))))
  //   Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  // }
}
object ToolWorkflow {
  case class Input(
    tool_call: BaseValue[ToolCall],
    cost: BaseValue[Double],
    env: BaseValue[Env] = ENV
  )
  case class Result(encoded: BaseValue[String], cost: BaseValue[Double])
}