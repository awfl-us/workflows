package workflows.traits

import ista.ChatMessage

import dsl.*
import dsl.auto.given
import workflows.helpers.Context
import workflows.EventHandler

trait Preloads extends EventHandler {
  trait PreloadItem
  case class PreloadFile(filename: String) extends PreloadItem
  case class PreloadCommand(command: String) extends PreloadItem

  def preloads: List[PreloadItem] = List()

  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps(super.buildPrompts, {
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