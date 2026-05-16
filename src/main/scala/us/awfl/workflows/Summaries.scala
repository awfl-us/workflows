package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.utils.Convo.ConvoContext
import us.awfl.ista.ConvoSummary
import us.awfl.utils.Convo.StepName
import us.awfl.dsl.buildList
import us.awfl.ista.ChatMessage
import us.awfl.services.Firebase
import us.awfl.utils.Env
import us.awfl.utils.SegKala
import us.awfl.utils.KalaVibhaga

given StepName = StepName("summariesStrider")
object Summaries extends us.awfl.utils.strider.Latest[ConvoContext, ConvoSummary]("Summaries") {
  override def prompt = "You are a thoughtfull and pragmatic assistant. Plan and operate towards the most impactful, cost effective solutions."

  override def onPostWrite(sessionId: Value[String], responseId: Value[String], response: Value[ConvoSummary], at: Value[Double]): List[Step[?, ?]] = {
    val title = response.flatMap(_.title)
    List(Switch("titleSwitch", List(
      ((title.cel !== Cel.nil) && (CelFunc("len", title.cel) > 0)) ->
        Firebase.update(
          "saveTitle",
          str("convo.sessions"),
          Env.sessionId,
          obj(Map("title" -> title))
        ).fn,
      (true: Cel) -> (List() -> Value.nil)
    )))
  }
}
