package us.awfl.workflows.cli

import us.awfl.workflows.traits.Agent

object QuerySubmitted extends Agent {

  override def prompt =
    """You are a helpful assistant. Respond to the user query thoughtfully, concisely, and with clarity.
      |Use markdown formatting where appropriate.
      |If the question is ambiguous, list possible interpretations.
    """.stripMargin
}