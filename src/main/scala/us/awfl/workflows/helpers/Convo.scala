package us.awfl.workflows.helpers

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.workflows.EventHandler
import us.awfl.ista.ChatMessage
import us.awfl.core
import us.awfl.dsl.Workflow
import us.awfl.utils
import us.awfl.utils.Yoj
import us.awfl.workflows.assistant.TopicContext
import us.awfl.services.Llm.ChatResponse
import us.awfl.services.Llm.ToolChoice
import us.awfl.services.Llm.ToolChoice.ToolChoiceFunction
import us.awfl.services.Llm.Tool
import us.awfl.utils.{Env, ENV}
import us.awfl.utils.TopicContextYoj
import us.awfl.services.Context.Kala
import us.awfl.utils.PostResult

case class ConvoInput(
  query: OptValue[String],
  prompts: ListValue[ChatMessage],
  tools: ListValue[Tool],
  toolChoice: OptBase[Tool] = OptValue.nil,
  skipAddMessages: Value[Boolean] = Value(false),
  kala: BaseValue[Kala],
  env: BaseValue[Env] = ENV
)
object Convo extends core.Workflow {
  override type Input = ConvoInput
  override type Result = ChatResponse[ChatMessage]

  override val inputVal: Value[ConvoInput] = init("input")

  override def workflows: List[Workflow[?]] = List(Workflow[ChatResponse[ChatMessage]]({
    val sessionId = inputVal.flatMap(_.env).get.sessionId

    def message = ChatMessage("user", input.query.getOrElse(Value.nil))
    val addMessage = Switch("maybeAddMessage", List(
      (input.skipAddMessages || !input.query.present)  -> (List() -> Value.nil[PostResult[Nothing]]),
      (true: Cel) -> utils.Convo.addMessage("addMessage", sessionId, obj(message)).fn
    ))
    
    val buildYojStep = summon[Yoj[TopicContext]].build(inputVal.flatMap(_.kala).get)
    val messages = Switch.list("messages", List(
      (!input.skipAddMessages && input.query.present) -> {
        val buildMessage = Try("buildMessage", List() -> obj(message))
        List(buildMessage) -> ListValue[ChatMessage](CelFunc("list.concat", buildYojStep.resultValue, buildMessage.resultValue))
      },
      (true: Cel) -> (List() -> buildYojStep.resultValue)
    ))
    val buildToolChoice = Switch("buildToolChoice", List(
      input.toolChoice.present.cel ->
        (List() -> obj(ToolChoice.Function(ToolChoiceFunction(input.toolChoice.getOrElse(Value.nil).flatMap(_.function).get.name)))),
      (true: Cel) -> (List() -> ToolChoice.auto)
    ))
    val completeStep = utils.Convo.completeWithTools(
      "complete",
      input.prompts,
      messages.resultValue,
      input.tools,
      sessionId,
      toolChoice = buildToolChoice.resultValue,
      model = inputVal.flatMap(_.env).get.model
    )
    val responseMessage = completeStep.result.message
    val addResponse  = Switch("maybeAddResponse", List(
      input.skipAddMessages.cel -> (List() -> Value.nil[PostResult[Nothing]]),
      (true: Cel) -> utils.Convo.addMessage(
        "addResponse",
        sessionId,
        obj(us.awfl.ista.ChatMessage(
          role = responseMessage.flatMap(_.role),
          content = responseMessage.flatMap(_.content),
          tool_calls = ListValue(CelFunc("map.get", responseMessage, "tool_calls")),
          create_time = Value("sys.now()")
        )),
        cost = completeStep.result.total_cost
      ).fn
    ))
    List[Step[?, ?]](addMessage, buildYojStep, messages, buildToolChoice, completeStep, addResponse) -> completeStep.resultValue
  }))

  def apply(input: ConvoInput): Step[ChatResponse[ChatMessage], Value[ChatResponse[ChatMessage]]] = {
    execute(workflowName, obj(input))
  }
}
