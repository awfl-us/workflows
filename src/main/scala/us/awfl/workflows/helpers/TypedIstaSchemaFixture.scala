package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm
import us.awfl.utils.{Ista, SegKala, KalaVibhaga}

/** Read-only native compiler fixture. No services, credentials or model calls. */
object TypedIstaSchemaFixture extends us.awfl.core.Workflow {
  case class Input()
  case class Args(
    path: Value[String],
    maxLines: Value[Int],
    note: OptBase[String],
    tags: OptList[String],
    history: ListValue[Boolean],
    counts: Value[Map[String, Int]]
  )
  case class Output(tool: Llm.Tool)
  override val inputVal = init[Input]("input")
  private val ista = Ista[Args](
    name = "READ_TYPED",
    build = _ => buildList[ChatMessage]("unusedMessages", List.empty),
    description = {
      case _: SegKala => "Read files in the current segment"
      case _ => "Read files"
    }
  )
  private val kala: KalaVibhaga = SegKala(
    Value[String](CelStr("test-session")), Value[Double](0), Value[Double](1200))

  override def workflows = List(
    Workflow(List.empty[Step[?, ?]] -> obj(Output(ista.tool(kala))))
  )
}
