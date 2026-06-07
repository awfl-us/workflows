package us.awfl.workflows.context

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.utils.Convo
import us.awfl.ista.ChatMessage
import us.awfl.ista.CollapseResponse
import us.awfl.utils.Convo.ConvoContext
import us.awfl.utils.Convo.SessionId
import us.awfl.utils.Yoj
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.SegKala
import us.awfl.utils.Convo.StepName
import us.awfl.utils.post
import us.awfl.utils.TopicContextYoj

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
          List[Step[?, ?]](call, serialized, single) -> single.resultValue
        )
      },
      // Component spec identifies this Yoj semantically as the topicContext source
      // even though we collapse it down to a single serialized message.
      Yoj.component("topicContext", "Serialized TopicContext (single message)")
    )
}

trait ContextCollapser extends us.awfl.core.Workflow {
  override def buildSteps(
    main: (List[Step[?, ?]], BaseValue[Result]),
    except: Resolved[Error] => (List[Step[?, ?]], BaseValue[Result])
  )(using Spec[Result]): (List[Step[?, ?]], BaseValue[Result]) = super.buildSteps(
    (ContextCollapser("collapseMessages", windowSeconds = 60 * 7, overlapSeconds = 60) :: main._1) -> main._2,
    except
  )
}

// Rework ContextCollapser to use the Strider pattern (Latest wrapper) similar to ExtractTopics.
// - Input Yoj: us.awfl.utils.Convo.ConvoContext (already provided by us.awfl.utils.Convo)
// - Output Ista: ista.CollapseResponse (defined under ista/, handles read/write semantics)
object ContextCollapser extends us.awfl.utils.strider.ConvoStrider[CollapserMessages, CollapseResponse] {

  override def name: String = "context-ContextCollapser"

  override def prompt = "You're a background assistant that helps compress older conversation context into expandable, named groups."

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
  override protected def postWriteSteps(sessionId: Value[String], responseId: Value[String], response: Value[CollapseResponse], at: Value[Double]) = {
    val call = post[CollapseIndexerArgs, CollapseIndexerResult](
      "collapse_indexer_run",
      "context/collapse/indexer/run",
      obj(CollapseIndexerArgs(sessionId, responseId))
    )
    List(call)
  }
}
