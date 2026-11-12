package us.awfl.workflows.tools

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ToolCall
import us.awfl.utils.PostRequest
import us.awfl.workflows.EventHandler
import us.awfl.workflows.traits.ToolWorkflow
import us.awfl.utils.*
import us.awfl.utils.Events
import us.awfl.utils.Events.OperationEnvelope
import us.awfl.utils.post
import us.awfl.workflows.helpers.ToolDefs._
import us.awfl.services.Cloud

object CliTools extends us.awfl.workflows.traits.ToolWorkflow {
  case class ProducerRequest(sessionId: Value[String] = Value.nil)

  val toolNames = CliToolDefs.toolNames

  val buildTools = CliToolDefs.buildTools(str(workflowName))

  // Standalone workflow to handle tool calls: create callback, persist it with an ID, enqueue via relay ingest, and await callback
  override def workflows = List({
    // CLI execution errors are tool results, not fatal workflow exceptions.
    // The dispatcher must persist them as tool messages so agents can recover.
    val awaitCallback = Callback.toolResult[NoValueT] { callback =>
      val envelope = OperationEnvelope(
        create_time = Value(time.now()),
        callback_id = callback.id,
        content = Value("null"),
        tool_call = input.tool_call,
        cost = input.cost,
        background = Env.background.getOrElse(Value(false)),
        workdir = Env.workdir.getOrElse(Value.nil),
        timeout_seconds = input.timeoutSeconds.getOrElse(Value.nil)
      )

      // POST to /workflows/events instead of Pub/Sub
      val ingest = Events.postEvent(
        data = envelope,
        source = str("workflows.tools.CliTools")
      )

      Block("awaitCallback", List[Step[?,?]](ingest, Cloud.start(Env.get, Value.nil)) -> noValue)
    }

    Workflow(buildSteps(
      List[Step[?, ?]](awaitCallback) -> obj(ToolWorkflow.Result(
        str(json.encode_to_string( awaitCallback.resultValue)),
        Value(0)
      )),
      _ => List() -> Value.nil[Result]
    ))
  })

  // Encapsulate running the tools-CliTools workflow and returning the encoded callback body
  def enqueueAndAwaitCallback(
    opName: String,
    toolCall: BaseValue[ToolCall],
    cost: BaseValue[Double],
    sessionId: Value[String] = Env.sessionId,
    background: Value[Boolean] = Env.background.getOrElse(Value(false))
  ) = {
    val args = RunWorkflowArgs(str("tools-CliTools${WORKFLOW_ENV}"), obj(ToolWorkflow.Input(toolCall, cost, env = obj(Env.get.copy(sessionId = sessionId, background = OptValue(background))))))
    val run = Call[RunWorkflowArgs[Input], Result](
      s"${opName}_run_tools_CliTools",
      CallOperations.WorkflowRun,
      obj(args)
    )

    Block(s"${opName}_callback_block", List[Step[?, ?]](run) -> run.resultValue.flatMap(_.encoded))
  }

  // High-level helpers using ToolWorkflow generic methods
  private val targetCliToolsWorkflow = str("tools-CliTools${WORKFLOW_ENV}")

  private def invokeTool(
    opName: String,
    functionName: String,
    params: Seq[(String, BaseValue[?])],
    cost: BaseValue[Double],
    env: BaseValue[Env]
  ) = callToolEncoded(opName, targetCliToolsWorkflow, functionName, params, cost, env = env)

  def readFile(
    filepath: BaseValue[String],
    opName: String = "readFile",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = {
    val encoded = callToolEncoded(
      opName = opName,
      targetWorkflowName = targetCliToolsWorkflow,
      functionName = "READ_FILE",
      params = List("filepath" -> filepath),
      cost = cost,
      env = env
    )
    Block(s"${opName}_decode_content", List[Step[?, ?]](encoded) -> decodeField(encoded.resultValue, "content"))
  }

  def writeFile(
    filepath: BaseValue[String],
    content: BaseValue[String],
    opName: String = "writeFile",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = {
    val encoded = callToolEncoded(
      opName = opName,
      targetWorkflowName = targetCliToolsWorkflow,
      functionName = "UPDATE_FILE",
      params = List("filepath" -> filepath, "content" -> content),
      cost = cost,
      env = env
    )
    // Return the encoded callback body (not used by callers typically)
    Block(s"${opName}_encoded", List[Step[?, ?]](encoded) -> encoded.resultValue)
  }

  /** Use a multi-file patch for precise edits instead of rewriting whole files.
    * Returns the encoded result, including changed file paths and hashes.
    */
  def applyPatch(
    patch: BaseValue[String],
    opName: String = "applyPatch",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "APPLY_PATCH", List("patch" -> patch), cost, env)

  /** Bounded repository search, with results returned as encoded JSON. */
  def searchFiles(
    pattern: BaseValue[String],
    root: BaseValue[String] = str("."),
    opName: String = "searchFiles",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "SEARCH_FILES", List("pattern" -> pattern, "root" -> root), cost, env)

  /** Read directory entries without spawning a shell. */
  def listDirectory(
    path: BaseValue[String] = str("."),
    opName: String = "listDirectory",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "LIST_DIRECTORY", List("path" -> path), cost, env)

  def readFileRange(
    filepath: BaseValue[String],
    startLine: BaseValue[Int],
    endLine: BaseValue[Int],
    opName: String = "readFileRange",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = {
    val encoded = invokeTool(opName, "READ_FILE", List(
      "filepath" -> filepath, "start_line" -> startLine,
      "end_line" -> endLine, "include_line_numbers" -> obj(true)
    ), cost, env)
    Block(s"${opName}_content", List[Step[?, ?]](encoded) ->
      decodeField(encoded.resultValue, "content"))
  }

  def writeFileIfUnchanged(
    filepath: BaseValue[String],
    content: BaseValue[String],
    expectedHash: BaseValue[String],
    opName: String = "writeFileIfUnchanged",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "UPDATE_FILE", List(
    "filepath" -> filepath, "content" -> content, "expected_hash" -> expectedHash
  ), cost, env)

  /** Start a durable process and immediately return its execution ID. */
  def startCommand(
    command: BaseValue[String],
    opName: String = "startCommand",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "RUN_COMMAND", List(
    "command" -> command, "yield_time_ms" -> obj(0)
  ), cost, env)

  def commandOutput(
    executionId: BaseValue[String],
    cursor: BaseValue[Int] = obj(0),
    opName: String = "commandOutput",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "COMMAND_OUTPUT", List(
    "execution_id" -> executionId, "cursor" -> cursor
  ), cost, env)

  def cancelCommand(
    executionId: BaseValue[String],
    opName: String = "cancelCommand",
    cost: BaseValue[Double] = obj(0.0),
    env: BaseValue[Env] = ENV
  ) = invokeTool(opName, "COMMAND_CANCEL", List("execution_id" -> executionId), cost, env)

  def runCommand(
    command: BaseValue[String],
    opName: String = "runCommand",
    cost: BaseValue[Double] = obj(0.0),
    raiseError: Boolean = false,
    timeoutSeconds: Value[Int] = Value(60),
    env: BaseValue[Env] = ENV
  ) = {
    val encoded = callToolEncoded(
      opName = opName,
      targetWorkflowName = targetCliToolsWorkflow,
      functionName = "RUN_COMMAND",
      params = List("command" -> command),
      cost = cost,
      timeoutSeconds = timeoutSeconds,
      env = env
    )
    val exitCode = decodeField(encoded.resultValue, "exitCode")
    val error = decodeField(encoded.resultValue, "error")
    val output = decodeField(encoded.resultValue, "output")
    val handleError = Switch("checkError", List(
      (exitCode.cel === 0) ->
        (List() -> output),
      (true: Cel) -> (
        if (raiseError) List(Raise("raiseError", obj(Error(error, OptValue[Int](exitCode.cel))))) -> Value.nil[String]
        else List() -> output
      )
    ))
    Block(s"${opName}_output", List[Step[?, ?]](encoded, handleError) -> handleError.resultValue)
  }
}
