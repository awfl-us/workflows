package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.utils.Env

trait Agent extends us.awfl.core.Workflow with EventHandler with Preloads with Tasks with Cli with Funds {
  override type Result = ChatToolResponse

  def apply(name: String, query: BaseValue[String], fund: BaseValue[Double], spent: BaseValue[Double]): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    execute(workflowName, obj(EventHandler.Input(query, fund, OptValue(spent), env = obj(Env.get.copy(sessionId = str(workflowName))))))
  }

  override def workflows = eventHandler() :: super.workflows
}