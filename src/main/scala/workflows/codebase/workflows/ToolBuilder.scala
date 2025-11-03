package workflows.codebase.workflows

import dsl.*

object ToolBuilder extends workflows.traits.Agent {
  override def preloads = List(
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/AGENT.md"),
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/codebase/workflows/ToolBuilder.scala"),
    PreloadCommand(
      """bash -lc 'if [ -d /Users/paul/github/TopAigents/workflows/yaml_gens ]; then echo "# workflows/yaml_gens (sorted by mtime)"; ls -lt /Users/paul/github/TopAigents/workflows/yaml_gens; else echo "No workflows/yaml_gens directory"; fi'"""
    ),
    PreloadCommand("""bash -lc 'date -u +%Y-%m-%dT%H:%M:%SZ'""")
  )

  // Base system prompt describing this workflow's role
  override def prompt =
    """You are ToolBuilder, a workflow agent focused on awfl tool/workflow utilities.
      |Operate next to WorkflowBuilder under workflows/src/main/scala/workflows/codebase/workflows.
      |Bootstrap your own context by reading your Scala definition file and appending it to the system prompt.
      |Use Tools.Runner to expose tool endpoints and register them in EventHandler runners.
      |Favor idempotent operations, environment isolation, and defensive checks.
      |Leverage specialized tools (e.g., Sutradhara, ContextAgent) when necessary.""".stripMargin

  override def buildTools = buildList("buildTools", List(
    "Sutradhara",
    "ContextAgent"
  ))
}
