package us.awfl.workflows.codebase.workflows

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.EventHandler
import us.awfl.workflows.helpers.Context
import us.awfl.ista.ChatMessage
import us.awfl.workflows.codebase.jobs.ContextAgent
import us.awfl.services.Llm.ChatToolResponse

object BuildManager extends us.awfl.workflows.traits.Agent {

  override def preloads = List(
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/AGENT.md"),
    // PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/codebase/workflows/BuildManager.scala"),
    PreloadFile("/Users/paul/github/TopAigents/workflows/build.sbt"),
    // val cornerstoneBuildSbt = Context.preloadFile("CornerstoneBuildSbt", "/Users/paul/github/TopAigents/cornerstone/build.sbt")
    PreloadCommand(
      """bash -lc 'if [ -d /Users/paul/github/TopAigents/workflows/yaml_gens ]; then echo "# workflows/yaml_gens (sorted by mtime)"; ls -lt /Users/paul/github/TopAigents/workflows/yaml_gens; else echo "No workflows/yaml_gens directory"; fi'"""
    ),
    PreloadCommand("""bash -lc 'date -u +%Y-%m-%dT%H:%M:%SZ'""")
  )

  // Base system prompt for this agent
  override def prompt =
    """You are BuildManager, a resident expert for managing Scala (sbt) builds, dependency coordinates/resolvers, and local/CI build hygiene.
      |You operate under workflows/src/main/scala/workflows.
      |You bootstrap your own context by reading your Scala definition file and appending it to the system prompt.
      |Also preload the project's build.sbt so you can reason about build settings and dependencies.
      |Leverage specialized tools (e.g., Sutradhara, ContextAgent) when necessary.""".stripMargin

  override def buildTools = joinSteps("tools", super.buildTools, buildList("buildTools", List("Sutradhara", "ContextAgent")))
}
