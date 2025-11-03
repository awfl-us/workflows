package workflows

import dsl._
import dsl.auto.given
import utils.Convo.ConvoContext
import ista.ConvoSummary
import utils.Convo.StepName
import utils.Convo.Prompt
import dsl.buildList
import ista.ChatMessage

given StepName = StepName("summariesStrider")
given Prompt = Prompt(buildList("buildPrompt", List(ChatMessage("system", str("You are a thoughtfull and pragmatic assistant. Plan and operate towards the most impactful, cost effective solutions.")))))
object Summaries extends utils.strider.Latest[ConvoContext, ConvoSummary]("Summaries")
