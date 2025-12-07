package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps.*
import us.awfl.ista.ChatMessage
import us.awfl.workflows.helpers
import us.awfl.workflows.tools.Tasks as TasksTool
import us.awfl.workflows.EventHandler

trait Funds extends EventHandler {
  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps("fundsPrompts", super.buildPrompts, {
    val fundPrompts = buildList("fundPrompt", List(
      ChatMessage(str("system"), str(
        ("Original funds allocated for the current task: $": Cel) + input.fund.cel +
        "\rSpent so far: $" + input.spent.getOrElse(Value[Double](0)).cel
      )),
      ChatMessage(str("system"), str(
        """Plan accordingly. If a third of the available funds have been spent: Make sure you're wrapping up investigation and have updated the task.
          |If two thirds of the funds have been spent: Make sure you're wrapping up implentation/main work and the task is up to date.
          |If all the funds have been spent: No more work allowed! Immediatly update the task and then report the latest status.
        """.stripMargin
      ))
    ))
    fundPrompts
  })
}