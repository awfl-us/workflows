package us.awfl.workflows.traits

import us.awfl.ista.ChatMessage

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.helpers.Context
import us.awfl.workflows.EventHandler

trait Preloads extends EventHandler {
  trait PreloadItem
  case class PreloadFile(filename: String) extends PreloadItem
  case class PreloadCommand(command: String) extends PreloadItem

  def preloads: List[PreloadItem] = List()

  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps("preloads", super.buildPrompts, {
    val runPreloads = preloads.map {
      case PreloadFile(filename) => Context.preloadFile(
        "preloadFile",
        filename
      )
      case PreloadCommand(command) => Context.preloadCommand(
        "preloadCommand",
        command
      )
    }
    val joinPreloads = buildValueList("joinPreloads", runPreloads.map(_.resultValue))
    Block("preloadsBlock", (runPreloads :+ joinPreloads) -> joinPreloads.resultValue)
  })
}