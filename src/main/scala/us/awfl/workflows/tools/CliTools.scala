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

object CliTools extends us.awfl.workflows.traits.ToolWorkflow {
  // Workflows callback helpers
  case class CreateCallbackArgs(http_callback_method: BaseValue[String])
  case class CallbackDetails(url: BaseValue[String])
  case class AwaitCallbackArgs(callback: BaseValue[CallbackDetails], timeout: BaseValue[Int])

  case class CallbackRequest(http_request: BaseValue[PostRequest[NoValueT]])

  // jobs/callbacks service payloads
  case class CreateJobsCallbackBody(callback_url: BaseValue[String])
  case class CreateCallbackResponse(id: Value[String])

  case class ProducerRequest(sessionId: Value[String] = Value.nil)

  val toolNames = List("READ_FILE", "UPDATE_FILE", "RUN_COMMAND")
 
  val buildTools = buildList("buildCliTools", List(
    ToolWithWorkflow(
      function = ToolDefFunc(
        str("READ_FILE"),
        "Read a file from disk.",
        ToolDefObj(
          properties = Map(
            "filepath" -> ToolDefStr
          ),
          required = ListValue(List("filepath").cel)
        )
      ).toLlm,
      workflowName = str(workflowName)
    ),
    ToolWithWorkflow(
      function = ToolDefFunc(
        str("RUN_COMMAND"),
        "Execute a shell command.",
        ToolDefObj(
          properties = Map(
            "command" -> ToolDefStr
          ),
          required = ListValue(List("command").cel)
        )
      ).toLlm,
      workflowName = str(workflowName)
    ),
    ToolWithWorkflow(
      function = ToolDefFunc(
        str("UPDATE_FILE"),
        "Update a file on disk with provided content",
        ToolDefObj(
          properties = Map(
            "filepath" -> ToolDefStr,
            "content" -> ToolDefStr
          ),
          required = ListValue(List("filepath", "content").cel)
        )
      ).toLlm,
      workflowName = str(workflowName)
    )
  ))

  // Standalone workflow to handle tool calls: create callback, persist it with an ID, enqueue via relay ingest, and await callback
  override def workflows = List({
    val createCallback = Call[CreateCallbackArgs, CallbackDetails](
      s"createCallback",
      "events.create_callback_endpoint",
      obj(CreateCallbackArgs(str("POST")))
    )

    // Save the callback on our server to receive a callback ID
    // POST /jobs/callbacks with { callback_url }
    val saveCallback = post[CreateJobsCallbackBody, CreateCallbackResponse](
      "saveCallback",
      "callbacks",
      obj(CreateJobsCallbackBody(createCallback.resultValue.flatMap(_.url)))
    )

    val envelope = OperationEnvelope(
      create_time = Value("sys.now()"),
      callback_id = saveCallback.result.body.get.id,
      content = Value("null"),
      tool_call = input.tool_call,
      cost = input.cost,
      background = Env.background.getOrElse(Value(false))
    )

    // POST to /workflows/events instead of Pub/Sub
    val ingest = Events.postEvent(
      data = envelope,
      source = str("workflows.tools.CliTools")
    )

    val maybeStartProducer = post[ProducerRequest, NoValueT]("maybeStartProducer", "producer/start", obj(ProducerRequest()))

    val awaitCallback = Call[AwaitCallbackArgs, CallbackRequest](
      s"awaitCallback",
      "events.await_callback",
      obj(AwaitCallbackArgs(createCallback.resultValue, obj(3600)))
    )

    val encodedCallback = str(
      CelFunc(
        "json.encode_to_string",
        awaitCallback.resultValue
          .flatMap(_.http_request)
          .flatMap(_.body)
          .cel
      )
    )

    Workflow(buildSteps(
      List[Step[?, ?]](
        createCallback,
        saveCallback,
        Log("logSavedCallback", str(("[saveCallback] Saved callback: id=": Cel) + saveCallback.result.body.get.id)),
        ingest,
        Log("logIngest", str(("[ingest] Post event result: ": Cel) + CelFunc("json.encode_to_string", ingest.resultValue.cel))),
        maybeStartProducer,
        awaitCallback
      ) -> obj(ToolWorkflow.Result(encodedCallback, Value(0))),
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
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(args)
    )

    Block(s"${opName}_callback_block", List[Step[?, ?]](run) -> run.resultValue.flatMap(_.encoded))
  }

  // High-level helpers using ToolWorkflow generic methods
  private val targetCliToolsWorkflow = str("tools-CliTools${WORKFLOW_ENV}")

  def readFile(
    filepath: BaseValue[String],
    opName: String = "readFile",
    cost: BaseValue[Double] = obj(0.0),
    sessionId: Value[String] = Env.sessionId,
    background: Value[Boolean] = Env.background.getOrElse(Value(false))
  ) = {
    val encoded = callToolEncoded(
      opName = opName,
      targetWorkflowName = targetCliToolsWorkflow,
      functionName = "READ_FILE",
      params = List("filepath" -> filepath),
      cost = cost,
      sessionId = sessionId,
      background = background
    )
    Block(s"${opName}_decode_content", List[Step[?, ?]](encoded) -> decodeField(encoded.resultValue, "content"))
  }

  def writeFile(
    filepath: BaseValue[String],
    content: BaseValue[String],
    opName: String = "writeFile",
    cost: BaseValue[Double] = obj(0.0),
    sessionId: Value[String] = Env.sessionId,
    background: Value[Boolean] = Env.background.getOrElse(Value(false))
  ) = {
    val encoded = callToolEncoded(
      opName = opName,
      targetWorkflowName = targetCliToolsWorkflow,
      functionName = "UPDATE_FILE",
      params = List("filepath" -> filepath, "content" -> content),
      cost = cost,
      sessionId = sessionId,
      background = background
    )
    // Return the encoded callback body (not used by callers typically)
    Block(s"${opName}_encoded", List[Step[?, ?]](encoded) -> encoded.resultValue)
  }

  def runCommand(
    command: BaseValue[String],
    opName: String = "runCommand",
    cost: BaseValue[Double] = obj(0.0),
    sessionId: Value[String] = Env.sessionId,
    background: Value[Boolean] = Env.background.getOrElse(Value(false))
  ) = {
    val encoded = callToolEncoded(
      opName = opName,
      targetWorkflowName = targetCliToolsWorkflow,
      functionName = "RUN_COMMAND",
      params = List("command" -> command),
      cost = cost,
      sessionId = sessionId,
      background = background
    )
    // Prefer decoding the common 'output' field if present; otherwise return raw encoded
    val out = decodeField(encoded.resultValue, "output")
    Block(s"${opName}_output", List[Step[?, ?]](encoded) -> out)
  }
}
