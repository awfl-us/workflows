package workflows.homeschool

import dsl._
import dsl.CelOps._
import dsl.auto.given
import ista.ChatMessage
import ista.Lesson
import ista.TopicInfo
import utils.Convo.ConvoContext
import utils.Yoj
import utils.Convo
import utils.KalaVibhaga
import utils.SegKala
import utils.YojComposer

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
  
  // val strideStep = Convo.inSession[Lesson]("inSession", input.sessionId) {
  //   utils.Strider[LessonContext, Lesson](obj(kala)).fn
  // }

  // val workflow = Workflow(List(
  //   buildPrompt,
  //   strideStep
  // ) -> strideStep.resultValue)

  def apply(name: String, sessionId: Value[String]): Call[RunWorkflowArgs[ExtractLessonInput], Lesson] = {
    val args = RunWorkflowArgs(str("homeschool-ExtractLesson${WORKFLOW_ENV}"), obj(ExtractLessonInput(sessionId)), connector_params = ConnectorParams(true))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }
}