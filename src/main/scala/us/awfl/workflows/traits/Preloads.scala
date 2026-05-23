package us.awfl.workflows.traits

import us.awfl.ista.ChatMessage

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.helpers.Context
import us.awfl.workflows.EventHandler
import us.awfl.workflows.helpers.Files

trait Preloads extends EventHandler {
  trait PreloadItem
  case class PreloadFile(filename: Cel) extends PreloadItem
  object PreloadFile:
    def apply(filename: String): PreloadFile = PreloadFile(CelStr(filename))

  case class PreloadCommand(command: Cel) extends PreloadItem
  object PreloadCommand:
    def apply(command: String): PreloadCommand = PreloadCommand(CelStr(command).safe)

  case class PreloadScript(name: String) extends PreloadItem

  def preloads: List[PreloadItem] = List()

  override def buildPrompts: Step[ChatMessage, ListValue[ChatMessage]] = joinSteps("preloads", super.buildPrompts, {
    val runPreloads = preloads.map {
      case PreloadFile(filename) => Context.preloadFile(
        "preloadFile",
        str(filename)
      )
      case PreloadCommand(command) => Context.preloadCommand(
        "preloadCommand",
        command
      )
      case PreloadScript(name) =>
        val readFile = Files.scripts(name)
        val runScript = Context.preloadCommand(
          "runScript",
          readFile.resultValue
        )
        Try("preloadScript", List[Step[?, ?]](readFile, runScript) -> runScript.resultValue)
    }
    val joinPreloads = buildValueList("joinPreloads", runPreloads.map(_.resultValue))
    Block("preloadsBlock", (runPreloads :+ joinPreloads) -> joinPreloads.resultValue)
  })
}