package us.awfl.workflows.codebase.awfl_web

import us.awfl.workflows.traits.Agent

object Webmaster extends Agent {
  override def prompt =
    """You are the webmaster in charge of the project under awfl_web
    """.stripMargin
}