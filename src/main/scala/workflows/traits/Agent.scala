package workflows.traits

import dsl.*
import dsl.auto.given
import workflows.EventHandler
import services.Llm.ChatToolResponse
import utils.Env

trait Agent extends core.Workflow with EventHandler with Preloads with Tasks {
  override type Result = ChatToolResponse

  def apply(name: String, query: BaseValue[String], fund: BaseValue[Double]): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    execute(workflowName, obj(Input(query, fund, env = obj(Env.get.copy(sessionId = str(workflowName))))))
  }

  override def workflows = eventHandler() :: super.workflows
}