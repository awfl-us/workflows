package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.dsl.Workflow
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.{Env, ENV}

trait Experiment[P: Spec, R: Spec] extends us.awfl.core.Workflow {
  override type Input = Experiment.Input[P]
  override type Result = R

  override val inputVal = init("input")

  type Answer

  def task: Step[Answer, Value[Answer]]
  def eval: Value[Answer] => Step[Result, Value[Result]]

  lazy val directory = inputVal.get.directory.cel

  lazy val inputFile = str(directory + "/input.json")
  lazy val answerFile = str(directory + "/answer.json")
  lazy val resultsFile = str(directory + "/results.json")

  def apply(
    name: String,
    runId: Value[String],
    limit: Value[Int],
    directory: Value[String],
    params: BaseValue[P],
    env: BaseValue[Env] = obj(Env.get.copy(sessionId = str(workflowName)))
  ): Call[RunWorkflowArgs[Experiment.Input[P]], R] = {
    execute(workflowName, obj(Experiment.Input(
      runId = runId,
      limit = limit,
      directory = directory,
      params = params,
      env = env
    )))
  }

  override def workflows: List[Workflow[?]] = List({
    val saveInput = CliTools.writeFile(inputFile, str(CelFunc("json.encode_to_string", inputVal)))
    val saveAnswer = CliTools.writeFile(answerFile, str(CelFunc("json.encode_to_string", task.resultValue)))

    val runEval = eval(task.resultValue)
    val saveResult = CliTools.writeFile(resultsFile, str(CelFunc("json.encode_to_string", runEval.resultValue)))

    Workflow(List[Step[?, ?]](saveInput, task, saveAnswer, runEval, saveResult) -> runEval.resultValue)
  })
}

object Experiment {
  case class Input[P](
    runId: Value[String],
    limit: Value[Int],
    directory: Value[String],
    params: BaseValue[P],
    env: BaseValue[Env] = ENV
  )
}
