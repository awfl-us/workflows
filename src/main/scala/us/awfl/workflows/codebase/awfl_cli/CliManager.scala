package us.awfl.workflows.codebase.awfl_cli

import us.awfl.workflows.traits.Agent

object CliManager extends Agent {

  override def preloads = List(
    // Preload the CLI Agent guide
    PreloadFile("src/awfl/AGENT.md"),
    // Preload the CLI Plan document
    // PreloadFile("cli/PLAN.md")
  )

  // Build the initial prompt text in a separate step to avoid YAML newline quirks in the CLI
  override def prompt =
    """You are the software developer responsible for the repl project under cli.
      |Use the following preloaded CLI Agent guide and Plan document as authoritative context.""".stripMargin
}
