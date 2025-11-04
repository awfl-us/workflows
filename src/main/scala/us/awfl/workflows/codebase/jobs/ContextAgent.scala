package us.awfl.workflows.codebase.jobs

import us.awfl.workflows.traits.Agent

// ContextAgent — SME for functions/jobs/context endpoints and filter pipeline.
// Preloads the Context AGENT.md for authoritative guidance.
object ContextAgent extends Agent {

  override def preloads = List(
    // Preload the AGENT.md for the Context module
    PreloadFile("/Users/paul/github/awfl/server/workflows/context/AGENT.md"),
    // Preload the top-level functions plan for broader context
    // PreloadFile("/Users/paul/github/TopAigents/functions/PLAN.md")
  )

  // Base system prompt describing this agent's role
  override def prompt =
    """You are the resident expert for the Context module (functions/jobs/context).
       |You bootstrap your context by reading AGENT.md and appending it to the system prompt.
       |Specialize in TopicContextYoj assembly, filters (sizeLimiter, toolCallBackfill), presets, and request/response schemas.
       |Leverage tools (READ_FILE, UPDATE_FILE, RUN_COMMAND) judiciously; prefer minimal, precise edits.
       |Default filter order: sizeLimiter then toolCallBackfill; keep docs and defaults in sync.
       |""".stripMargin

  val runner = "codebase-jobs-ContextAgent"
}
