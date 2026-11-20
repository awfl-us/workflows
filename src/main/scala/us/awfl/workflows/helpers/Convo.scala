package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm.{ChatToolResponse, Tool, ToolChoice}
import us.awfl.utils.{Convo as ConvoOps, Env, ENV, KalaVibhaga, SegKala, Yoj, PostResult}
import us.awfl.workflows.assistant.TopicContext

/** Conversation completion boundary from workflows/wip-old.
  *
  * EventHandler intentionally retains the session lock and initial user-turn
  * persistence. Only after both complete does it invoke this native child,
  * which builds the context, calls the model, and persists the assistant
  * response. In particular, a query-free continuation must not add a user
  * message, and side calls must not persist an assistant response.
  */
object Convo extends us.awfl.core.Workflow {
  case class Input(
    prompts: ListValue[ChatMessage],
    tools: ListValue[Tool],
    toolChoice: BaseValue[ToolChoice],
    skipAddMessages: Value[Boolean],
    contextOverride: OptValue[List[ChatMessage]] = OptValue.nil[List[ChatMessage]],
    env: BaseValue[Env] = ENV
  )
  override type Result = ChatToolResponse
  override val inputVal = init[Input]("input")

  def apply(name: String, prompts: ListValue[ChatMessage],
    tools: ListValue[Tool], toolChoice: BaseValue[ToolChoice],
    skipAddMessages: Value[Boolean],
    contextOverride: OptValue[List[ChatMessage]] = OptValue.nil[List[ChatMessage]]): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    val args = RunWorkflowArgs(str("helpers-Convo${WORKFLOW_ENV}"),
      obj(Input(prompts, tools, toolChoice, skipAddMessages, contextOverride)))
    Call(name, CallOperations.WorkflowRun, obj(args))
  }

  override def workflows = List({
    val sessionId = inputVal.flatMap(_.env).get.sessionId
    given KalaVibhaga = SegKala(sessionId, Value(time.now()), Value(20 * 60))

    val context = Switch.list("buildContext", List(
      (input.contextOverride.getOrElse(Value.nil[List[ChatMessage]]) !== Cel.nil) ->
        (List() -> ListValue[ChatMessage](input.contextOverride.getOrElse(Value.nil[List[ChatMessage]]).cel)),
      (true: Cel) -> summon[Yoj[TopicContext]].build.fn
    ))
    val completion = ConvoOps.completeWithTools(
      "complete", input.prompts, context.resultValue, input.tools, sessionId,
      toolChoice = input.toolChoice, model = inputVal.flatMap(_.env).get.model
    )
    val responseMessage = completion.result.message
    val addResponse = Switch("maybeAddResponse", List(
      input.skipAddMessages.cel -> (List() -> Value.nil[PostResult[Nothing]]),
      (true: Cel) -> ConvoOps.addMessage("addResponse",
        obj(ChatMessage(
          role = responseMessage.flatMap(_.role),
          content = responseMessage.flatMap(_.content),
          tool_calls = ListValue(map.get(responseMessage,"tool_calls")),
          create_time = Value(time.now())
        )), cost = completion.result.total_cost).fn
    ))

    Workflow(buildSteps[ChatToolResponse](
      List[Step[?, ?]](context, completion, addResponse) -> completion.resultValue,
      _ => List() -> Value.nil[ChatToolResponse]))
  })
}
