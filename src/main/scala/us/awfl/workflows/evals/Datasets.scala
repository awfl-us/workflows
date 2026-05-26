package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.helpers.Files
import us.awfl.workflows.tools.CliTools

object Datasets {
  case class SweBenchInstance(
    instance_id: Value[String],
    repo: Value[String],
    base_commit: Value[String],
    problem_statement: Value[String],
    FAIL_TO_PASS: Value[String],
    hints_text: Value[String]
  )
  def sweBenchLite = Dataset[SweBenchInstance](str("SWE-bench/SWE-bench_Lite"))

  case class Dataset[Row: Spec](name: Value[String]) {
    def load(
      split: Value[String] = str("test"),
      limit: Value[Int] = Value(10),
      randomize: Value[Boolean] = Value(false),
      seed: Value[Int] = Value(1)
    ): Step[Row, ListValue[Row]] = {
      val params =
        ("DATASET='": Cel) + name +
        "';SPLIT='" + split +
        "';LIMIT='" + limit +
        "';RANDOMIZE='" + randomize +
        "';SEED='" + seed + "';"
      val loadScript = Files.scripts("load_dataset.sh")
      val runScript = CliTools.runCommand(str(params + loadScript.resultValue))
      val decode = For("decode", ListValue(CelFunc("text.split", runScript.resultValue, CelStr("\n").safe))) { row =>
        List() -> Value[Row](CelFunc("json.decode", row))
      }

      Try(
        "loadDataset", 
        List[Step[?, ?]](loadScript, runScript, decode) -> decode.resultValue
      )
    }
  }
}