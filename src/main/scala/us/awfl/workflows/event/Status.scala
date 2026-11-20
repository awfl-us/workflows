package us.awfl.workflows.event

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.utils.Exec

/** Composable execution lifecycle based on the original wip-old traits.
  * The native core Workflow still registers executions and rethrows errors.
  * The caller may release its own resources before recording Failed.
  */
trait Status extends us.awfl.core.Workflow with Recursion {
  protected def beforeStatusFailure(err: Resolved[Error]): List[Step[?, ?]] = List()

  override def buildSteps[T: Spec](
    main: (List[Step[?, ?]], BaseValue[T]),
    except: Resolved[Error] => (List[Step[?, ?]], BaseValue[T])
  ): (List[Step[?, ?]], BaseValue[T]) = super.buildSteps(
    (statusStart(Exec.currentExecId) :: main._1) -> main._2,
    err => {
      val (continuation, result) = except(err)
      val failed = Try(
        "errorStatus",
        statusFailure(Exec.currentExecId, statusErrorMessage(err)) -> Value.nil[NoValueT]
      )
      (beforeStatusFailure(err) ++ List[Step[?, ?]](failed) ++ continuation) -> result
    }
  )

  override def stop: Step[NoValueT, NoValue] = Try(
    "statusStop",
    (statusComplete(Exec.currentExecId) :+ super.stop) -> Value.nil[NoValueT]
  )

  /** Initial status is best-effort and must not block a user turn. */
  protected def statusStart(execId: Value[String]) = Try(
    "tryInitStatus",
    List[Step[?, ?]](
      Exec.updateExecStatus("statusRunning", execId, str("Running"), Value(false)),
      Exec.enqueueExecStatus("enqueueStatusRunning", str("Running"))
    ) -> Value.nil[NoValueT]
  )

  /** Invoked by Recursion.stop at the terminal non-tool-feedback branch. */
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
