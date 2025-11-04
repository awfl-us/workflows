package us.awfl.workflows.tools

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ToolCall
import us.awfl.utils.PostRequest
import us.awfl.workflows.EventHandler
import us.awfl.workflows.traits.ToolWorkflow
import us.awfl.utils.*

object CliTools extends us.awfl.workflows.traits.ToolWorkflow {
  // Event payload delivered to CLI via awfl-relay (data field)
  case class OperationEnvelope(
    create_time: BaseValue[String],
    callback_url: BaseValue[String],
    content: BaseValue[String],
    tool_call: BaseValue[ToolCall],
    cost: BaseValue[Double],
    background: BaseValue[Boolean] = Env.background.getOrElse(Value(false)),
    // Optional status update fields (backward-compatible defaults)
    status: BaseValue[String] = Field.str(""),
    error: BaseValue[String] = Value.nil
  )

  // Relay ingest body wrapper
  case class RelayEvent(
    sessionId: BaseValue[String],
    projectId: BaseValue[String],
    data: BaseValue[OperationEnvelope],
    background: BaseValue[Boolean] = Env.background.getOrElse(Value(false)),
    `type`: BaseValue[String] = Field.str("message"),
    source: BaseValue[String] = Field.str("workflows.tools.CliTools")
  )

  // Workflows callback helpers
  case class CreateCallbackArgs(http_callback_method: BaseValue[String])
  case class CallbackDetails(url: BaseValue[String])
  case class AwaitCallbackArgs(callback: BaseValue[CallbackDetails], timeout: BaseValue[Int])

  case class CallbackRequest(http_request: BaseValue[PostRequest[NoValueT]])

  val toolNames = List("READ_FILE", "UPDATE_FILE", "RUN_COMMAND")

  def postEvent(data: OperationEnvelope, source: BaseValue[String]) = {
    // POST to /workflows/events instead of Pub/Sub
    val ingestBody = RelayEvent(
      sessionId = Env.sessionId,
      projectId = Env.projectId,
      data = obj(data),
      background = Env.background.getOrElse(Value(false)),
      `type` = Field.str("message"),
      source = Field.str("workflows.tools.CliTools")
    )

    post[RelayEvent, NoValueT](
      "relay_ingest_tool_call",
      "events",
      obj(ingestBody)
    )
  }

  // Fire-and-forget enqueue for non-tool assistant content or status updates via awfl-relay
  def enqueueResponse(
    opName: String,
    callback_url: BaseValue[String],
    content: BaseValue[String],
    toolCall: BaseValue[ToolCall],
    cost: BaseValue[Double],
    background: BaseValue[Boolean] = Env.background.getOrElse(Value(false)),
    // Optional status and error for status updates
    status: BaseValue[String] = Field.str(""),
    error: BaseValue[String] = Value.nil,
  ): Step[NoValueT, BaseValue[NoValueT]] = {
    val opEnvelopeField = OperationEnvelope(
      create_time = Value("sys.now()"),
      callback_url = callback_url,
      content = content,
      tool_call = toolCall,
      cost = cost,
      background = background,
      status = status,
      error = error
    )

    // POST to awfl-relay ingest endpoint: /workflows/events
    val ingest = postEvent(
      data = opEnvelopeField,
      source = str(opName)
    )

    Block(s"${opName}_enqueue_block", List(ingest) -> Value.nil)
  }

  // Standalone workflow to handle tool calls: create callback, enqueue via relay ingest, and await callback
  override def workflows = List({
    val createCallback = Call[CreateCallbackArgs, CallbackDetails](
      s"createCallback",
      "events.create_callback_endpoint",
      obj(CreateCallbackArgs(Field.str("POST")))
    )

    val envelope = OperationEnvelope(
      create_time = Value("sys.now()"),
      callback_url = createCallback.resultValue.flatMap(_.url),
      content = Value("null"),
      tool_call = input.tool_call,
      cost = input.cost,
      background = Env.background.getOrElse(Value(false))
    )

    // POST to /workflows/events instead of Pub/Sub
    val ingest = postEvent(
      data = envelope,
      source = Field.str("workflows.tools.CliTools")
    )

    val awaitCallback = Call[AwaitCallbackArgs, CallbackRequest](
      s"awaitCallback",
      "events.await_callback",
      obj(AwaitCallbackArgs(createCallback.resultValue, obj(3600)))
    )

    val encodedCallback = str(
      CelFunc(
        "json.encode_to_string",
        awaitCallback.resultValue.flatMap(_.http_request).flatMap(_.body).cel
      )
    )

    Workflow(buildSteps(
      List[Step[_, _]](
        createCallback,
        ingest,
        Log("logIngest", str(("Post event result: ": Cel) + CelFunc("json.encode_to_string", ingest.resultValue.cel))),
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
    background: BaseValue[Boolean] = Env.background.getOrElse(Value(false))
  ) = {
    val args = RunWorkflowArgs(str("tools-CliTools${WORKFLOW_ENV}"), obj(ToolWorkflow.Input(toolCall, cost, env = obj(Env.get.copy(sessionId = sessionId, background = OptValue(background))))))
    val run = Call[RunWorkflowArgs[Input], Result](
      s"${opName}_run_tools_CliTools",
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(args)
    )

    Block(s"${opName}_callback_block", List[Step[_, _]](run) -> run.resultValue.flatMap(_.encoded))
  }
}
