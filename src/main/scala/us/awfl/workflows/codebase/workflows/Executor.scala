package us.awfl.workflows.codebase.workflows

import us.awfl.dsl.*

object Executor extends us.awfl.workflows.traits.Agent {
  override def preloads = List(
    PreloadFile("workflows/src/main/scala/workflows/codebase/workflows/Executor.AGENT.md"),
    PreloadFile("workflows/src/main/scala/utils/Exec.scala")
  )

  // Base system prompt describing this workflow's role (kept original)
  override def prompt =
    """You are the Executor workflow agent. Your scope is the utils/Exec.scala helper under workflows/src/main/scala/utils.
      |Preload and use the Executor.AGENT.md and the Exec.scala source to guide safe, incremental improvements and maintenance.
      |Prefer idempotent, defensive changes and keep backwards compatibility.""".stripMargin

  // Keep tools aligned with original usage (ContextAgent only)
  override def buildTools = joinSteps("tools", super.buildTools, buildList("buildTools", List(
    "ContextAgent"
  )))
}
