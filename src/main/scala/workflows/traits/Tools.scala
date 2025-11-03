package workflows.traits

import dsl.*
import workflows.tools.CliTools

trait Tools extends core.Workflow with Prompts {
  def buildTools: Step[String, ListValue[String]] = buildList("buildCliTools", CliTools.toolNames)
}