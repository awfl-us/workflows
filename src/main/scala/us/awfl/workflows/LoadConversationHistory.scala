package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.Cel._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import io.circe.generic.auto._
import us.awfl.utils.post

object LoadConversationHistory extends us.awfl.core.Workflow {
  // The workflow now calls the API endpoint to load and ingest conversation history.

  case class Input(arg1: Value[String], arg2: Value[String]) {
    val objectName = arg1
    val userId = arg2
  }
  override val inputVal = init[Input]("input")

  override type Result = Map[String, BaseValue[String]]

  // Define the step to POST to the loadConvoHistory endpoint
  val postStep = post[Map[String, Value[String]], NoValueT](
    "postLoadConvoHistory",
    "convo-history/loadConvoHistory",
    obj(Map("objectName" -> input.objectName, "userId" -> input.userId))
  )

  override def workflows = List(Workflow(List(postStep) -> obj(Map("status" -> str("success")))))
}