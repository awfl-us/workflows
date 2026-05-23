package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.workflows.tools.CliTools

trait Eval[P: Spec, R: Spec] extends us.awfl.core.Workflow {
  def directory: Value[String]
  def variables: Map[String, ListValue[Cel]]
  def experiment: Experiment[P, R]
  def csvCols: ListValue[String]

  override def workflows: List[Workflow[?]] = List({
    val buildParams = Product(variables)

    val runExperiments = ParallelFor("runExperiments", buildParams.resultValue) { rawParams =>
      val params = Value[P](rawParams.cel)
      val subDir = variables.keys.map[Cel] { k => CelFunc("map.get", params, k) }.reduce(_ + "_" + _)
      experiment("experiment", str(directory.cel + "/" + subDir), params).fn
    }

    def toRow(row: ListValue[String]) =
      Fold("buildHeader", str(""), row) { (b, c) => List() -> str(b.cel + "," + c) }
        .flatMap { s => str(CelFunc("text.substring", s, 1, CelFunc("len", s))) }

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