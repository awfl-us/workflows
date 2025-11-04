package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.workflows.cli.CliActions

trait Prompts extends us.awfl.core.Workflow {
  def promptsWfName = s"${workflowName}-prompts"

  case class PromptsResult(prompts: ListValue[ChatMessage])

  def promptsWorkflow = execute[Input, PromptsResult](promptsWfName, inputVal)

  def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] =
    buildList(
      "buildPromptList",
      ChatMessage("system", str(prompt)) :: CliActions.cliStatusPrompt ::
        Nil
    )

  def prompt: String

  override def workflows: List[Workflow[_]] = Workflow(
    {
      val prompts = buildPrompts
      List(prompts) -> obj(PromptsResult(prompts.resultValue))
    },
    Some("prompts")
  ) :: super.workflows
}