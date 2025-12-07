package us.awfl.workflows.helpers.tasks

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.ToolCall
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm.{Tool, ToolFunctionDef, ToolDefParams, ToolDefProperty}
import us.awfl.workflows.tools.Tools
import us.awfl.workflows.helpers.Tasks
import us.awfl.utils.get

/**
 * TaskRunners
 * - Exposes CREATE_TASK and UPDATE_TASK execution helpers used by tools/Tasks workflow.
 * - Keeps idempotent behavior and auto-promotion logic encapsulated here.
 */
object TaskRunners {
  // -------- Tool Params (kept for reference; not used directly by the workflow) --------
  private val createParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      // sessionId is implied by the current session context; agent should not provide it
      "title"       -> ToolDefProperty("string"),
      "description" -> ToolDefProperty("string"),
      "status"      -> ToolDefProperty("string", ListValue("""["Queued", "In Progress", "Done", "Stuck"]"""))
    ),
    // No required fields; session is injected by the runner
    required = str("[]")
  )

  private val updateParams = ToolDefParams(
    `type` = "object",
    properties = Map(
      "id"          -> ToolDefProperty("string"),
      // sessionId is not required or exposed; runner will not change session
      "title"       -> ToolDefProperty("string"),
      "description" -> ToolDefProperty("string"),
      "status"      -> ToolDefProperty("string", ListValue("""["Queued", "In Progress", "Done", "Stuck"]"""))
    ),
    required = str("""["id"]""")
  )

  // -------- CREATE_TASK exec --------
  private val createRun: Value[ToolCall] => (List[Step[_, _]], Value[String]) = { toolCall =>
    val fn = toolCall.flatMap(_.function).get
    val title = fn.arg("title")
    val desc  = fn.arg("description")
    // Default to In Progress if omitted
    val status = str(CelFunc("default", fn.arg("status").cel, "In Progress"))

    // Important: execute the POST exactly once inside Try to avoid duplicate creation on retries
    val tryCreate = Try("tryCreateTask", {
      val create = Tasks.createTask("createTaskCall", title, desc, status)
      val body = create.resultValue.flatMap(_.body).flatMap(_.task)
      val line = Tasks.taskToLine("Created task", body)
      List(create) -> line
    }, err => {
      val msg = str((("Task create failed: ": Cel)) + CelFunc("string", err.cel))
      List() -> msg
    })

    (List(tryCreate), tryCreate.resultValue)
  }

  // -------- UPDATE_TASK exec --------
  private val updateRun: Value[ToolCall] => (List[Step[_, _]], Value[String]) = { toolCall =>
    val fn = toolCall.flatMap(_.function).get
    val id    = fn.arg("id")

    val titleV: BaseValue[String]   = fn.arg("title")
    val descV: BaseValue[String]    = fn.arg("description")
    val statusV: BaseValue[String]  = fn.arg("status")

    val patch = Tasks.updateTask("updateTaskCall", id, titleV, descV, statusV)

    val tryUpdate = Try("tryUpdateTask", {
      val body = patch.resultValue.flatMap(_.body)
      val task = body.flatMap(_.task)
      val line = Tasks.taskToLine("Updated task", task)
      // Do not include `patch` here to avoid double execution
      List() -> line
    }, err => {
      val msg = str((("Task update failed: ": Cel)) + CelFunc("string", err.cel))
      List() -> msg
    })

    // Auto-promotion logic: when a task is marked Done, set the oldest pending (Queued) to In Progress
    // Defensive checks:
    // - Only run when the updated task's status is Done.
    // - Do nothing if there is already an In Progress task for the session.
    // - Promote only the oldest Queued task (limit=1, order=asc).
    val updatedStatus = patch.resultValue.flatMap(_.body).flatMap(_.task).flatMap(_.status)
    val isDoneCel: Cel = CelFunc("string", updatedStatus.cel) === ("Done": Cel)

    val maybePromoteInner: Step[String, Value[String]] = {
      val getInProgress = get[Tasks.TasksList](
        "promote_get_in_progress",
        Tasks.bySessionUrl("In%20Progress", 1, "desc")
      )
      val inProgLen: Cel = CelFunc("len", getInProgress.resultValue.flatMap(_.body).flatMap(_.tasks).cel)

      val promoteIfNoCurrent = Switch[String, Value[String]]("promote_if_no_current_in_progress", List(
        (inProgLen === 0) -> {
          val getQueued = get[Tasks.TasksList](
            "promote_get_oldest_queued",
            Tasks.bySessionUrl("Queued", 1, "asc")
          )
          val queuedLen: Cel = CelFunc("len", getQueued.resultValue.flatMap(_.body).flatMap(_.tasks).cel)

          val promoteIfHasQueued = Switch[String, Value[String]]("promote_if_has_queued", List(
            (queuedLen > 0) -> {
              val next = getQueued.resultValue.flatMap(_.body).flatMap(_.tasks)(0)
              val nextId = next.flatMap(_.id)
              val promotePatch = Tasks.updateTask(
                "promote_update_next",
                nextId,
                Value.nil,
                Value.nil,
                str("In Progress")
              )
              val promotedLine = Tasks.taskToLine("Promoted next task", promotePatch.resultValue.flatMap(_.body).flatMap(_.task))
              List(promotePatch) -> promotedLine
            },
            (true: Cel) -> {
              val msg = str("No queued tasks to promote")
              List() -> msg
            }
          ))

          Block("promote_has_queued_block", List[Step[_, _]](getQueued, promoteIfHasQueued) -> promoteIfHasQueued.resultValue).fn
        },
        (true: Cel) -> {
          val msg = str("Task marked Done, but another task is already In Progress; no promotion performed")
          List() -> msg
        }
      ))

      Block("promote_no_current_block", List[Step[_, _]](getInProgress, promoteIfNoCurrent) -> promoteIfNoCurrent.resultValue)
    }

    val maybePromote = Switch[String, Value[String]]("maybe_promote_next", List(
      isDoneCel -> {
        List(maybePromoteInner) -> maybePromoteInner.resultValue
      },
      (true: Cel) -> {
        // Not a Done update — no auto-promotion
        val msg = str("")
        List() -> msg
      }
    ))

    val combinedMsg = str(tryUpdate.resultValue.cel + ("\r": Cel) + maybePromote.resultValue.cel)

    (List[Step[_, _]](patch, tryUpdate, maybePromote), combinedMsg)
  }

  // -------- Public exec helpers used by tools/Tasks workflow --------
  def runCreate(toolCall: Value[ToolCall]): (List[Step[_, _]], Value[String]) =
    createRun(toolCall)

  def runUpdate(toolCall: Value[ToolCall]): (List[Step[_, _]], Value[String]) =
    updateRun(toolCall)
}
