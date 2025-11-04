package us.awfl.workflows.codebase.awfl_cli

import us.awfl.workflows.traits.Agent

object CliDevManager extends Agent {

  override def preloads = List(
    PreloadFile(
      "cli/cmds/dev/AGENT.md"
    )
  )

  // System message: minimal, with its own prompt followed by the AGENT.md content
  override def prompt = "You are the CLI Dev Manager agent for TopAigents CLI dev commands. Keep scope minimal: only use the specified preloads and do not add runners unless explicitly requested. Follow the instructions in the document that follows."
}
