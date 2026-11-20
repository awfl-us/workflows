package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.{ChatMessage, ConvoSummary}
import us.awfl.utils.{KalaVibhaga, Sadhaka, SegKala}
import us.awfl.workflows.EventHandler

/** Exercise the original wip-old Ista factory + generic Sadhaka against
  * native WorkflowRun and Jobs callbacks. Never publish this test workflow.
  */
object SadhakaNativeFixture extends us.awfl.core.Workflow {
  case class Input()
  override val inputVal = init[Input]("input")

  import EventHandler.given
  import ChatMessage.given
  import ConvoSummary.given

  given KalaVibhaga = SegKala(
    str("sadhaka-fixture-session"), Value[Double](0), Value[Double](1200))

  private val completion = summon[Sadhaka[EventHandler.Input]]
    .complete[ChatMessage, ConvoSummary](
      "Summarize the fixture conversation", str("helpers-SadhakaChildFixture"))

  override def workflows = List(
    Workflow(List[Step[?, ?]](completion) -> completion.resultValue)
  )
}
