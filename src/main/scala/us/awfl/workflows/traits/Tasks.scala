package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.workflows.helpers
import us.awfl.workflows.tools.Tasks as TaskTools

trait Tasks extends Prompts with Tools {
  // Stable guidance only. Task state is fetched explicitly with CHECK_TASKS,
  // rather than rewriting three early system messages on every agent turn.
  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps(
    "taskPrompts", super.buildPrompts,
    buildList("taskGuidance", helpers.Tasks.managementGuidance)
  )

  override def buildTools = joinSteps("taskTools", super.buildTools, TaskTools.buildTools)
}