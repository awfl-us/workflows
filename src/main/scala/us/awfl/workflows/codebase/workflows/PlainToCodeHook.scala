package us.awfl.workflows.codebase.workflows

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.Env

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
  override val inputVal: Value[Input] = init[Input]("input")
  override def workflows = List(
    apply()
  )

  def apply(): Workflow[String] = {
    val sessionId: Value[String] = Value("uuid.generate()")

    val outPath: Value[String]   = inputVal.flatMap(_.filepath)
    val plainPath: Value[String] = str((("plain/": Cel) + outPath.cel))

    // 1) READ_FILE the plain specification from /plain<filepath>
    val plainSpec = CliTools.readFile(
      filepath = plainPath,
      opName = "readPlain",
      env = obj(Env.get.copy(
        sessionId = sessionId,
        background = OptValue(true)
      ))
    )

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
      // plainSpec is a Block[..., Value[String]]. Use resultValue.cel to embed it in CEL
      str((("Plain specification follows:\n\n" : Cel) + plainSpec.resultValue.cel))
    )

    val messages = buildList("plainToCodeMessages", sysMsg :: userMsg :: Nil)

    val chat = us.awfl.services.Llm.chatWithTools(
      name = "plain_to_code",
      messages = messages.resultValue,
      tools = ListValue.nil,
      sessionId = sessionId,
      tool_choice = Value.nil,
      model = str("gpt-4o"),
      temperature = 0.2,
      maxTokens = Value("null")
    )

    val generatedCode: BaseValue[String] = chat.result.message.get.content

    // 3) Ensure destination directory exists
    val doMkdir = CliTools.runCommand(
      command = str((("bash -lc 'mkdir -p $(dirname " : Cel) + outPath.cel + (")'" : Cel))),
      opName = "mkdirCode",
      env = obj(Env.get.copy(
        sessionId = sessionId,
        background = OptValue(true)
      ))
    )

    // 4) Write generated code to <filepath>
    val writeOut = CliTools.writeFile(
      filepath = outPath,
      content  = generatedCode,
      opName   = "writeCode",
      env = obj(Env.get.copy(
        sessionId = sessionId,
        background = OptValue(true)
      ))
    )

    Workflow(
      List[Step[?, ?]](
        plainSpec,
        messages,
        chat,
        doMkdir,
        writeOut
      ) -> outPath
    )
  }
}
