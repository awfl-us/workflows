package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.workflows.helpers
import us.awfl.workflows.tools.Tasks as TasksTool

trait Tasks extends Prompts with Tools {
  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps("taskPrompts", super.buildPrompts, {
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

  override def buildTools = joinSteps("taskTools", super.buildTools, buildList("buildTaskTools", TasksTool.supported))
}