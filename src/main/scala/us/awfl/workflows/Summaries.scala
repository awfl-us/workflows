package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.utils.Convo.ConvoContext
import us.awfl.ista.ConvoSummary
import us.awfl.utils.Convo.StepName
import us.awfl.utils.Convo.Prompt
import us.awfl.dsl.buildList
import us.awfl.ista.ChatMessage

given StepName = StepName("summariesStrider")
given Prompt = Prompt(buildList("buildPrompt", List(ChatMessage("system", str("You are a thoughtfull and pragmatic assistant. Plan and operate towards the most impactful, cost effective solutions.")))))
object Summaries extends us.awfl.utils.strider.Latest[ConvoContext, ConvoSummary]("Summaries")
