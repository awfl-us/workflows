package us.awfl.workflows.helpers

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.utils.Convo.ConvoContext
import us.awfl.ista.ChatMessage
import us.awfl.workflows.EventHandler

trait SmallStrider extends us.awfl.core.Workflow with EventHandler {
  override def buildSteps(
    main: (List[Step[?, ?]], BaseValue[Result]),
    except: Resolved[Error] => (List[Step[?, ?]], BaseValue[Result])
  )(using Spec[Result]): (List[Step[?, ?]], BaseValue[Result]) = super.buildSteps(
    (main._1 :+ SmallStrider("smallStrider", id, windowSeconds = 60 * 3, overlapSeconds = 30)) -> main._2,
    except
  )
}

// Strider wrapper to run a lightweight, session-scoped "Small" pass that yields a single ChatMessage
// Keep the prompt concise; this primarily validates wiring and can be tuned later.
object SmallStrider extends us.awfl.utils.strider.ConvoStrider[ConvoContext, EventHandler.Input, ChatMessage] {
  override def name: String = "helpers-SmallStrider"

  // Minimal, safe default prompt suitable for nano model usage
  override def prompt: String =
    "You're a lightweight assistant. Produce one short operational note (<= 200 chars) about the next best action for this session, based only on recent context. If nothing clear, call the sleep tool."
}
