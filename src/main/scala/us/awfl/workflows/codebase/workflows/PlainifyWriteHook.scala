package us.awfl.workflows.codebase.workflows

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.{ChatMessage, ToolCall, ToolCallFunction}
import us.awfl.workflows.tools.CliTools

/**
 * PlainifyWriteHook
 *
 * Purpose
 * - Hook that accepts Input(filepath) ONLY.
 * - Reads the raw source code from the given filepath (no hardcoded roots).
 * - Generates a complete plain-technical-English explanation of the code.
 * - Writes the explanation to: /plain<filepath> (prefixes the exact input filepath with /plain).
 *
 * Notes
 * - Uses READ_FILE to load the raw code, RUN_COMMAND to mkdir -p the parent dir of the output path, then UPDATE_FILE to write the explanation.
 */
object PlainifyWriteHook extends us.awfl.core.Workflow {
  // Result type is the output file path as a plain String
  type Result = String

  case class Input(
    filepath: Value[String]
  )
  override val inputVal: BaseValue[Input] = init[Input]("input")
  override def workflows = List(
    apply()
  )

  def apply(): Workflow[String] = {
    val sessionId: Value[String] = str("plaintify_write_hook")

    val inPath: Value[String]  = inputVal.flatMap(_.filepath)
    val outPath: Value[String] = str((("plain/": Cel) + inPath.cel))

    // 1) READ_FILE the raw source code from <filepath>
    val readParams = Try(
      "read_raw_params",
      List() -> obj(Map(
        "filepath" -> inPath
      )).base
    )
    val readCall = obj(ToolCall(
      id = str("read_raw"),
      `type` = "function",
      function = obj(ToolCallFunction(
        str("READ_FILE"),
        str(CelFunc("json.encode_to_string", readParams.resultValue))
      ))
    ))
    val readOutEncoded = CliTools.enqueueAndAwaitCallback(
      opName     = "readRaw",
      toolCall   = readCall,
      cost       = obj(0.0),
      background = Value(true)
    )
    // Extract .content from the encoded JSON callback body
    val rawCode: Value[String] = Value(CelFunc(
      "map.get",
      CelFunc("json.decode", readOutEncoded.resultValue.cel),
      "content"
    ))

    // 2) Ask LLM to generate a complete plain-technical-English explanation
    val sysMsg = ChatMessage(
      "system",
      str(((
        "Translate the following code completely into precise, plain technical English. Include all types, methods, data flow, control flow, external interactions, and intent. No omissions. Include the full class signature in code, but the implementation and logic should be in plain English.\n\nTarget filepath: " : Cel
      ) + inPath.cel))
    )
    val userMsg = ChatMessage(
      "user",
      str((("Code follows:\n\n" : Cel) + rawCode.cel))
    )

    val messages = buildList("plainifyMessages", sysMsg :: userMsg :: Nil)

    val chat = us.awfl.services.Llm.chatWithTools(
      name = "plainify",
      messages = messages.resultValue,
      tools = ListValue.nil,
      tool_choice = Value.nil,
      model = Field.str("gpt-5"),
      maxTokens = Value.nil
    )

    val explanation = chat.result.message.get.content

    // 3) Ensure destination directory exists
    val mkdirParams = Try(
      "mkdir_plain_params",
      List() -> obj(Map(
        "command" -> str((("bash -lc 'mkdir -p $(dirname " : Cel) + outPath.cel + (")'" : Cel)))
      )).base
    )
    val mkdirCall = obj(ToolCall(
      id = str("mkdir_plain"),
      `type` = "function",
      function = obj(ToolCallFunction(
        str("RUN_COMMAND"),
        str(CelFunc("json.encode_to_string", mkdirParams.resultValue))
      ))
    ))
    val doMkdir = CliTools.enqueueAndAwaitCallback(
      opName     = "mkdirPlain",
      toolCall   = mkdirCall,
      cost       = obj(0.0),
      sessionId  = sessionId,
      background = Value(true)
    )

    // 4) Write explanation to /plain<filepath>
    val writeParams = Try(
      "write_plain_params",
      List() -> obj(Map(
        "filepath" -> outPath,
        "content"  -> explanation
      )).base
    )
    val writeCall = obj(ToolCall(
      id = str("write_plain"),
      `type` = "function",
      function = obj(ToolCallFunction(
        str("UPDATE_FILE"),
        str(CelFunc("json.encode_to_string", writeParams.resultValue))
      ))
    ))
    val writeOut = CliTools.enqueueAndAwaitCallback(
      opName     = "writePlain",
      toolCall   = writeCall,
      cost       = obj(0.0),
      sessionId  = sessionId,
      background = Value(true)
    )

    Workflow(
      List[Step[_, _]](
        readParams,
        // readCall,
        readOutEncoded,
        messages,
        chat,
        mkdirParams,
        doMkdir,
        writeParams,
        writeOut
      ) -> outPath
    )
  }
}
