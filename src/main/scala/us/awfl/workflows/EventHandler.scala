package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.utils.Convo
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.SegKala
import us.awfl.workflows.assistant.TopicContextYoj
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.ista.ToolCall
import us.awfl.workflows.tools.{CliTools, Tools}
import us.awfl.utils.Convo.ConvoContext
import us.awfl.utils.Yoj
import us.awfl.utils.Exec
import us.awfl.workflows.assistant.TopicContext
import us.awfl.utils.Locks
import us.awfl.workflows.helpers.{Tasks, ToolDefs, ToolDispatcher, Agents}
import us.awfl.utils.{Env, ENV}
import us.awfl.workflows.cli.CliActions
import us.awfl.workflows.Summaries
import us.awfl.workflows.assistant.ExtractTopics
import us.awfl.workflows.context.ContextCollapser
import us.awfl.workflows.traits.Agent
import us.awfl.workflows.traits.Tools
import us.awfl.workflows.traits.Prompts
import us.awfl.workflows.traits.Preloads

trait EventHandler extends us.awfl.core.Workflow with Prompts with Tools {
  override case class Input(
    query: BaseValue[String],
    fund: BaseValue[Double],
    // Optional task payload to seed a task for this session (title/description/status)
    task: Field = Field("null"),
    env: BaseValue[Env] = ENV
  )

  override val inputVal: BaseValue[Input] = init[Input]("input")
  override def workflows = super.workflows

  // Workflows callback request wrapper
  case class CallbackRequest(http_request: BaseValue[us.awfl.utils.PostRequest[NoValueT]])

  // Single entry point: tool-enabled chat that surfaces tool_calls
  def eventHandler(
    message: ChatMessage = ChatMessage("user", input.query),
    tools: List[String] = List()
  ): Workflow[ChatToolResponse] = {
    val model = input.env.get.model
    val userId = input.env.flatMap(_.userId)
    val sessionId = input.env.get.sessionId

    given KalaVibhaga = SegKala(sessionId, Value("sys.now()"), obj(20 * 60))

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
    val candidateNames = Switch("toolNamesCandidate", List(
      (CelFunc("len", agentTools.resultValue) > 0) -> (List() -> agentTools.resultValue),
      (true: Cel) -> (List() -> buildTools.resultValue)
    ))

    // Service-first tool defs with names filtering
    val toolDefs = ToolDefs("toolDefs", sessionId, candidateNames.resultValue)

    val owner: Value[String] = Exec.currentExecId
    // Use a session-scoped lock to prevent concurrent completions
    val lockKey = Locks.sessionKey("Convo")
    val acquiredTry = Locks.acquireBool("acquireResponseLock", lockKey, owner)
    val release = Locks.release("releaseResponseLock", lockKey, owner)

    // Main body wrapped in Try so we can update status to Done or Failed
    val mainTry = buildSteps(
      {
        // Best-effort init status; don't fail the workflow if this call errors.
        val tryInitStatus = Try(
          "tryInitStatus",
          List[Step[_, _]](statusRunning, enqueueRunning) -> Value.nil[NoValueT]
        )

        val addMessage = Convo.addMessage("addMessage", obj(message))

        // In-session operations: write the query message first; then try to acquire a lock for response generation.
        val complete = Convo.inSession[ChatToolResponse]("inSession", sessionId) {
          val buildYojStep = summon[Yoj[TopicContext]].build
          val completeStep = Convo.completeWithTools(
            "complete",
            Convo.Prompt(promptsWorkflow.flatMap(_.prompts)),
            buildYojStep.resultValue,
            tools = ListValue(toolDefs.result.defs.cel),
            model = model
          )
          val responseMessage = completeStep.result.message
          val addResponse  = Convo.addMessage(
            "addResponse",
            obj(us.awfl.ista.ChatMessage(
              role = responseMessage.flatMap(_.role),
              content = responseMessage.flatMap(_.content),
              tool_calls = ListValue(CelFunc("map.get", responseMessage, "tool_calls")),
              create_time = Value("sys.now()")
            )),
            cost = completeStep.result.total_cost
          )
          List[Step[_, _]](buildYojStep, completeStep, addResponse) -> completeStep.resultValue
        }

        val sendContents = Switch("sendContents", List(
          // Dispatch only message content via Pub/Sub; not a tool-call interaction
          ("content" in complete.result.message) -> CliTools.enqueueResponse("enqueueContent", Value("null"), complete.result.message.get.content, Value("null"), complete.result.total_cost).fn,
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
          (CelFunc("len", processToolCalls.result.results) > 0) -> {
            val remainingFunds = Value[Double](input.fund.cel - totalCost)
            val toolFeedbackArgs = RunWorkflowArgs(
              WORKFLOW_ID,
              obj(Input(str("Tool calls completed"), remainingFunds))
            )
            Call[RunWorkflowArgs[Input], ChatToolResponse](
              "toolCallsCompleted",
              "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
              obj(toolFeedbackArgs)
            ).fn
          },
          (true: Cel) -> (List() -> complete.resultValue)
        ))

        val summaries = Summaries("runSummaries", sessionId)
        val extract = ExtractTopics("extractTopics", sessionId)
        val collapse = ContextCollapser("collapseMessages", sessionId, 60 * 4, 48)

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
              collapse,
              statusDone,
              enqueueDone
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
        val errMsg = str(CelFunc("default", CelFunc("map.get", err.cel, "message"), "Unknown error"))
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
