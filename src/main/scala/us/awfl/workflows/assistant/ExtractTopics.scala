package us.awfl.workflows.assistant

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.utils.Convo
import us.awfl.ista.ChatMessage
import us.awfl.ista.TopicInfo
import us.awfl.utils.Convo.ConvoContext
import us.awfl.utils.Convo.SessionId
import us.awfl.utils.Yoj
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.SegKala
import us.awfl.utils.Convo.StepName
import us.awfl.utils.strider.Strider
import us.awfl.utils.TopicContextYoj
import us.awfl.utils.YojComposer

case class TopicContext(prevTopics: ListValue[TopicInfo], convoContext: BaseValue[ConvoContext])
object TopicContext:
  // De-duplicated: build a composite Yoj via TopicContextYoj with children [prevTopics, convoContext]
  given (using prevTopics: Yoj[TopicInfo], convoContext: Yoj[ConvoContext]): Yoj[TopicContext] =
    Yoj(
      "topicContext",
      { kala =>
        given KalaVibhaga = kala

        YojComposer.composed(
          name = "topicContext",
          childComponents = List(prevTopics.component, convoContext.component),
          intro = Some("These are the previously extracted topic information, as well as the conversation context: "),
          promoteUpstream = true,
          filters = ListValue.nil
        )
      },
      // componentYoj: parent spec must include nested children
      YojComposer.parentComponent(
        name = "topicContext",
        children = List(prevTopics.component, convoContext.component)
      )
    )

// Default prompt used by Strider workflows
given Convo.Prompt = Convo.Prompt(
  buildList(
    "buildPrompt",
    List(
      ChatMessage(
        "system",
        str("You're a background assistant, scrutanizing live or recorded conversations. You're the 'unconsciouse' part of the conversation agent, adding depth and structured memory to the conversation.")
      )
    )
  )
)

object ExtractTopics extends us.awfl.utils.strider.Latest[TopicContext, TopicInfo]("assistant-ExtractTopics")
