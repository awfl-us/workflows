package workflows.context

import dsl._
import dsl.CelOps._
import dsl.auto.given
import utils.Convo
import ista.ChatMessage
import ista.CollapseResponse
import utils.Convo.ConvoContext
import utils.Convo.SessionId
import utils.Yoj
import utils.KalaVibhaga
import utils.SegKala
import utils.Convo.StepName
import utils.post
import workflows.assistant.TopicContextYoj

// Minimal prompt; core JSON schema/instructions live in Ista[CollapseResponse]
private given Convo.Prompt = Convo.Prompt(
  buildList(
    "buildPrompt",
    List(
      ChatMessage(
        "system",
        str(
          "You're a background assistant that helps compress older conversation context into expandable, named groups."
        )
      )
    )
  )
)

trait CollapserMessages
object CollapserMessages {
  // Build a Yoj that serializes the full TopicContext (joined yoj) into a single ChatMessage
  // The single message's content is a JSON string of the full TopicContextYoj output list.
  given Yoj[CollapserMessages] =
    Yoj(
      name = "collapserMessages",
      buildYoj = { kala =>
        given KalaVibhaga = kala

        // Fetch the full topic context messages from the server and include docIds for downstream grouping
        val call = TopicContextYoj.run(includeDocId = true)

        // Serialize the list of ChatMessage objects into a JSON string
        val serialized = Try(
          s"collapser_serialize_topicContext_${Yoj.kalaName}",
          List(call) -> str(CelFunc("json.encode_to_string", call.resultValue.flatMap(_.body).flatMap(_.yoj).cel))
        )

        // Wrap the serialized JSON into a single ChatMessage
        val single = buildList[ChatMessage](
          s"collapser_single_msg_${Yoj.kalaName}",
          List(ChatMessage("system", serialized.resultValue))
        )

        Block(
          s"collapserYojBlock_${Yoj.kalaName}",
          List[Step[_, _]](call, serialized, single) -> single.resultValue
        )
      },
      // Component spec identifies this Yoj semantically as the topicContext source
      // even though we collapse it down to a single serialized message.
      Yoj.component("topicContext", "Serialized TopicContext (single message)")
    )
}

// Rework ContextCollapser to use the Strider pattern (Latest wrapper) similar to ExtractTopics.
// - Input Yoj: utils.Convo.ConvoContext (already provided by utils.Convo)
// - Output Ista: ista.CollapseResponse (defined under ista/, handles read/write semantics)
object ContextCollapser extends utils.strider.ConvoStrider[CollapserMessages, CollapseResponse] {

  override def name: String = "context-ContextCollapser"
  // Enable post-write hook for child Strider workflows via Latest -> All plumbing
  override protected def enablePostWrite: Boolean = true

  // Collapse indexer request/response payloads
  case class CollapseIndexerArgs(
    sessionId: Value[String],
    responseId: BaseValue[String],
    includeResponseToGroups: BaseValue[Boolean] = Value(true)
  )
  case class CollapseIndexerResult(
    ok: BaseValue[Boolean] = Value("null"),
    indexed_groups: BaseValue[Int] = Value("null"),
    indexed_messages: BaseValue[Int] = Value("null"),
    batches: BaseValue[Int] = Value("null")
  )

  // Fire the indexer only when a new SegKala child document was created
  override protected def postWriteSteps(sessionId: Value[String], responseId: BaseValue[String], at: BaseValue[Double]) = {
    val call = post[CollapseIndexerArgs, CollapseIndexerResult](
      "collapse_indexer_run",
      "context/collapse/indexer/run",
      obj(CollapseIndexerArgs(sessionId, responseId))
    )
    List(call)
  }
}
