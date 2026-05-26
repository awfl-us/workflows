package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps.*
import us.awfl.ista.{ChatMessage, ToolCall, ToolCallFunction}
import us.awfl.workflows.tools.CliTools
import us.awfl.workflows.EventHandler
import us.awfl.utils.Env

object Context {
  def preloadFile(name: String, filename: Value[String]) = {
    val contents = CliTools.readFile(filename, env = obj(Env.get.copy(background = OptValue(true))))

    // Keep the expression short to satisfy Cloud Workflows' 400-char expression limit.
    Try(
      s"${name}_block",
      List(contents) -> obj(ChatMessage("system", str((("[Preload ": Cel) + filename + "]\r" + contents.resultValue.cel))))
    )
  }

  // Preload helper that runs a shell command and captures stdout for the system prompt
  def preloadCommand(name: String, command: Cel) = {
    val contents = CliTools.runCommand(str(command), env = obj(Env.get.copy(background = OptValue(true))))

    val right = command match {
      case CelStr(str) => CelStr(str.takeRight(350)).safe
      case other => other
    }

    Try(
      s"${name}_block",
      List[Step[?, ?]](contents) -> obj(ChatMessage("system", str(CelStr(s"[Preload ") + right + "]\r" + contents.resultValue.cel)))
    )
  }
}