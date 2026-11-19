package us.awfl.workflows.helpers.links

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.services.Llm.ToolChoice
import us.awfl.utils.ChainableInput
import us.awfl.utils.Linkable
import us.awfl.workflows.EventHandler
import us.awfl.core
import us.awfl.ista.TopicInfo
import us.awfl.services.Llm.ChatResponse
import us.awfl.ista.ChatMessage
import us.awfl.workflows.tools.CliTools


object SaveReflection extends Linkable {
  override case class Params(agentId: BaseValue[core.WorkflowId[EventHandler.Input, ChatResponse[ChatMessage]]])
  override type Input = ChainableInput[TopicInfo, Params]
  override type Result = NoValueT

  override val inputVal: Value[Input] = init("input")

  override def workflows: List[Workflow[AnyValueT]] = List(Workflow {
    val query = str(
      """The current conversation has been marked to save reflection.
        |Analyze what general, widely applicable learnings can be reaped and remembered in order to pay dividends in the future in terms of saved time and quicker, better results going forward.
        |Don't polute the file with information that is only narrowly applicable to a specific recent task or project. Only general development guidelines.
        |Feel free to cleanup anything messy or out of date with the latest state of the project.
        |Keep you changes minimal, agent files should be stable for the most part (except at the very begining of a project).
      """.stripMargin
    )
    val args = RunWorkflowArgs(
      inputVal.flatMap(_.params).flatMap(_.agentId).get.workflowName,
      obj(EventHandler.Input(
        query = OptValue(query),
        fund = Value[Double](0),
        toolChoice = OptObj(obj(CliTools.updateFileTool)),
        sideCall = OptValue(Value[Boolean](true))
      ))
    )
    val call = Call[RunWorkflowArgs[EventHandler.Input], AnyValueT](
      "callAgent",
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(args)
    )
    Switch("maybeSaveReflection", List(
      inputVal.flatMap(_.input.map(_.get.shouldSaveReflection).getOrElse(Value(false))).cel -> call.fn,
      (true: Cel) -> (List() -> Value.nil)
    )).fn
  })
}