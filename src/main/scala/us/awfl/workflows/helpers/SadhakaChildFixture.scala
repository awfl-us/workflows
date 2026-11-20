package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.{ChatMessage, ToolCall, ToolCallFunction}
import us.awfl.services.Llm.{ChatToolResponse, Usage, CompletionTokensDetails, PromptTokensDetails}
import us.awfl.workflows.EventHandler

/** Minimal native child for an end-to-end Sadhaka workflow.run/callback test.
  * It represents the typed output of an EventHandler without invoking a
  * provider or the unrelated Project Manager's shell preloads.
  */
object SadhakaChildFixture extends us.awfl.core.Workflow {
  override type Input = EventHandler.Input
  override val inputVal = init[Input]("input")

  override def workflows = List({
    val toolCalls = buildList[ToolCall]("outputToolCalls", List(ToolCall(
      id = str("fixture-output-1"),
      `type` = str("function"),
      function = obj(ToolCallFunction(
        str("summaries"),
        str("""{"title":"Fixture title","summary":"Fixture summary"}""")))
    )))
    val message = Try("outputMessage", List(toolCalls) -> obj(ChatMessage(
      str("assistant"), str(""), tool_calls = toolCalls.resultValue)))
    val usage = Try("outputUsage", List() -> obj(Usage(Value[Double](3),
        obj(CompletionTokensDetails(Value[Double](0),Value[Double](0),
          Value[Double](0),Value[Double](0))),
        Value[Double](5),
        obj(PromptTokensDetails(Value[Double](0),Value[Double](0))),
        Value[Double](8))))
    val reply = Try("typedOutputReply", List[Step[?, ?]](message, usage) -> obj(ChatToolResponse(
      message = message.resultValue,
      total_cost = Value[Double](0.015),
      usage = usage.resultValue)))
    val send = inputVal.get.callback.getOrElse(Value.nil).get.apply(reply.resultValue)
    Workflow(List[Step[?, ?]](toolCalls, message, usage, reply, send) -> reply.resultValue)
  })
}
