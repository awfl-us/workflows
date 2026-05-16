package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.tools.CliTools
import us.awfl.workflows.EventHandler
import us.awfl.workflows.helpers.Agents
import us.awfl.workflows.helpers.ToolDefs
import us.awfl.workflows.helpers.ToolDefs.ToolWithWorkflow

trait Tools extends us.awfl.core.Workflow with EventHandler.WithInput {
  def toolsWfName = s"${workflowName}-tools"

  case class ToolsResult(tools: ListValue[ToolWithWorkflow])

  def toolsWorkflow = execute[Input, ToolsResult](toolsWfName, inputVal)
  
  def buildTools: Step[ToolWithWorkflow, ListValue[ToolWithWorkflow]] = {
    // resolve agentId by session, then fetch that agent's tool names. Fall back to existing list if none.
    val sessionAgent = Agents.agentIdBySession("sessionAgent", sessionId)
    val agentTools = Agents.toolsByAgent("agentTools", sessionAgent.resultValue)

    // Service-first tool defs with names filtering
    val toolDefs = ToolDefs("toolDefs", sessionId, agentTools.resultValue)

    Try(
      "buildTools",
      List[Step[?, ?]](
        sessionAgent,
        agentTools,
        toolDefs
      ) -> toolDefs.resultValue
    ).flatMapList(_.defs)
  }

  override def workflows: List[Workflow[?]] = Workflow(
    {
      val tools = buildTools
      List(tools) -> obj(ToolsResult(tools.resultValue))
    },
    Some("tools")
  ) :: super.workflows
}