package us.awfl.workflows.event

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.utils.Exec

trait Status extends us.awfl.core.Workflow with Recursion {
  private val triggeredExecId = Exec.currentExecId
  override def buildSteps(
    main: (List[Step[?, ?]], BaseValue[Result]),
    except: Resolved[Error] => (List[Step[?, ?]], BaseValue[Result])
  )(using Spec[Result]): (List[Step[?, ?]], BaseValue[Result]) = super.buildSteps(
    (Try(
      "tryInitStatus",
      List[Step[?, ?]](
        Exec.updateExecStatus(
          "statusRunning",
          triggeredExecId,
          str("Running"),
          Value(false)
        ),
        Exec.enqueueExecStatus(
          "enqueueStatusRunning",
          str("Running")
        )
      ) -> Value.nil[NoValueT]
    ) :: main._1) -> main._2,
    
    { err =>
      val errMsg = str(CelFunc(
        "default",
        CelFunc(
          "default",
          CelFunc(
            "default",
            CelFunc("map.get", err.cel, List("body", "error", "message").cel),
            CelFunc("map.get", err.cel, List("body", "error").cel)
          ),
          CelFunc("map.get", err.cel, "message")
        ),
        "Unknown error"
      ))
      val (continue, result) = except(err)
      Try("errorStatus", List[Step[?, ?]](
        Exec.updateExecStatus(
          "statusFailed",
          triggeredExecId,
          str("Failed"),
          Value(true),
          error = errMsg
        ),
        Exec.enqueueExecStatus(
          "enqueueStatusFailed",
          str("Failed"),
          error = errMsg
        )
      ) ++ continue -> result).fn
    }
  )

  override def stop: Step[NoValueT, NoValue] = Try("statusStop",
    List[Step[?, ?]](
      Exec.updateExecStatus(
        "statusDone",
        Exec.currentExecId,
        str("Done"),
        Value(true)
      ),
      Exec.enqueueExecStatus(
        "enqueueStatusDone",
        str("Done")
      ),
      super.stop
    ) -> Value.nil[NoValueT]
  )
}
