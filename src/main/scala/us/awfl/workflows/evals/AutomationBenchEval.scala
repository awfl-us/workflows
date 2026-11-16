package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.evals.experiments.{AutomationBench, AutomationBenchParams, AutomationBenchResult}

/** Initial *local* AutomationBench adapter, sharing the existing Eval framework.
  *
  * The included Python reference artifact proves simulator/runtime separation.
  * It is NOT an AWFL-vs-n8n comparison or an agent-produced score.
  * Set up real authoring/deployment adapters before reporting benchmark results.
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
    "platform" -> ListValue(List("python-reference").cel),
    "model" -> ListValue(List("none").cel),
    "agent_harness" -> ListValue(List("prebuilt-reference").cel),
    "authoring_method" -> ListValue(List("code-prebuilt").cel),
    "artifact" -> ListValue(List(
      "/workspace/workflows/files/scripts/evals/automationbench/example_manifest.json"
    ).cel)
  )

  override def csvCols: ListValue[String] = ListValue(List(
    "task_id",
    "platform",
    "model",
    "agent_harness",
    "authoring_method",
    "build_success",
    "deployment_success",
    "task_completed_correctly",
    "partial_credit",
    "workflow_runtime_seconds",
    "business_tool_calls",
    "artifact_location"
  ).cel)
}
