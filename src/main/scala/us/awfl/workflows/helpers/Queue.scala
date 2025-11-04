package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.utils.Events.OperationEnvelope

/**
 * Queue: a small helper workflow to enqueue Pub/Sub messages (fire-and-forget).
 * - Encodes provided JSON objects as base64 data and publishes them to the given topic.
 * - Attaches the sessionId as a message attribute for routing on the CLI side.
 */
object Queue {
  // Pub/Sub request types
  case class MessageAttributes(sessionId: BaseValue[String])
  case class PubSubMessage(data: Field, attributes: MessageAttributes = MessageAttributes(Field.str("")))
  case class PublishBody(messages: ListValue[PubSubMessage])
  case class PublishArgs(topic: BaseValue[String], body: PublishBody)

  // Workflow input and result
  case class Input(
    sessionId: Value[String],
    items: ListValue[OperationEnvelope],            // Each item is a JSON object to encode and publish
    topic: BaseValue[String] = Field.str("projects/topaigents/topics/CliOperations")
  )
  case class Result(published: BaseValue[Int])

  // Standalone workflow definition
  def workflows = List({
    val input = init[Input]("input").get

    // Map each JSON item to a PubSubMessage with base64-encoded data
    val buildMessages = For("buildMessages", input.items) { item =>
      val dataField = Field(CelFunc("base64.encode", CelFunc("json.encode", item)))
      List() -> obj(PubSubMessage(dataField, MessageAttributes(input.sessionId)))
    }

    val publishArgs = PublishArgs(
      topic = input.topic,
      body = PublishBody(buildMessages.resultValue)
    )

    val publish = Call[PublishArgs, NoValueT](
      "publish",
      "googleapis.pubsub.v1.projects.topics.publish",
      obj(publishArgs)
    )

    // Return the number of messages published (len of built messages)
    val publishedCount = Value[Int](CelFunc("len", buildMessages.resultValue))

    Workflow(List[Step[_, _]](
      buildMessages,
      publish
    ) -> obj(Result(publishedCount)))
  })

  // Helper: run the Queue workflow with given items
  def apply(name: String, sessionId: Value[String], items: ListValue[OperationEnvelope], topic: BaseValue[String] = Field.str("projects/topaigents/topics/CliOperations")): Call[RunWorkflowArgs[Input], Result] = {
    val args = RunWorkflowArgs(str("helpers-Queue${WORKFLOW_ENV}"), obj(Input(sessionId, items, topic)))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }

  // Helper: enqueue a single item by wrapping it into a one-element list
  def one(name: String, sessionId: Value[String], item: OperationEnvelope, topic: BaseValue[String] = Field.str("projects/topaigents/topics/CliOperations")): Step[Result, BaseValue[Result]] = {
    val items = buildList("singleItem", List(item))
    val args = RunWorkflowArgs(str("helpers-Queue${WORKFLOW_ENV}"), obj(Input(sessionId, items.resultValue, topic)))
    val call = Call[RunWorkflowArgs[Input], Result](name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
    Block(s"${name}_block", List[Step[_, _]](items, call) -> call.resultValue)
  }
}
