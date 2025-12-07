package us.awfl.workflows.cli

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.services.Llm
import us.awfl.ista.ChatMessage

object CliActions {
  // case class StatusUpdate(
  //   message: Value[String],
  //   // Typed tool result carried as structured data (DSL-style optional via null default)
  //   tool_result: BaseValue[Llm.ToolCallResult] = Value("null"),

  //   action: Resolved[String] = str("null": Cel),
  //   filepath: Resolved[String] = str("null": Cel),
  //   content: Value[String] = Field("null"),
  //   command: Value[String] = Field("null"),
  //   workflow: Value[String] = Field("null"),
  //   query: Resolved[String] = str("null": Cel)
  // )

  // case class StatusRecord(response: BaseValue[StatusUpdate], create_time: Value[String])

  // Tool-only instruction (primary)
  val cliStatusPrompt = {
    us.awfl.ista.ChatMessage("system", str(
      """Here are the available tool calls.
        |Use these tools:
        |- THINK: private reflection, no side effects.
        |- UPDATE_FILE: write a file with {filepath, content}.
        |- READ_FILE: read a file with {filepath}.
        |- RUN_COMMAND: execute a shell command with {command}.
        |- QUERY: delegate to another workflow with {workflow, query}.
      """.stripMargin
    ))
  }

  // Alias maintained for callers expecting toolModePrompt
  val toolModePrompt = cliStatusPrompt

  // def saveStatus(name: String, sessionId: Value[String], update: BaseValue[StatusUpdate]) = {
  //   val collection: Cel = ("cli.session/": Cel) + sessionId.resolver.path + "/messages/"

  //   val updateStep = us.awfl.services.Firebase.update[StatusRecord](
  //     name,
  //     Field(collection),
  //     Field("uuid.generate()"),
  //     obj(StatusRecord(
  //       response = update,
  //       create_time = Field("sys.now()")
  //     ))
  //   )

  //   Block(s"${name}_block",
  //     List[Step[_, _]](
  //       Log(s"${name}_log", str(("Collection: ": Cel) + collection)),
  //       updateStep
  //     ) -> updateStep.resultValue
  //   )
  // }
}
