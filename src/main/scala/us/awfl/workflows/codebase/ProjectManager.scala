package us.awfl.workflows.codebase

import us.awfl.workflows.traits.Agent

object ProjectManager extends Agent {
  override def preloads = List(
    // Preload the AGENT.md for the current project
    PreloadFile("AGENT.md")
  )

  override def prompt =
    """You are the agent responsible for assisting with development in the current project.
    """.stripMargin
}