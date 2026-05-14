package us.awfl.workflows.codebase

import us.awfl.workflows.traits.Agent
import us.awfl.workflows.traits.local.ProjectManagerLocalTools

object ProjectManager extends Agent with ProjectManagerLocalTools {
  override def prompt =
    """You are the agent responsible for assisting with development in the current project.
    """.stripMargin
}
