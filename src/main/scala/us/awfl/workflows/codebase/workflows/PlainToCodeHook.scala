package us.awfl.workflows.codebase.workflows

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.{ChatMessage, ToolCall, ToolCallFunction}
import us.awfl.workflows.tools.CliTools

/**
 * PlainToCodeHook
 *
 * Purpose
 * - Hook that accepts Input(filepath) ONLY.
 * - Reads the plain-English specification from: /plain<filepath>.
 * - Translates the specification back into complete, functional source code for <filepath>.
 * - Writes the generated code to the exact <filepath> (ensuring parent directory exists).
 *
 * Notes
 * - No hardcoded repo paths; we operate solely on the given Input.
 * - Uses READ_FILE to load the plain spec, RUN_COMMAND to mkdir -p the parent dir, then UPDATE_FILE to write the code.
 */
object PlainToCodeHook extends us.awfl.core.Workflow {
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
    val sessionId: Value[String] = Value("uuid.generate()")

    val outPath: Value[String]   = inputVal.flatMap(_.filepath)
    val plainPath: Value[String] = str((("plain/": Cel) + outPath.cel))

    // 1) READ_FILE the plain specification from /plain<filepath>
    val readParams = Try(
      "read_plain_params",
      List() -> obj(Map(
        "filepath" -> plainPath
      ))
    )
    val readCall = obj(ToolCall(
      id = str("read_plain"),
      `type` = "function",
      function = obj(ToolCallFunction(
        str("READ_FILE"),
        str(CelFunc("json.encode_to_string", readParams.resultValue))
      ))
    ))
    val readOutEncoded = CliTools.enqueueAndAwaitCallback(
      opName     = "readPlain",
      toolCall   = readCall,
      cost       = obj(0.0),
      sessionId  = sessionId,
      background = Value(true)
    )
    // Extract .content from the encoded JSON callback body
    val plainSpec: Value[String] = Value(CelFunc(
      "map.get",
      CelFunc("json.decode", readOutEncoded.resultValue.cel),
      "content"
    ))

    // 2) Ask LLM to generate the complete, compilable code for <filepath>
    val sysMsg = ChatMessage(
      "system",
      str("""
        |You are PlainToCodeHook. Convert a plain technical-English specification into a complete, compilable source file.
        |Operating rules:
        | - Infer the target language, package, and imports from the file path and context.
        | - Preserve behavior, data flow, side effects, error handling, and formatting.
        | - Include the correct package declaration when applicable.
        | - Return only the full code of the file; no backticks, no commentary.
      """.stripMargin)
    )
    val userMsg = ChatMessage(
      "user",
      str((("Plain specification follows:\n\n" : Cel) + plainSpec.cel))
    )

    val messages = buildList("plainToCodeMessages", sysMsg :: userMsg :: Nil)

    val chat = us.awfl.services.Llm.chatWithTools(
      name = "plain_to_code",
      messages = messages.resultValue,
      tools = ListValue.nil,
      tool_choice = Field.str("none"),
      model = Field.str("gpt-4o"),
      temperature = 0.2,
      maxTokens = Value("null")
    )

    val generatedCode: BaseValue[String] = chat.result.message.get.content

    // 3) Ensure destination directory exists
    val mkdirParams = Try(
      "mkdir_code_params",
      List() -> obj(Map(
        "command" -> str((("bash -lc 'mkdir -p $(dirname " : Cel) + outPath.cel + (")'" : Cel)))
      ))
    )
    val mkdirCall = obj(ToolCall(
      id = str("mkdir_code"),
      `type` = "function",
      function = obj(ToolCallFunction(
        str("RUN_COMMAND"),
        str(CelFunc("json.encode_to_string", mkdirParams.resultValue))
      ))
    ))
    val doMkdir = CliTools.enqueueAndAwaitCallback(
      opName     = "mkdirCode",
      toolCall   = mkdirCall,
      cost       = obj(0.0),
      sessionId  = sessionId,
      background = Value(true)
    )

    // 4) Write generated code to <filepath>
    val writeParams = Try(
      "write_code_params",
      List() -> obj(Map(
        "filepath" -> outPath,
        "content"  -> generatedCode
      ))
    )
    val writeCall = obj(ToolCall(
      id = str("write_code"),
      `type` = "function",
      function = obj(ToolCallFunction(
        str("UPDATE_FILE"),
        str(CelFunc("json.encode_to_string", writeParams.resultValue))
      ))
    ))
    val writeOut = CliTools.enqueueAndAwaitCallback(
      opName     = "writeCode",
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
