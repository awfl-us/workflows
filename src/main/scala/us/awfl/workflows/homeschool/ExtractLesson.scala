package us.awfl.workflows.homeschool

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.ista.Lesson
import us.awfl.ista.TopicInfo
import us.awfl.utils.Convo.ConvoContext
import us.awfl.utils.Yoj
import us.awfl.utils.Convo
import us.awfl.utils.KalaVibhaga
import us.awfl.utils.SegKala
import us.awfl.utils.YojComposer

case class ExtractLessonInput(arg1: Value[String]) {
  val sessionId = arg1
}
private val input = init[ExtractLessonInput]("input").get

object ExtractLesson extends ExtractLesson(using SegKala(input.sessionId, Value("sys.now()"), obj(15 * 60)))

trait ExtractLesson(using kala: SegKala) {
  case class LessonContext(prevTopics: ListValue[TopicInfo], convoContext: BaseValue[ConvoContext], lesson: ListValue[Lesson])
  given LessonContext(using prevTopics: Yoj[TopicInfo], convoContext: Yoj[ConvoContext], lesson: Yoj[Lesson]): Yoj[LessonContext] = Yoj(
    "lessonContext",
    { kala =>
      given KalaVibhaga = kala

      YojComposer.composed(
        name = "topicContext",
        childComponents = List(prevTopics.component, convoContext.component, lesson.component),
        intro = Some("Here are the topics, lessons and summaries extracted from earlier parts of the conversation:\r"),
        promoteUpstream = true,
        filters = ListValue.nil
      )
    },
    // componentYoj: parent spec must include nested children
    YojComposer.parentComponent(
      name = "topicContext",
      children = List(prevTopics.component, convoContext.component, lesson.component)
    )
  )

  val buildPrompt = buildList("buildPrompt", List((ChatMessage("system", str("You're a homeschool teacher working in the background of the live lesson conversation to document and record the important elements of the lesson to track and guage the student's progression through the semester's lesson plan and towards their personal academic and life goals.")))))
  given Convo.Prompt = Convo.Prompt(buildPrompt)

  def apply(name: String, sessionId: Value[String]): Call[RunWorkflowArgs[ExtractLessonInput], Lesson] = {
    val args = RunWorkflowArgs(str("homeschool-ExtractLesson${WORKFLOW_ENV}"), obj(ExtractLessonInput(sessionId)), connector_params = ConnectorParams(true))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }
}