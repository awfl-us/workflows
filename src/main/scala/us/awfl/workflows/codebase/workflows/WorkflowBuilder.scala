package us.awfl.workflows.codebase.workflows

import us.awfl.dsl.*

object WorkflowBuilder extends us.awfl.workflows.traits.Agent {
  override def preloads = List(
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/AGENT.md"),
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/codebase/workflows/WorkflowBuilder.scala"),
    // PreloadFile("/Users/paul/github/TopAigents/scripts/PLAN.md")
    PreloadCommand(
      """bash -lc 'if [ -d /Users/paul/github/TopAigents/workflows/yaml_gens ]; then echo "# workflows/yaml_gens (sorted by mtime)"; ls -lt /Users/paul/github/TopAigents/workflows/yaml_gens; else echo "No workflows/yaml_gens directory"; fi'"""
    ),
    PreloadCommand("""bash -lc 'date -u +%Y-%m-%dT%H:%M:%SZ'""")
  )

  // Base system prompt describing this workflow's role
  override def prompt =
    """You are the resident expert in building us.awfl.workflows. You operate under workflows/src/main/scala/workflows.
      |You bootstrap your own context by reading your Scala definition file and appending it to the system prompt.
      |Leverage specialized tools (e.g., Sutradhara) when necessary.""".stripMargin

  override def buildTools = joinSteps("tools", super.buildTools, buildList("buildTools", List(
    "Sutradhara",
    "ContextAgent"
  )))
}
