package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm.ToolChoice
import us.awfl.utils.Convo
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.SegKala
import us.awfl.utils.TopicContextYoj
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.ista.ToolCall
import us.awfl.workflows.tools.{CliTools, Tools}
import us.awfl.utils.Convo.ConvoContext
import us.awfl.utils.Yoj
import us.awfl.utils.Exec
import us.awfl.workflows.assistant.TopicContext
import us.awfl.utils.Locks
import us.awfl.workflows.helpers.{Tasks, ToolDefs, ToolDispatcher, Agents, Chain}
import us.awfl.utils.{Env, ENV}
import us.awfl.utils.Events
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

trait EventHandler extends us.awfl.core.Workflow with Prompts with Tools {
  override type Input = EventHandler.Input

  override val inputVal: Value[Input] = init[Input]("input")

  // Workflows callback request wrapper
  case class CallbackRequest(http_request: BaseValue[us.awfl.utils.PostRequest[NoValueT]])

  // Single entry point: tool-enabled chat that surfaces tool_calls
  def eventHandler(
    message: ChatMessage = ChatMessage("user", input.query),
    tools: List[String] = List()
  ): Workflow[ChatToolResponse] = {
    val model = inputVal.flatMap(_.env).get.model
    val userId = inputVal.flatMap(_.env).flatMap(_.userId)
    val sessionId = inputVal.flatMap(_.env).get.sessionId

    given KalaVibhaga = SegKala(sessionId, Value("sys.now()"), Value(20 * 60))

    // Best-effort: if an input.task object is provided, create it for this session before completion logic
    val maybeSaveTask = Tasks.maybeSaveInputTask("maybeSaveInputTask", CelFunc("map.get", inputVal.cel, "task"))

    val triggeredExecId: Value[String] = Exec.currentExecId

    // Mark this execution as Running at the start (best-effort, non-fatal if it fails)
    val statusRunning = Exec.updateExecStatus(
      "statusRunning",
      triggeredExecId,
      str("Running"),
      Value(false)
    )
    val enqueueRunning = Exec.enqueueExecStatus(
      "enqueueStatusRunning",
      str("Running")
    )

    // New: resolve agentId by session, then fetch that agent's tool names. Fall back to existing list if none.
    val sessionAgent = Agents.agentIdBySession("sessionAgent", sessionId)
    val agentTools = Agents.toolsByAgent("agentTools", sessionAgent.resultValue)

    // Choose names to query ToolDefs with:
    // - if agentTools returned names, use them
    // - otherwise, use previous default list
    // - if input.toolNames was provided, it overrides both (ToolDefs helper preserves names filter contract)
    val candidateNames = Switch.list("toolNamesCandidate", List(
      (CelFunc("len", agentTools.resultValue) > 0) -> (List() -> agentTools.resultValue),
      (true: Cel) -> (List() -> buildTools.resultValue)
    ))

    // Service-first tool defs with names filtering
    val toolDefs = ToolDefs("toolDefs", sessionId, candidateNames.resultValue)

    val sideCall = input.sideCall.getOrElse(Value(false))
    val bypassLock = sideCall
    val skipToolFeedback = sideCall
    val skipAddMessages = sideCall

    val owner: Value[String] = Exec.currentExecId
    // Use a session-scoped lock to prevent concurrent completions
    val lockKey = Locks.sessionKey("Convo")
    val acquiredTry = Switch("maybeBypassAquire", List(
      bypassLock.cel -> (List() -> Value[Boolean](true)),
      (true: Cel) -> Locks.acquireBool("acquireResponseLock", lockKey, owner).fn
    ))
    val release = Switch("maybeNBypassRelease", List(
      bypassLock.cel -> (List() -> Value.nil[PostResult[NoValueT]]),
      (true: Cel) -> Locks.release("releaseResponseLock", lockKey, owner).fn
    ))

    // Main body wrapped in Try so we can update status to Done or Failed
    val mainTry = buildSteps(
      {
        // Best-effort init status; don't fail the workflow if this call errors.
        val tryInitStatus = Try(
          "tryInitStatus",
          List[Step[_, _]](statusRunning, enqueueRunning) -> Value.nil[NoValueT]
        )

        val addMessage = Switch("maybeAddMessage", List(
          skipAddMessages.cel -> (List() -> Value.nil[PostResult[Nothing]]),
          (true: Cel) -> Convo.addMessage("addMessage", obj(message)).fn
        ))

        // In-session operations: write the query message first; then try to acquire a lock for response generation.
        val complete = Convo.inSession[ChatToolResponse]("inSession", sessionId) {
          val buildYojStep = summon[Yoj[TopicContext]].build
          val messages = Switch.list("messages", List(
            skipAddMessages.cel -> {
              val buildMessage = Try("buildMessage", List() -> obj(message))
              List(buildMessage) -> ListValue[ChatMessage](CelFunc("list.concat", buildYojStep.resultValue, buildMessage.resultValue))
            },
            (true: Cel) -> (List() -> buildYojStep.resultValue)
          ))
          val completeStep = Convo.completeWithTools(
            "complete",
            Convo.Prompt(promptsWorkflow.flatMapList(_.prompts)),
            buildYojStep.resultValue,
            tools = ListValue(toolDefs.result.defs.cel),
            toolChoice = input.toolChoice.getOrElse(ToolChoice.auto),
            model = model
          )
          val responseMessage = completeStep.result.message
          val addResponse  = Switch("mayberAddResponse", List(
            skipAddMessages.cel -> (List() -> Value.nil[PostResult[Nothing]]),
            (true: Cel) -> Convo.addMessage(
              "addResponse",
              obj(us.awfl.ista.ChatMessage(
                role = responseMessage.flatMap(_.role),
                content = responseMessage.flatMap(_.content),
                tool_calls = ListValue(CelFunc("map.get", responseMessage, "tool_calls")),
                create_time = Value("sys.now()")
              )),
              cost = completeStep.result.total_cost
            ).fn
          ))
          List[Step[_, _]](buildYojStep, completeStep, addResponse) -> completeStep.resultValue
        }

        val sendContents = Switch("sendContents", List(
          // Dispatch only message content via Pub/Sub; not a tool-call interaction
          ("content" in complete.result.message) -> Events.enqueueResponse("enqueueContent", Value("null"), complete.result.message.get.content, Value("null"), complete.result.total_cost).fn,
          (true: Cel) -> (List() -> Value("null"))
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
          toolDefs.result.defs
        )

        val toolsCost = Fold("toolsCost", Value[Double](0), processToolCalls.result.results) { case (b, toolResult) =>
          List() -> Value(b.cel + toolResult.get.cost.cel)
        }
        val totalCost = complete.result.total_cost.cel + toolsCost.resultValue.cel

        val maybeToolFeedback = Switch("maybeToolFeedback", List(
          ((CelFunc("len", processToolCalls.result.results) > 0) && !skipToolFeedback) -> {
            val newSpent = Value[Double](input.spent.getOrElse(Value(0)).cel + totalCost)
            val toolFeedbackArgs = RunWorkflowArgs(
              WORKFLOW_ID,
              obj(EventHandler.Input(str("Tool calls completed"), input.fund, OptValue(newSpent))),
              connector_params = ConnectorParams(true)
            )
            Call[RunWorkflowArgs[Input], ChatToolResponse](
              "toolCallsCompleted",
              "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
              obj(toolFeedbackArgs)
            ).fn
          },
          (true: Cel) -> {
            val statusDone = Exec.updateExecStatus(
              "statusDone",
              triggeredExecId,
              str("Done"),
              Value(true)
            )
            val enqueueDone = Exec.enqueueExecStatus(
              "enqueueStatusDone",
              str("Done")
            )
            List[Step[_, _]](statusDone, enqueueDone) -> complete.resultValue
          }
        ))

        val summaries = Summaries("runSummaries")
        val extract = Chain(
          ExtractTopics
            .runSync(obj(StriderInput()))
            .andThen(SaveReflection)(obj(SaveReflection.Params(WORKFLOW_ID)))
        )
        val collapse = ContextCollapser("collapseMessages", 60 * 4, 48)

        val whenAcquired = Switch("maybeRespond", List(
          // If lock acquired: build context, get response, save it, then release the lock.
          acquiredTry.resultValue.cel -> {
            List[Step[_, _]](
              sessionAgent,
              agentTools,
              buildTools,
              candidateNames,
              toolDefs,
              complete,
              sendContents,
              processToolCalls,
              release,
              toolsCost,
              maybeToolFeedback,
              summaries,
              extract,
              collapse
            ) -> maybeToolFeedback.resultValue
          },
          // If busy: exit without fetching a response (query message is already saved).
          (true: Cel) -> (List() -> Value.nil[ChatToolResponse])
        ))

        List[Step[_, _]](
          tryInitStatus,
          maybeSaveTask,
          addMessage,
          acquiredTry,
          whenAcquired,
        ) -> whenAcquired.resultValue
      },
      err => {
        val errMsg = str(CelFunc(
          "default",
          CelFunc(
            "default",
            CelFunc(
              "default",
              CelFunc("map.get", err.cel, CelConst("""["body", "error", "message"]""")),
              CelFunc("map.get", err.cel, CelConst("""["body", "error"]"""))
            ),
            CelFunc("map.get", err.cel, "message")
          ),
          "Unknown error"
        ))
        val statusFailed = Exec.updateExecStatus(
          "statusFailed",
          triggeredExecId,
          str("Failed"),
          Value(true),
          error = errMsg
        )
        val enqueueFailed = Exec.enqueueExecStatus(
          "enqueueStatusFailed",
          str("Failed"),
          error = errMsg
        )
        List[Step[_, _]](release, statusFailed, enqueueFailed) -> Value.nil[ChatToolResponse]
      }
    )

    Workflow(
      mainTry
    )
  }
}
object EventHandler {
  case class Input(
    query: Value[String],
    fund: Value[Double],
    spent: OptValue[Double],
    // Optional task payload to seed a task for this session (title/description/status)
    task: Value[String] = Value.nil,
    toolChoice: OptBase[ToolChoice] = OptValue.nil[ToolChoice],
    sideCall: OptValue[Boolean] = OptValue(false),
    env: BaseValue[Env] = ENV
  )
}
