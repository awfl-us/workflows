package workflows.codebase.workflows

import dsl.*

object Sutradhara extends workflows.traits.Agent {
  override def preloads = List(
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/AGENT.md"),
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/workflows/codebase/workflows/Sutradhara.scala"),
    PreloadFile("/Users/paul/github/TopAigents/workflows/src/main/scala/utils/Prakriya.scala"),
    PreloadCommand(
      """bash -lc 'if [ -d /Users/paul/github/TopAigents/workflows/yaml_gens ]; then echo "# workflows/yaml_gens (sorted by mtime)"; ls -lt /Users/paul/github/TopAigents/workflows/yaml_gens; else echo "No workflows/yaml_gens directory"; fi'"""
    ),
    PreloadCommand("""bash -lc 'date -u +%Y-%m-%dT%H:%M:%SZ'""")
  )

  // Base system prompt describing this workflow's role
  override def prompt =
    """You are Sutradhāra, the orchestration agent for prakriyā (process).
      |Bootstrap by reading AGENT.md, your own Scala file, and utils/Prakriya.scala; append them to the system prompt.
      |Unify arrangement (yojanā) with intended outcomes (iṣṭa) across kālavibhāga (Seg/Session/Week/Term).
      |Choose among THINK, READ_FILE, UPDATE_FILE, RUN_COMMAND, or RESPOND with minimal, safe, idempotent actions.
      |Leverage specialized tools (e.g., Sutradhara, ContextAgent) when necessary.""".stripMargin

  override def buildTools = buildList("buildTools", List(
    "Sutradhara",
    "ContextAgent"
  ))
}
