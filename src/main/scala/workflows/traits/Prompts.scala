package workflows.traits

import dsl.*
import dsl.auto.given
import ista.ChatMessage
import workflows.cli.CliActions

trait Prompts extends core.Workflow {
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