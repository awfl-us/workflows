package workflows.helpers

import dsl.*
import dsl.auto.given
import dsl.CelOps.*
import ista.{ChatMessage, ToolCall, ToolCallFunction}
import workflows.tools.CliTools
import workflows.EventHandler

object Context {
  def preloadFile(name: String, filename: String) = {
    val toolCall = obj(ToolCall(
      id = str("preload_file"),
      `type` = "function",
      function = obj(ToolCallFunction(str("READ_FILE"), obj(s"{\"filepath\": \"${filename}\"}")))
    ))

    val contents = CliTools.enqueueAndAwaitCallback(
      opName   = s"${name}_preloadFile",
      toolCall = toolCall,
      cost     = obj(0.0),
      background = Value(true)
    )

    // Keep the expression short to satisfy Cloud Workflows' 400-char expression limit.
    Block(
      s"${name}_block",
      List(contents) -> obj(ChatMessage("system", str(((s"[Preload ${filename.takeRight(350)}]\r": Cel) + contents.resultValue.cel))))
    )
  }

  // Preload helper that runs a shell command and captures stdout for the system prompt
  def preloadCommand(name: String, command: String) = {
    val paramJson = Try(s"${name}_paramJson", List() -> obj(Map("command" -> command)))

    val toolCall = obj(ToolCall(
      id = str("preload_command"),
      `type` = "function",
      function = obj(ToolCallFunction(str("RUN_COMMAND"), str(CelFunc("json.encode_to_string", paramJson.resultValue))))
    ))

    val contents = CliTools.enqueueAndAwaitCallback(
      opName   = s"${name}_preloadCommand",
      toolCall = toolCall,
      cost     = obj(0.0),
      background = Value(true)
    )

    Block(
      s"${name}_block",
      List(paramJson, contents) -> obj(ChatMessage("system", str((CelStr(s"[Preload ${command.takeRight(350)}]\r").safe + contents.resultValue.cel))))
    )
  }
}