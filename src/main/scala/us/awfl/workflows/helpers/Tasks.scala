package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.utils.{get, PostResult}
import us.awfl.workflows.EventHandler
import us.awfl.workflows.tools.Tools
import us.awfl.services.Llm.{Tool, ToolFunctionDef, ToolDefParams, ToolDefProperty}
import us.awfl.utils.Env

object Tasks extends us.awfl.core.Workflow {
  // Agent guidance: include this in prompts so agents know when to create/update tasks.
  // Keep concise; system-level instruction.
  val managementGuidanceText: List[String] = List(
    "Tasks guidance: When a query will require more than a handful of file reads/writes, create task(s) to track the work. Use CREATE_TASK to open an In Progress task before starting substantial edits.",
    "Don't create a duplicate task if it already exists; optionally queue additional tasks if you will batch work. Keep titles concise and descriptions scoped. Update status as you proceed (Queued -> In Progress -> Done/Stuck) and mark Done when complete."
  )
  val managementGuidance: List[ChatMessage] = managementGuidanceText.map(msg => ChatMessage("system", str(msg)))

  // Mirror of functions/tasks.common.js Task structure
  case class Task(
    id: Value[String],
    sessionId: Value[String],
    title: Value[String],
    description: Value[String],
    status: Value[String],
    created: Value[Double],
    updated: Value[Double]
  )
  case class TasksList(tasks: ListValue[Task])
  case class TaskEnvelope(task: BaseValue[Task])

  // Build a GET URL for by-session with filters (made public for reuse by runners)
  def bySessionUrl(status: String, limit: Int, order: String): Value[String] = {
    // e.g., tasks/by-session/{sessionId}?status=In%20Progress&limit=1&order=desc
    val base: Cel = ("tasks/by-session/": Cel) + Env.sessionId.cel
    val q: Cel = ("?status=": Cel) + status + ("&limit=": Cel) + limit + ("&order=": Cel) + order
    str(base + q)
  }

  // Stringify a task in a single line (made public for reuse by runners)
  def taskToLine(prefix: String, t: BaseValue[Task]): Value[String] = {
    // Safely stringify possibly-null fields via CEL string() conversion; build with Cels only
    val idCel: Cel      = CelFunc("string", t.flatMap(_.id).cel)
    val titleCel: Cel   = CelFunc("string", t.flatMap(_.title).cel)
    val descCel: Cel    = CelFunc("string", t.flatMap(_.description).cel)
    val statusCel: Cel  = CelFunc("string", t.flatMap(_.status).cel)
    val createdCel: Cel = CelFunc("string", t.flatMap(_.created).cel)
    // Example: Current task: id=abc123 | Title — Description [status=In Progress, created=...]
    str((prefix: Cel) + (": id=": Cel) + idCel + (" | ": Cel) + titleCel + (" — ": Cel) + descCel + (" [status=": Cel) + statusCel + ("]": Cel))
  }

  private def firstOrNote(name: String, label: String, getStep: Step[PostResult[TasksList], Resolved[PostResult[TasksList]]]): Step[ChatMessage, BaseValue[ChatMessage]] = {
    val body = getStep.resultValue.flatMap(_.body)

    // Defensive: detect non-object bodies before looking up fields.
    val bodyTypeCel: Cel = CelFunc("get_type", body.cel)
    val bodyStrCel: Cel  = CelFunc("string", body.cel)

    val contentSwitch = Switch[ChatMessage, BaseValue[ChatMessage]](s"${name}_content", List(
      // When body is an object (map) and we have at least one task
      ((bodyTypeCel === ("map": Cel)) && (CelFunc("len", body.flatMap(_.tasks).cel) > 0)) -> {
        val first = body.flatMap(_.tasks)(0)
        val line = taskToLine(label, first)
        List() -> obj(ChatMessage("system", line))
      },
      // When body is an object (map) but contains no tasks
      (bodyTypeCel === ("map": Cel)) -> {
        val msg = str((label: Cel) + (": ": Cel) + ("None found for session": Cel))
        List() -> obj(ChatMessage("system", msg))
      },
      // Non-object response: surface a descriptive error including the body type and string value
      (true: Cel) -> {
        val err = str((label: Cel) + (": ": Cel)
          + ("Tasks endpoint returned non-object body. get_type=": Cel) + bodyTypeCel
          + (", body=": Cel) + bodyStrCel)
        List() -> obj(ChatMessage("system", err))
      }
    ))

    // Execute getStep before the Switch and expose the Block as `${name}_switch` so `${name}_switchResult` is resolvable downstream.
    Block(s"${name}_switch", List[Step[_, _]](getStep, contentSwitch) -> contentSwitch.resultValue)
  }

  case class Input(env: BaseValue[us.awfl.utils.Env] = us.awfl.utils.ENV)
  case class Result(tasks: ListValue[ChatMessage])

  override val inputVal: BaseValue[Input] = init[Input]("input")

  // Exposed helpers that each return a ChatMessage Step to be included in prompts
  def currentTaskPrompt(name: String): Step[ChatMessage, BaseValue[ChatMessage]] = {
    val url = bySessionUrl("In%20Progress", 1, "desc")
    val getStep = get[TasksList](s"${name}_get", url)
    firstOrNote(name, "Current task", getStep)
  }

  def oldestPendingTaskPrompt(name: String): Step[ChatMessage, BaseValue[ChatMessage]] = {
    val url = bySessionUrl("Queued", 1, "asc")
    val getStep = get[TasksList](s"${name}_get", url)
    firstOrNote(name, "Oldest pending task", getStep)
  }

  def newestDoneTaskPrompt(name: String): Step[ChatMessage, BaseValue[ChatMessage]] = {
    val url = bySessionUrl("Done", 1, "desc")
    val getStep = get[TasksList](s"${name}_get", url)
    firstOrNote(name, "Newest complete task", getStep)
  }

  // -------- Task creation and update helpers (HTTP) --------
  case class CreateTaskBody(
    sessionId: BaseValue[String],
    title: BaseValue[String] = Value.nil,
    description: BaseValue[String] = Value.nil,
    status: BaseValue[String] = Value.nil
  )

  case class UpdateTaskBody(
    sessionId: BaseValue[String] = Value.nil,
    title: BaseValue[String] = Value.nil,
    description: BaseValue[String] = Value.nil,
    status: BaseValue[String] = Value.nil
  )

  // POST /jobs/tasks
  def createTask(name: String, title: BaseValue[String], description: BaseValue[String], status: BaseValue[String]) = {
    us.awfl.utils.post[CreateTaskBody, TaskEnvelope](
      name,
      "tasks",
      obj(CreateTaskBody(Env.sessionId, title, description, status))
    )
  }

  // PATCH /jobs/tasks/:id
  def updateTask(name: String, id: BaseValue[String], title: BaseValue[String], description: BaseValue[String], status: BaseValue[String]) = {
    us.awfl.utils.patchV[UpdateTaskBody, TaskEnvelope](
      name,
      str(("tasks/": Cel) + id.cel),
      obj(UpdateTaskBody(Env.sessionId, title, description, status))
    )
  }

  // Optional helper: create a task from EventHandler.input.task if provided
  // Expects `task` to be a map-like value with optional title/description/status
  def maybeSaveInputTask(name: String, task: Cel): Step[ChatMessage, BaseValue[ChatMessage]] = {
    val isMap: Cel = CelFunc("get_type", task) === ("map": Cel)

    val create = {
      val title       = Value[String](CelFunc("map.get", task, "title"))
      val description = Value[String](CelFunc("map.get", task, "description"))
      val status      = str(CelFunc("default", CelFunc("map.get", task, "status"), "In Progress"))

      val post = createTask(s"${name}_post", title, description, status)
      val body = post.resultValue.flatMap(_.body).flatMap(_.task)
      val line = taskToLine("Task created", body)
      List(post) -> obj(ChatMessage("system", line))
    }

    val skip = List() -> obj(ChatMessage("system", str("No input.task provided")))

    val sw = Switch(s"${name}_switch", List(
      isMap -> create,
      (true: Cel) -> skip
    ))

    Block(name, List(sw) -> sw.resultValue)
  }

  def apply(name: String): Call[RunWorkflowArgs[Input], Result] = {
    val args = RunWorkflowArgs(str("helpers-Tasks${WORKFLOW_ENV}"), obj(Input()))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }

  override def workflows = List({
    val currentTaskMsg = currentTaskPrompt("current")
    val oldestPendingTaskMsg = oldestPendingTaskPrompt("pending")
    val newestDoneTaskMsg = newestDoneTaskPrompt("done")

    val buildList = buildValueList(
      "buildList",
      currentTaskMsg.resultValue ::
      oldestPendingTaskMsg.resultValue ::
      newestDoneTaskMsg.resultValue :: Nil
    )
    Workflow(List(
      currentTaskMsg,
      oldestPendingTaskMsg,
      newestDoneTaskMsg,
      buildList
    ) -> obj(Result(buildList.resultValue)))
  })
}
