package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.workflows.tools.CliTools

trait Tools extends us.awfl.core.Workflow with Prompts {
  def buildTools: Step[String, ListValue[String]] = buildList("buildCliTools", CliTools.toolNames)
}