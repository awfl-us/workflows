package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.workflows.tools.CliTools

trait Eval[P: Spec, R: Spec] extends us.awfl.core.Workflow {
  def directory: Value[String]
  def variables: Map[String, ListValue[String]]
  def experiment: Experiment[P, R]
  def csvCols: ListValue[String]

  override def workflows: List[Workflow[?]] = List({
    val buildParams = Product(variables)

    val runExperiments = ParallelFor("runExperiments", buildParams.resultValue) { rawParams =>
      val params = Value[P](rawParams.cel)
      val subDir = variables.keys.map[Cel] { k => CelFunc("map.get", params, k) }.reduce(_ + "_" + _)
      val run = experiment("experiment", str(directory.cel + "/" + subDir), params)
      List[Step[?,?]](
        Log("logParams", str(("Running with params: ": Cel) + CelFunc("json.encode_to_string", params))),
        run
      ) -> run.resultValue
    }

    def toRow(row: ListValue[String]) =
      Fold("buildCsvRow", str(""), row) { (b, c) =>
        val joined = str(b.cel + "," + c)
        List() -> str(CelFunc("text.substring", joined, 1, CelFunc("len", joined)))
      }

    val buildHeader = toRow(csvCols)

    val buildCsv = Fold("buildCsv", buildHeader.resultValue, runExperiments.resultValue) { (b, result) =>
      val csvRow = For("csvRow", csvCols) { col => List() -> str(CelFunc("map.get", result, col)) }
      val buildRow = toRow(csvRow.resultValue)
      List(csvRow, buildRow) -> str(b.cel + CelStr("\n").safe + buildRow.resultValue)
    }
    val saveCsv = CliTools.writeFile(str(directory.cel + "/results.csv"), buildCsv.resultValue)
    Workflow(List[Step[?, ?]](buildParams, runExperiments, buildHeader, buildCsv, saveCsv) -> runExperiments.resultValue)
  })
}