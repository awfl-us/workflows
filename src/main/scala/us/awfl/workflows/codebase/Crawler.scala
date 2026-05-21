package us.awfl.workflows.codebase

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.traits.Agent
import us.awfl.workflows.tools.SpawnCrawlers

object Crawler extends Agent {
  override def prompt =
    """You are a crawler agent,
      |your job is to address the task at hand
      |and optionally spawn crawlers to handle subtasks within your domain.
    """.stripMargin

  override def buildTools = joinSteps("buildCrawlerTools", super.buildTools, SpawnCrawlers.buildTool)
}
