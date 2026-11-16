package us.awfl.workflows.evals.experiments

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.evals.Experiment
import us.awfl.workflows.tools.CliTools

/** A frozen externally executable artifact is the answer; the task environment
  * is recreated only inside the independent Python scoring process.
  *
  * For the first vertical slice the artifact is prebuilt; real agent-driven
  * build adapters will replace this staging step without changing the scorer.
  */
object AutomationBench extends Experiment[AutomationBenchParams, AutomationBenchResult] {
  val params = inputVal.flatMap(_.params).get

  override type Answer = AutomationBenchArtifact

  private val runner = "/workspace/workflows/files/scripts/evals/automationbench/runner.py"
  private val uv = "/home/awfl/.local/bin/uv"
  private val upstream = "/workspace/automationbench-upstream"
  private val runDirectory = str(("/workspace/workflows/": Cel) + directory)
  private val prefix = s"$uv run --project $upstream python $runner"

  override def task: Step[Answer, Value[Answer]] = {
    val ensureDirectory = CliTools.runCommand(
      str(("mkdir -p ": Cel) + runDirectory)
    )
    val prepareTask = CliTools.runCommand(
      str((s"$prefix prepare --domain ": Cel) +
        params.domain + " --task-id " + params.task_id +
        " --out " + runDirectory + "/task.json"),
      raiseError = true
    )
    Try("prepareAutomationBench",
      List[Step[?, ?]](ensureDirectory, prepareTask) ->
        obj(AutomationBenchArtifact(
          task_id = params.task_id,
          domain = params.domain,
          artifact = params.artifact,
          platform = params.platform,
          model = params.model,
          agent_harness = params.agent_harness,
          authoring_method = params.authoring_method
        ))
    )
  }

  override def eval: Value[Answer] => Step[Result, Value[Result]] = { answer =>
    val cmd = str((s"$prefix evaluate --domain ": Cel) +
      answer.get.domain + " --task-id " + answer.get.task_id +
      " --artifact " + answer.get.artifact +
      " --output-dir " + runDirectory +
      " --platform " + answer.get.platform +
      " --model " + answer.get.model +
      " --agent-harness " + answer.get.agent_harness +
      " --authoring-method " + answer.get.authoring_method +
      " --max-tool-calls 30 --timeout-seconds 120")
    val score = CliTools.runCommand(
      cmd, raiseError = true, timeoutSeconds = Value(180)
    )
    Try("independentAutomationBenchEval",
      List[Step[?, ?]](score) ->
        Value[Result](CelFunc("json.decode", score.resultValue))
    )
  }
}

case class AutomationBenchParams(
  domain: Value[String],
  task_id: Value[String],
  platform: Value[String],
  model: Value[String],
  agent_harness: Value[String],
  authoring_method: Value[String],
  artifact: Value[String]
)

case class AutomationBenchArtifact(
  task_id: Value[String],
  domain: Value[String],
  artifact: Value[String],
  platform: Value[String],
  model: Value[String],
  agent_harness: Value[String],
  authoring_method: Value[String]
)

case class AutomationBenchResult(
  task_id: Value[String],
  platform: Value[String],
  model: Value[String],
  agent_harness: Value[String],
  authoring_method: Value[String],
  build_success: Value[Boolean],
  deployment_success: Value[Boolean],
  task_completed_correctly: Value[Double],
  partial_credit: Value[Double],
  workflow_runtime_seconds: Value[Double],
  business_tool_calls: Value[Int],
  artifact_location: Value[String]
)
