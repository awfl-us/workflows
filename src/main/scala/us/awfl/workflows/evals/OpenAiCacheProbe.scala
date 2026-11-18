package us.awfl.workflows.evals

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm

/**
 * Controlled prompt-cache experiment. Pass the exact chat message array and
 * model; do not run TopicContext, conversation filters, or agent tool loops.
 * The existing authenticated LLM service invokes OpenAI using the user's
 * encrypted provider credential, so this workflow never reads secrets.
 */
object OpenAiCacheProbe extends us.awfl.core.Workflow {
  case class Input(arg1: ListValue[ChatMessage], arg2: Value[String])
  override val inputVal = init[Input]("input")
  override type Result = Llm.ChatToolResponse

  override def workflows = List({
    val completion = Llm.chatWithTools(
      name = "controlledCacheCall",
      messages = inputVal.get.arg1,
      tools = ListValue.nil,
      sessionId = str("openai-cache-probe"),
      tool_choice = Value.nil,
      model = inputVal.get.arg2,
      maxTokens = Value(32)
    )
    Workflow(List[Step[?, ?]](completion) -> completion.resultValue)
  })
}
