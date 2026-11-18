package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.evals.experiments.{AutomationBench, AutomationBenchParams, AutomationBenchResult}
import us.awfl.utils.Env

/** Public AutomationBench workflow-building evaluation using the same per-row
  * temporary project / cloud consumer / AWFL agent lifecycle as SWE-bench.
  *
  * Input: { arg1: runId, arg2: instanceLimit, models: [modelId, ...] }.
  * The model list expands through Eval's parameter product so each model
  * gets a separate evaluation row and its own temporary project.
  */
object AutomationBenchEval extends Eval[AutomationBenchParams, AutomationBenchResult, Eval.ModelArgs] {
  override val inputVal = init("input")

  override def runId: Value[String] = inputVal.get.arg1
  override def limit: Value[Int] = Value[Int](core.int(inputVal.get.arg2))

  override def modelForExperiment(params: Value[AutomationBenchParams]): Value[String] =
    params.get.model

  // The evaluator itself never calls an LLM, only its child agents do.
  override def modelForEvaluator: Value[String] = str("evaluation")

  override def directory: Value[String] =
    str(("eval_runs/automationbench/": Cel) + runId)

  override def experiment: Experiment[AutomationBenchParams, AutomationBenchResult] =
    AutomationBench

  override def variables: Map[String, ListValue[String]] = Map(
    "domain" -> ListValue(List("operations").cel),
    "task_id" -> ListValue(List("1219").cel),
    "platform" -> ListValue(List("awfl-native-rust").cel),
    "model" -> inputVal.get.models,
    "agent" -> ListValue(List("AWFL").cel),
    "fund" -> ListValue(List("1").cel)
  )

  override def csvCols: ListValue[String] = ListValue(List(
    "total_instances",
    "strict_passes",
    "partial_credit_sum",
    "builder_cost_usd",
    "total_agent_wall_time_seconds",
    "total_native_build_time_seconds",
    "workflow_runtime_seconds_sum",
    "results_jsonl"
  ).cel)
}
