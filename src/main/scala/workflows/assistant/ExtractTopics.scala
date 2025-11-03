package workflows.assistant

import dsl._
import dsl.CelOps._
import dsl.auto.given
import utils.Convo
import ista.ChatMessage
import ista.TopicInfo
import utils.Convo.ConvoContext
import utils.Convo.SessionId
import utils.Yoj
import utils.KalaVibhaga
import utils.SegKala
import utils.Convo.StepName
import utils.strider.Strider
import workflows.assistant.TopicContextYoj
import utils.YojComposer

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

object ExtractTopics extends utils.strider.Latest[TopicContext, TopicInfo]("assistant-ExtractTopics")
