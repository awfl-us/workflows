package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.Env

object Eval {
  // Keep SWE-bench's original two-argument contract intact.
  case class Args(arg1: Value[String], arg2: Value[String])
  // A typed model list lets the same evaluation compare several models.
  case class ModelArgs(arg1: Value[String], arg2: Value[String],
    models: ListValue[String])
}

trait Eval[P: Spec, R: Spec, A: Spec] extends us.awfl.core.Workflow {
  override type Input = A
  override type Result = String

  def runId: Value[String]
  def limit: Value[Int]
  def directory: Value[String]
  def variables: Map[String, ListValue[String]]
  def experiment: Experiment[P, R]
  def csvCols: ListValue[String]

  // Legacy evals can keep the model injected by the launcher. Matrix evals
  // override these to use each row's selected model, and a neutral model
  // for evaluator-only file operations.
  def modelForExperiment(params: Value[P]): Value[String] = Env.model
  def modelForEvaluator: Value[String] = Env.model

  override def workflows: List[Workflow[?]] = List({
    val buildParams = Product(variables)

    val runExperiments = ParallelFor("runExperiments", buildParams.resultValue) { rawParams =>
      val params = Value[P](rawParams.cel)
      val subDir = variables.keys.map[Cel] { k => map.get( params, k) }.reduce(_ + "_" + _)
      val run = experiment(
        name = "experiment",
        runId = runId,
        limit = limit,
        directory = str(directory.cel + "/" + subDir),
        params = params,
        env = obj(Env.get.copy(
          model = modelForExperiment(params),
          sessionId = str(experiment.workflowName)
        ))
      )
      List[Step[?,?]](
        Log("logParams", str(("Running with params: ": Cel) + json.encode_to_string( params))),
        run
      ) -> run.resultValue
    }

    def toRow(row: ListValue[String]) =
      Fold("buildCsvRow", str(""), row) { (b, c) =>
        val joined = str(b.cel + "," + c)
        List() -> str(text.substring( joined, 0, core.len( joined)))
      }

    val buildHeader = toRow(csvCols)

    val buildCsv = Fold("buildCsv", buildHeader.resultValue, runExperiments.resultValue) { (b, result) =>
      val csvRow = For("csvRow", csvCols) { col => List() -> str(map.get( result, col)) }
      val buildRow = toRow(csvRow.resultValue)
      List(csvRow, buildRow) -> str(b.cel + CelStr("\n").safe + buildRow.resultValue)
    }
    val saveCsv = CliTools.writeFile(
      str(directory.cel + "/results.csv"),
      buildCsv.resultValue,
      env = obj(Env.get.copy(model = modelForEvaluator))
    )
    Workflow(List[Step[?, ?]](buildParams, runExperiments, buildHeader, buildCsv, saveCsv) -> runExperiments.resultValue)
  })
}