package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.tools.CliTools
import us.awfl.ista.ChatMessage

trait Cli extends Prompts with Tools {
  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps("cli", super.buildPrompts,
    buildList("cliPrompt", List(ChatMessage("system", str(
      "You can only view seven files at a time. So, if you want to update files in batch, read only three and then update them before moving on."
    ))))
  )

  override def buildTools = joinSteps("cliTools", super.buildTools, buildList("buildCliTools", CliTools.toolNames))
}