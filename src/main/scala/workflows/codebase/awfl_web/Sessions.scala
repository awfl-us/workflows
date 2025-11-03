package workflows.codebase.awfl_web

import workflows.traits.Agent

object Sessions extends Agent {

  override def preloads = List(
    PreloadFile("/Users/paul/github/TopAigents/awfl-web/src/pages/Sessions.tsx"),
    PreloadFile("/Users/paul/github/TopAigents/awfl-web/src/pages/AGENT.md"),
    // PreloadFile("/Users/paul/github/TopAigents/awfl-web/PLAN.md")
  )

  // Base system prompt specifying this workflow's responsibility
  override def prompt =
    """You are the workflow responsible for the awfl-web Sessions page (src/pages/Sessions.tsx).
      |Goals:
      |- Understand the current Sessions page implementation and its data/UX flows.
      |- Propose safe, incremental changes via diffs/patches when asked.
      |- Keep changes aligned with the broader awfl-web architecture and coding conventions.
      |- Use Sutradhara as a specialist to interpret Prakriya, Yoj/Iṣṭa/Kālavibhāga concepts when relevant to session state/segmentation.
      |
      |Operational guidance:
      |- Prefer minimal diffs with clear rationale.
      |- Surface assumptions and request missing context if necessary.
      |- When reading or updating files, use the CLI actions and respond with masked secrets if any are encountered.
      |""".stripMargin
}
