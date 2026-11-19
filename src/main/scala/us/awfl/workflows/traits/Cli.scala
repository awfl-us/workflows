package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.tools.CliTools
import us.awfl.ista.ChatMessage

trait Cli extends Prompts with Tools {
  override def buildTools = joinSteps("cliTools", super.buildTools, CliTools.buildTools)
}