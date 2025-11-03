package workflows.traits

import dsl.*
import dsl.auto.given
import ista.ChatMessage
import workflows.helpers
import workflows.tools.Tasks as TasksTool

trait Tasks extends Prompts with Tools {
  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps(super.buildPrompts, {
    // val buildPromptList = buildValueList("builList", prompts.map(obj))
    // Add task context prompts: current in-progress, oldest queued, newest done
    val taskPrompts = helpers.Tasks("tasks")
    val buildGuidancePrompt = buildList("buildGuidancePrompt", helpers.Tasks.managementGuidance)

    val buildPrompt = join(
      "buildPrompt",
      buildGuidancePrompt.resultValue,
      taskPrompts.result.tasks
    )

    Try(
      "buildPromptsWithTasks",
      List[Step[_, _]](taskPrompts, buildGuidancePrompt, buildPrompt) -> buildPrompt.resultValue
    )
  })

  override def buildTools = joinSteps(super.buildTools, buildList("buildTaskTools", TasksTool.supported))
}