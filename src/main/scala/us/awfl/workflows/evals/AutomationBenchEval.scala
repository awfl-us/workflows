package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.evals.experiments.{AutomationBench, AutomationBenchParams, AutomationBenchResult}

/** Public AutomationBench workflow-building evaluation using the same per-row
  * temporary project / cloud consumer / AWFL agent lifecycle as SWE-bench.
  */
object AutomationBenchEval extends Eval[AutomationBenchParams, AutomationBenchResult] {
  override val inputVal = init("input")

  override def directory: Value[String] =
    str(("eval_runs/automationbench/": Cel) + input.runId)

  override def experiment: Experiment[AutomationBenchParams, AutomationBenchResult] =
    AutomationBench

  override def variables: Map[String, ListValue[String]] = Map(
    "domain" -> ListValue(List("operations").cel),
    "task_id" -> ListValue(List("1219").cel),
    "platform" -> ListValue(List("awfl-native-rust").cel),
    "model" -> ListValue(List("gpt-6-sol").cel),
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
