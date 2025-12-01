package us.awfl.workflows.helpers

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.utils.ChainableInput
import us.awfl.utils.{Env, ENV}

object Chain extends us.awfl.core.Workflow {
  case class WorkflowArgs(workflowName: BaseValue[String], params: BaseValue[_])
  // given Spec[WorkflowArgs] = Spec(r => WorkflowArgs(r.in("workflowName"), r.in("params")))

  case class Input(input: BaseValue[_], headWorkflow: BaseValue[String], tail: ListValue[WorkflowArgs], env: BaseValue[Env] = ENV)
  override type Result = AnyValueT

  override val inputVal: BaseValue[Input] = init("input")

  override def workflows = List({
    val inputAs: BaseValue[AnyValueT] = input.input match {
      case FieldValue(resolver) => Value(resolver)
      case Value(resolver) => Value(resolver)
      case ListValue(resolver) => ListValue(resolver)
      case Obj(value) => Obj(value.asInstanceOf[AnyValueT])
    }
    val initArgs = RunWorkflowArgs(
      str(input.headWorkflow.cel + "${WORKFLOW_ENV}"),
      inputAs
    )
    val initCall = Call[RunWorkflowArgs[AnyValueT], AnyValueT](
      "Run",
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(initArgs)
    )
    val runTail = Fold("foldWorkflows", initCall.resultValue, input.tail) { case (b, wfArgs) =>
      val args = RunWorkflowArgs(
        str(wfArgs.get.workflowName.cel + "${WORKFLOW_ENV}"),
        obj(ChainableInput(b, Value[AnyValueT](wfArgs.get.params.cel)))
      )
      Call[RunWorkflowArgs[ChainableInput[AnyValueT, AnyValueT]], AnyValueT](
        "Run",
        "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
        obj(args)
      ).fn
    }
    Workflow((initCall :: runTail :: Nil) -> runTail.resultValue)
  })

  def apply(chain: us.awfl.utils.Chain[_]): Step[_, _] = {
    val buildWorkflows = buildList("buildWorkflows", (chain.init.tail :+ (chain.lastParams -> chain.lastWorkflow)).map { (p, w) =>
      WorkflowArgs(str(w.workflowName), p)
    })
    val args = RunWorkflowArgs(
      str(s"${workflowName}$${WORKFLOW_ENV}"),
      obj(Input(chain.input, str(chain.init.head._2.workflowName), buildWorkflows.resultValue)),
      connector_params = ConnectorParams(true)
    )
    val call = Call[RunWorkflowArgs[Input], AnyValueT](
      "callChain",
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(args)
    )
    Block("chainBlock", List[Step[_, _]](buildWorkflows, call) -> call.resultValue)
  }
}
