package us.awfl.workflows.tools

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ToolCall
import us.awfl.services.Llm.{Tool, ToolFunctionDef, ToolDefParams, ToolDefProperty}

object Tools {
  // A Tool runner couples an exposed tool definition with the Step it performs when called
  // The run function returns (steps, resultContent)
  // case class Runner(
  //   defn: Tool,
  //   run: (BaseValue[ToolCall], BaseValue[Double], Value[String]) => (List[Step[_, _]], BaseValue[String])
  // )

  // Shared param shapes
  private val updateFileParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      "filepath" -> ToolDefProperty("string"),
      "content"  -> ToolDefProperty("string")
    ),
    required = Field("""["filepath", "content"]""")
  )

  private val readFileParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      "filepath" -> ToolDefProperty("string")
    ),
    required = Field("""["filepath"]""")
  )

  private val runCommandParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      "command" -> ToolDefProperty("string")
    ),
    required = Field("""["command"]""")
  )

  // Public query-only params for simple "{ query }" tools
  val queryOnlyParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      "query" -> ToolDefProperty("string")
    ),
    required = Field("""["query"]""")
  )

  // Public params for general workflow runner tools supporting an optional task seed
  // task is an object that may include: title, description, status
  val queryWithTaskParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      "query" -> ToolDefProperty("string"),
      // We intentionally keep task as a generic object; concrete fields are documented in the description
      "task"  -> ToolDefProperty("object")
    ),
    required = Field("""["query"]""")
  )

  // Standard tools are executed via enqueueAndAwaitCallback
  // def standard: List[Runner] = {
  //   def enqueueRun(opName: String): (BaseValue[ToolCall], BaseValue[Double], Value[String]) => (List[Step[_, _]], BaseValue[String]) =
  //     (toolCall, cost, sessionId) => us.awfl.workflows.tools.CliTools.enqueueAndAwaitCallback(opName, toolCall, cost, sessionId).fn

  //   List(
  //     Runner(Tool(function = ToolFunctionDef(str("UPDATE_FILE"), "Update a file on disk with provided content.", updateFileParams)), enqueueRun("UPDATE_FILE")),
  //     Runner(Tool(function = ToolFunctionDef(str("READ_FILE"), "Read a file from disk.", readFileParams)), enqueueRun("READ_FILE")),
  //     Runner(Tool(function = ToolFunctionDef(str("RUN_COMMAND"), "Execute a shell command.", runCommandParams)), enqueueRun("RUN_COMMAND"))
  //   )
  // }

  // Helper to create a workflow-invoking Runner whose payload is { query, task? }
  // Note: When the optional `task` argument is provided, the queried workflow will create a task in its session
  // (via EventHandler.Input.task and Tasks.maybeSaveInputTask) before processing the query.
  // def invokerRunner(
  //   name: String,
  //   description: String,
  //   onQuery: Resolved[String] => Step[String, BaseValue[String]]
  // ): Runner = {
  //   val descWithTaskNote = s"$description Optional param: task (object with title, description, status). If provided, a task will be created for the queried workflow session before processing."
  //   val defn = Tool(function = ToolFunctionDef(name, descWithTaskNote, queryWithTaskParams))
  //   val runFn: (BaseValue[ToolCall], BaseValue[Double], Value[String]) => (List[Step[_, _]], BaseValue[String]) = { (toolCall, _, _) =>
  //     val function = toolCall.get.function.get
  //     val response = onQuery(function.arg("query"))
  //     val prefix: Cel = CelConst(s"${name}'s response:\n")
  //     List(response) -> str(prefix + response.resultValue.cel)
  //   }
  //   Runner(defn, runFn)
  // }

  // Merge standard tools with any custom runners provided by a workflow
  // def all(custom: List[Runner]): List[Runner] = standard ++ custom

  // def defs(runners: List[Runner]): List[Tool] = runners.map(_.defn)
}
