package us.awfl.workflows

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm.ToolChoice
import us.awfl.utils.Convo
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.Sadhaka
import us.awfl.utils.Ista
import us.awfl.utils.SegKala
import us.awfl.utils.TopicContextYoj
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.ista.ToolCall
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.Convo.ConvoContext
import us.awfl.utils.Yoj
import us.awfl.utils.Exec
import us.awfl.workflows.assistant.TopicContext
import us.awfl.utils.Locks
import us.awfl.workflows.helpers.{Tasks, ToolDefs, ToolDispatcher, Agents, Chain, Convo as ConvoWorkflow}
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
import us.awfl.workflows.helpers.ToolDefs.ToolWithWorkflow
import us.awfl.services.Llm.{Tool, Usage}
import us.awfl.workflows.helpers.ToolDefs.ToolDefValue
import us.awfl.services.Llm.ToolDefProperty
import us.awfl.workflows.helpers.ToolDefs.ToolDefStr
import us.awfl.workflows.helpers.ToolDefs.ToolDefEnum
import us.awfl.workflows.helpers.ToolDefs.ToolDefObj
import scala.annotation.tailrec
import us.awfl.services.Llm.ToolFunctionDef
import us.awfl.workflows.tools.Tasks.Task
import us.awfl.workflows.event.Status

trait EventHandler extends us.awfl.core.Workflow with EventHandler.WithInput with Prompts with Tools with Status {
  // Single entry point: tool-enabled chat that surfaces tool_calls
  def eventHandler(
    message: ChatMessage = ChatMessage("user", input.query.getOrElse(str(""))),
    tools: List[String] = List()
  ): Workflow[ChatToolResponse] = {
    val model = inputVal.flatMap(_.env).get.model
    val userId = inputVal.flatMap(_.env).flatMap(_.userId)

    given KalaVibhaga = SegKala(sessionId, Value(time.now()), Value(20 * 60))

    // Best-effort: if an input.task object is provided, create it for this session before completion logic
    val maybeSaveTask = Tasks.maybeSaveInputTask("maybeSaveInputTask", inputVal.flatMap(_.task.getOrElse(Value.nil)))

    val mapToolDefs = For("mapToolDefs", toolsWorkflow.resultValue.flatMap(_.tools)) { toolDef =>
      List(Log("logTool", str(encodeJson(toolDef)))) -> obj(Tool(
        toolDef.get.`type`,
        toolDef.get.function
      ))
    }

    // A side-call requesting a typed Ista output must declare that function
    // in the provider-visible tools as well as choosing its name. The schema
    // stays in the Jobs LLM request; routing information stays server-side.
    val withIstaTool = Switch.list("withIstaTool", List(
      (input.typedTool.getOrElse(Value.nil[Tool]) !== Cel.nil) ->
        (List() -> ListValue[Tool](list.concat(
          mapToolDefs.resultValue.cel,
          input.typedTool.getOrElse(Value.nil[Tool]).cel
        ))),
      (true: Cel) -> (List() -> mapToolDefs.resultValue)
    ))

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

    // Status wraps buildSteps and Recursion.stop; EventHandler owns lock/callback handling.
    val mainTry = buildSteps(
      {
        // Tool-call continuations have no query: resume from the stored tool
        // results without synthesizing a new user message.
        val addMessage = Switch("maybeAddMessage", List(
          ((input.query.getOrElse(Value.nil[String]) !== Cel.nil) && !skipAddMessages) ->
            Convo.addMessage("addMessage", obj(message)).fn,
          (true: Cel) -> (List() -> Value.nil[PostResult[Nothing]])
        ))

        // The wip-old Convo boundary owns context construction, LLM completion,
        // and assistant response persistence. Keep the user turn in the parent
        // after lock acquisition, preventing lost turns on session contention.
        val complete = ConvoWorkflow(
          "completeConvo",
          promptsWorkflow.result.prompts,
          withIstaTool.resultValue,
          input.toolChoice.getOrElse(ToolChoice.auto),
          skipAddMessages,
          input.sideCallContext
        )

        val sendContents = Switch("sendContents", List(
          // Dispatch only message content via Pub/Sub; not a tool-call interaction
          ("content" in complete.result.message) -> Events.enqueueResponse("enqueueContent", Value("null"), complete.result.message.get.content, Value("null"), complete.result.total_cost).fn,
          (true: Cel) -> (List() -> Value("null"))
        ))

        val toolCalls = ListValue[ToolCall](
          core.default(
            map.get( complete.resultValue.flatMap(_.message).cel, "tool_calls"),
            CelConst("[]")
          )
        )

        // ToolDispatcher decides whether a returned function has a matching
        // executable workflow. Sadhaka typed-output calls have none.
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
          ((core.len( processToolCalls.result.results) > 0) && !skipToolFeedback) -> {
            val toolFeedbackArgs = RunWorkflowArgs(
              WORKFLOW_ID,
              obj(EventHandler.Input(
                query = OptValue.nil[String],
                fund = input.fund,
                spent = OptValue(newSpent),
                callback = input.callback
              )),
              connector_params = ConnectorParams(skip_polling = true)
            )
            Call[RunWorkflowArgs[Input], ChatToolResponse](
              "toolCallsCompleted",
              CallOperations.WorkflowRun,
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

        val summaries = Summaries("runSummaries", WORKFLOW_ID)
        val extract = Chain(
          ExtractTopics
            .runSync(obj(StriderInput[EventHandler.Input](WORKFLOW_ID)))
            .andThen(SaveReflection)(obj(SaveReflection.Params(WORKFLOW_ID)))
        )
        val collapse = ContextCollapser("collapseMessages", WORKFLOW_ID, windowSeconds = 60 * 7, overlapSeconds = 60)

        // Typed Sadhaka side calls must never start fresh background Striders:
        // doing so recursively invokes the same agent via the Strider's
        // Sadhaka completion. Keep the normal turn's background pipeline.
        val maybeBackgroundStriders = Switch("maybeBackgroundStriders", List(
          sideCall.cel -> (List() -> Value.nil[NoValueT]),
          (true: Cel) -> (List[Step[?, ?]](summaries, extract, collapse) -> Value.nil[NoValueT])
        ))

        val whenAcquired = Switch("maybeRespond", List(
          // If lock acquired: persist the turn, build context, save the
          // response and tool results, then release the lock.
          acquiredTry.resultValue.cel -> {
            List[Step[?, ?]](
              maybeSaveTask,
              addMessage,
              Parallel("loadContext", List(
                promptsWorkflow,
                toolsWorkflow,
              )),
              mapToolDefs,
              withIstaTool,
              complete,
              sendContents,
              processToolCalls,
              release,
              toolsCost,
              addUsage,
              maybeToolFeedback,
              maybeBackgroundStriders
            ) -> obj(complete.result.copy(
              total_cost = Value[Double](totalCost)
            ))
          },
          // Contention is a retriable failure, never a successful empty turn.
          // The original request was not persisted and can be retried once
          // the current completion/tool callback has released the lock.
          (true: Cel) -> (List(Raise("sessionBusy", obj(Error(
            str("Session is busy; retry after the active turn completes"),
            OptValue[Int](409)
          )))) -> Value.nil[ChatToolResponse])
        ))

        List[Step[?, ?]](
          acquiredTry,
          whenAcquired,
        ) -> whenAcquired.resultValue
      },
      err => {
        val callbackFailure = Switch("callbackFailure", List(
          (input.callback.getOrElse(Value.nil) !== Cel.nil) -> input.callback.getOrElse(Value.nil).get.failure(err).fn,
          (true: Cel) -> (List() -> Value.nil)
        ))
        // Status prepends cleanup and Failed; the core Workflow rethrows.
        List[Step[?, ?]](callbackFailure, Raise("propagateEventHandlerError", err)) -> Value.nil[ChatToolResponse]
      }
    )

    Workflow(
      mainTry
    )
  }

  /** Run owner-scoped cleanup BEFORE Status emits Failed and callbacks run. */
  override protected def beforeStatusFailure(err: Resolved[Error]): List[Step[?, ?]] = {
    given KalaVibhaga = SegKala(sessionId, Value(time.now()), Value(20 * 60))
    val owner: Value[String] = Exec.currentExecId
    val lockKey = Locks.sessionKey("Convo")
    val bypassLock = input.sideCall.getOrElse(Value(false))
    List[Step[?, ?]](Switch("maybeNBypassRelease", List(
      bypassLock.cel -> (List() -> Value.nil[PostResult[NoValueT]]),
      (true: Cel) -> Locks.release("releaseResponseLock", lockKey, owner).fn
    )))
  }
}
object EventHandler {
  case class Input(
    query: OptValue[String] = OptValue.nil[String],
    fund: Value[Double],
    spent: OptValue[Double] = OptValue(0),
    usage: OptValue[Usage] = OptValue.nil,
    // Optional task payload to seed a task for this session (title/description/status)
    task: OptBase[Task] = OptValue.nil,
    toolChoice: OptBase[ToolChoice] = OptValue.nil[ToolChoice],
    typedTool: OptValue[Tool] = OptValue.nil[Tool],
    // Build Strider-specific Yoj in the caller before invoking Sadhaka.
    // The specialized CollapserMessages Yoj includes document IDs that the
    // default ProjectManager TopicContext would otherwise omit.
    sideCallContext: OptValue[List[ChatMessage]] = OptValue.nil[List[ChatMessage]],
    sideCall: OptValue[Boolean] = OptValue(false),
    callback: OptValue[Callback[ChatToolResponse]] = OptValue.nil,
    env: BaseValue[Env] = ENV
  )

  // Reconcile the sole wip-old Sadhaka implementation. Preserve its fixed
  // $0.10 budget, side-call flag, callback and first-output-tool semantics;
  // replace only the obsolete Google Workflows connector with native AWFL
  // workflow execution and adapt the provider tool-choice wire shape.
  given Sadhaka[Input] = new Sadhaka[Input] {
    override def complete[In: Yoj, Out: Ista: Spec](
      query: String,
      workflowName: Value[String]
    )(using kala: KalaVibhaga): Step[Sadhaka.Response[Out], Value[Sadhaka.Response[Out]]] = {
      val output = summon[Ista[Out]]
      val buildYoj = summon[Yoj[In]].build
      val completion = Callback[ChatToolResponse] { callback =>
        val args = RunWorkflowArgs(
          workflowName,
          obj(Input(
            query = OptValue(str(query)),
            fund = Value[Double](0.1),
            toolChoice = OptValue(obj(ToolChoice.Function(output.name))),
            typedTool = OptValue(obj(output.tool(kala))),
            sideCallContext = OptValue(buildYoj.resultValue),
            sideCall = OptValue(obj(true)),
            callback = OptValue(obj(callback))
          ))
        )
        Call[RunWorkflowArgs[Input], ChatToolResponse](
          "eventHandlerComplete",
          CallOperations.WorkflowRun,
          obj(args)
        )
      }
      val arguments = completion.result.message
        .flatMap(_.tool_calls(0)).flatMap(_.function).flatMap(_.arguments)
      val response = obj(Sadhaka.Response(
        Value[Out](json.decode(arguments)),
        completion.result.total_cost,
        completion.result.usage
      ))
      Try("eventHandlerTry", List[Step[?, ?]](buildYoj, completion) -> response)
    }
  }

  trait WithInput extends us.awfl.core.Workflow {
    override type Input = EventHandler.Input

    override val inputVal: Value[Input] = init[Input]("input")

    val sessionId = inputVal.flatMap(_.env).get.sessionId
  }
}
