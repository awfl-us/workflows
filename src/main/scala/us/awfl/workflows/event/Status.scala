package us.awfl.workflows.event

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.utils.Exec

/** The original `wip-old` Status lifecycle, adapted to today's EventHandler.
  *
  * The historical Status mixed into Recursion and intercepted buildSteps/stop.
  * Current EventHandler handles session contention, typed side calls and
  * callback continuations explicitly. Preserve that ordering by providing
  * status steps at the same call sites rather than overriding buildSteps or
  * moving Done ahead of tool dispatch and lock release.
  *
  * All methods create DSL steps, not immediate side effects.
  */
trait Status extends us.awfl.core.Workflow {
  /** Initial status is best-effort and must not block a user turn. */
  protected def statusStart(execId: Value[String]) = Try(
    "tryInitStatus",
    List[Step[?, ?]](
      Exec.updateExecStatus("statusRunning", execId, str("Running"), Value(false)),
      Exec.enqueueExecStatus("enqueueStatusRunning", str("Running"))
    ) -> Value.nil[NoValueT]
  )

  /** Called only by the terminal, non-tool-feedback branch of EventHandler. */
  protected def statusComplete(execId: Value[String]): List[Step[?, ?]] = List(
    Exec.updateExecStatus("statusDone", execId, str("Done"), Value(true)),
    Exec.enqueueExecStatus("enqueueStatusDone", str("Done"))
  )

  protected def statusErrorMessage(err: Resolved[Error]): Value[String] = str(core.default(
    core.default(
      core.default(
        map.get(err.cel, CelConst("""["body", "error", "message"]""")),
        map.get(err.cel, CelConst("""["body", "error"]"""))
      ),
      map.get(err.cel, "message")
    ),
    "Unknown error"
  ))

  /** EventHandler must still deliver callback failure and rethrow afterwards. */
  protected def statusFailure(execId: Value[String], message: Value[String]): List[Step[?, ?]] = List(
    Exec.updateExecStatus("statusFailed", execId, str("Failed"), Value(true), error = message),
    Exec.enqueueExecStatus("enqueueStatusFailed", str("Failed"), error = message)
  )
}
