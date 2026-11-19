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
  private case class UniqueTools(
    names: ListValue[String],
    tools: ListValue[ToolWithWorkflow]
  )

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
      // Service definitions come first. When they provide a typed schema
      // for a built-in tool, keep that schema rather than advertising two
      // functions with the same name. Other built-in tools remain available.
      val unique = Fold(
        "uniqueToolDefinitions",
        obj(UniqueTools(ListValue.empty, ListValue.empty)),
        tools.resultValue
      ) { (acc, tool) =>
        val functionName = tool.flatMap(_.function).flatMap(_.name)
        val choose = Switch("keepFirstToolDefinition", List(
          (functionName.cel in acc.get.names.cel) -> (List() -> acc),
          (true: Cel) -> (List() -> obj(UniqueTools(
            ListValue[String](list.concat(acc.get.names.cel, functionName.cel)),
            ListValue[ToolWithWorkflow](list.concat(acc.get.tools.cel, tool.cel))
          )))
        ))
        List[Step[?, ?]](choose) -> choose.resultValue
      }
      List[Step[?, ?]](tools, unique) -> obj(ToolsResult(unique.result.tools))
    },
    Some("tools")
  ) :: super.workflows
}