package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.SegKala
import us.awfl.utils.TopicContextYoj
import us.awfl.services.Llm.ChatResponse
import us.awfl.ista.ToolCall
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.Exec
import us.awfl.utils.Locks
import us.awfl.workflows.helpers.{Tasks, ToolDefs, ToolDispatcher, Agents, Chain}
import us.awfl.utils.{Env, ENV}
import us.awfl.utils.Events
import us.awfl.utils.Callback
import us.awfl.workflows.cli.CliActions
import us.awfl.workflows.Summaries
import us.awfl.workflows.assistant.ExtractTopics
import us.awfl.workflows.context.ContextCollapser
import us.awfl.workflows.traits.Agent
import us.awfl.workflows.traits.Tools
import us.awfl.workflows.traits.Prompts
import us.awfl.workflows.traits.Preloads
import us.awfl.utils.strider.StriderInput
import us.awfl.workflows.helpers.links.SaveReflection
import us.awfl.utils.PostResult
import us.awfl.services.Llm.{Tool, Usage}
import us.awfl.workflows.helpers.ToolDefs.ToolDefValue
import us.awfl.services.Llm.ToolDefProperty
import us.awfl.workflows.helpers.ToolDefs.ToolDefStr
import us.awfl.workflows.helpers.ToolDefs.ToolDefEnum
import us.awfl.workflows.helpers.ToolDefs.ToolDefObj
import scala.annotation.tailrec
import us.awfl.services.Llm.ToolFunctionDef
import us.awfl.workflows.tools.Tasks.Task
import us.awfl.workflows.event.Recursion
import us.awfl.utils.Sadhaka
import us.awfl.utils.Ista
import us.awfl.core.WorkflowId
import us.awfl.services.Context
import us.awfl.utils.Yoj
import us.awfl.workflows.helpers.{Convo, ConvoInput}

trait EventHandler extends us.awfl.core.Workflow with EventHandler.WithInput with Prompts with Tools with Recursion {
  override type Result = ChatResponse[ChatMessage]
  // Single entry point: tool-enabled chat that surfaces tool_calls
  def eventHandler(
    // message: OptValue[ChatMessage],
    // tools: List[String] = List()
  ): (List[Step[?, ?]], BaseValue[ChatResponse[ChatMessage]]) = {
    val userId = inputVal.flatMap(_.env).flatMap(_.userId)

    // Best-effort: if an input.task object is provided, create it for this session before completion logic
    val maybeSaveTask = Tasks.maybeSaveInputTask("maybeSaveInputTask", inputVal.flatMap(_.task.getOrElse(Value.nil)))

    val mapToolDefs = For("mapToolDefs", toolsWorkflow.resultValue.flatMap(_.tools)) { toolDef =>
      List(Log("logTool", str(encodeJson(toolDef)))) -> obj(Tool(
        toolDef.get.`type`,
        toolDef.get.function
      ))
    }

    val sideCall = input.sideCall.getOrElse(Value(false))
    val bypassLock = sideCall
    val skipToolFeedback = sideCall
    val skipAddMessages = sideCall

    val buildKala = Switch("buildKala", List(
      input.kala.present.cel -> (List() -> input.kala.getOrElse(Value.nil)),
      (true: Cel) -> (List() -> obj[KalaVibhaga](SegKala(
        sessionId,
        Value("sys.now()"),
        Value(20 * 60)
      )))
    ))

    val owner: Value[String] = Exec.currentExecId
    // Use a session-scoped lock to prevent concurrent completions
    val buildLockKey = Locks.buildSessionKey("Convo", buildKala.resultValue)
    val acquiredTry = Switch("maybeBypassAquire", List(
      bypassLock.cel -> (List() -> Value[Boolean](true)),
      (true: Cel) -> Locks.acquireBool("acquireResponseLock", buildLockKey.result, owner).fn
    ))
    val release = Switch("maybeNBypassRelease", List(
      bypassLock.cel -> (List() -> Value.nil[PostResult[NoValueT]]),
      (true: Cel) -> Locks.release("releaseResponseLock", buildLockKey.result, owner).fn
    ))

    // Main body wrapped in Try so we can update status to Done or Failed
    buildSteps(
      {
        val encodeKala = TopicContextYoj.encodeKalaVal(buildKala.resultValue)
        val complete = Convo(ConvoInput(
          query = input.query,
          prompts = promptsWorkflow.resultValue.flatMap(_.prompts),
          tools = mapToolDefs.resultValue,
          toolChoice = input.toolChoice,
          skipAddMessages = skipAddMessages,
          kala = encodeKala.resultValue
        ))

        val sendContents = Switch("sendContents", List(
          // Dispatch only message content via Pub/Sub; not a tool-call interaction
          ("content" in complete.result.message) -> Events.enqueueResponse("enqueueContent", Value("null"), complete.result.message.get.content, Value("null"), complete.result.total_cost).fn,
          (true: Cel) -> (List() -> Value.nil)
        ))

        val toolCalls = ListValue[ToolCall](
          CelFunc(
            "default",
            CelFunc("map.get", complete.resultValue.flatMap(_.message).cel, "tool_calls"),
            CelConst("[]")
          )
        )

        // Trigger ToolDispatcher as a separate workflow to keep this definition small
        val processToolCalls = ToolDispatcher(
          "processToolCalls",
          complete.resultValue.flatMap(_.total_cost),
          toolCalls,
          toolsWorkflow.resultValue.flatMap(_.tools)
        )

        val toolsCost = Fold("toolsCost", Value[Double](0), processToolCalls.result.results) { case (b, toolResult) =>
          List() -> Value(b.cel + toolResult.get.cost.cel)
        }
        val totalCost = complete.result.total_cost.cel + toolsCost.resultValue.cel
        val newSpent = Value[Double](input.spent.getOrElse(Value(0)).cel + totalCost)
        val addUsage = Switch("addUsage", List(
          (input.usage.getOrElse(Value.nil) !== Cel.nil) ->
            input.usage.getOrElse(Value.nil).get.plus(complete.result.usage).fn,
          (true: Cel) -> (List() -> complete.result.usage)
        ))
        val responseWithCost = obj(complete.result.copy(
          total_cost = newSpent,
          usage = addUsage.resultValue
        ))

        val maybeToolFeedback = Switch("maybeToolFeedback", List(
          ((CelFunc("len", processToolCalls.result.results) > 0) && !skipToolFeedback) -> {
            val toolFeedbackArgs = RunWorkflowArgs(
              WORKFLOW_ID,
              obj(EventHandler.Input(
                query = OptValue(str("Tool calls completed")),
                fund = input.fund,
                spent = OptValue(newSpent),
                callback = input.callback,
                kala = input.kala
              )),
              connector_params = ConnectorParams(skip_polling = true)
            )
            Call[RunWorkflowArgs[Input], ChatResponse[ChatMessage]](
              "toolCallsCompleted",
              "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
              obj(toolFeedbackArgs)
            ).fn
          },
          (true: Cel) -> {
            val maybeCallback = Switch("maybeCallback", List(
              (input.callback.getOrElse(Value.nil) !== Cel.nil) ->
                input.callback.getOrElse(Value.nil).get.apply(responseWithCost).fn,
              (true: Cel) -> (List() -> Value.nil)
            ))
            List[Step[?, ?]](stop, maybeCallback) -> responseWithCost
          }
        ))

        val whenAcquired = Switch("maybeRespond", List(
          // If lock acquired: build context, get response, save it, then release the lock.
          acquiredTry.resultValue.cel -> {
            List[Step[?, ?]](
              Parallel("loadContext", List(
                promptsWorkflow,
                toolsWorkflow,
                encodeKala,
              )),
              mapToolDefs,
              complete,
              Parallel("followup", List(
                sendContents,
                processToolCalls,
                release
              )),
              Parallel("calcStats", List(
                toolsCost,
                addUsage
              )),
              maybeToolFeedback,
            ) -> obj(complete.result.copy(
              total_cost = Value[Double](totalCost)
            ))
          },
          // If busy: exit without fetching a response (query message is already saved).
          (true: Cel) -> (List() -> Value.nil[ChatResponse[ChatMessage]])
        ))

        List[Step[?, ?]](
          maybeSaveTask,
          buildKala,
          buildLockKey,
          acquiredTry,
          whenAcquired,
        ) -> whenAcquired.resultValue
      },
      err => {
        val callbackFailure = Switch("callbackFailure", List(
          (input.callback.getOrElse(Value.nil) !== Cel.nil) -> input.callback.getOrElse(Value.nil).get.failure(err).fn,
          (true: Cel) -> (List() -> Value.nil)
        ))
        List[Step[?, ?]](release, callbackFailure) -> Value.nil[ChatResponse[ChatMessage]]
      }
    )
  }
}
object EventHandler {
  case class Input(
    query: OptValue[String],
    fund: Value[Double],
    spent: OptValue[Double] = OptValue(0),
    usage: OptValue[Usage] = OptValue.nil,
    // Optional task payload to seed a task for this session (title/description/status)
    task: OptBase[Task] = OptValue.nil,
    toolChoice: OptBase[Tool] = OptValue.nil,
    sideCall: OptValue[Boolean] = OptValue(false),
    callback: OptValue[Callback[ChatResponse[ChatMessage]]] = OptValue.nil,
    kala: OptBase[KalaVibhaga] = OptValue.nil,
    env: BaseValue[Env] = ENV
  )

  given Sadhaka[Input] = new Sadhaka {
    override def complete[In: Yoj, Out: Ista: Spec](
      query: String,
      workflowId: WorkflowId[Input, ChatResponse[ChatMessage]]
    )(implicit kala: KalaVibhaga): Step[ChatResponse[Out], Value[ChatResponse[Out]]] = {
      // val ctxKala = TopicContextYoj.encodeKala(kala)
      val toolChoice: Option[OptValue[Tool]] = summon[Ista[Out]].tool(kala).map {
//        case ListValue(_) => sys.error("Unexpected ListValue for toolChoice")
        case Obj(v) => OptValue(Obj(v))
//        case Value(r) => OptValue(Value[Tool](r))
      }
      val complete = Callback[ChatResponse[ChatMessage]] { callback =>
        val input = Input(
          query = OptValue(str(query)),
          fund = Value[Double](0.1),
          toolChoice = toolChoice.getOrElse(OptValue.nil),
          sideCall = OptValue(obj(true)),
          callback = OptValue(obj(callback)),
          kala = OptValue(obj(kala))
        )
        val args = RunWorkflowArgs(workflowId.workflowName, obj(input))
        Call[RunWorkflowArgs[EventHandler.Input], ChatResponse[ChatMessage]](
          "eventHandlerComplete",
          "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
          obj(args)
        )
      }
      val result = obj(complete.result.copy(message = Value[Out](CelFunc(
        "json.decode",
        complete.result.message.flatMap(_.tool_calls(0)).flatMap(_.function).flatMap(_.arguments)
      ))))
      Try("eventHandlerTry", List(complete) -> result)
    }
  }

  trait WithInput extends us.awfl.core.Workflow {
    override type Input = EventHandler.Input

    override val inputVal: Value[Input] = init[Input]("input")

    val sessionId = inputVal.flatMap(_.env).get.sessionId
  }
}
