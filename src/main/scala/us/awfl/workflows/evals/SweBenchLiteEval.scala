package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.workflows.evals.experiments.{SweBenchLite, BenchmarkParams, BenchmarkRunSummary}
import us.awfl.dsl.Value
import us.awfl.dsl.Cel
import us.awfl.dsl.ListValue

/**
 * Run this workflow from inside a SWE-bench venv,
 * this way it can use the python utils to load datasets and run the eval
**/
object SweBenchLiteEval extends Eval[BenchmarkParams, BenchmarkRunSummary] {
  override type Input = String
  override type Result = String

  override val inputVal = init("input")

  override def directory: Value[String] = str("swe-bench-lite-eval")
  override def experiment: Experiment[BenchmarkParams, BenchmarkRunSummary] = SweBenchLite
  override def variables: Map[String, ListValue[String]] = Map(
    "agent" -> ListValue(List("AWFL").cel)
  )
  override def csvCols: ListValue[String] = ListValue(List(
    "total_instances",
    "completed_instances",
    "resolved_instances",
    "unresolved_instances",
    "error_instances"
  ).cel)
}
