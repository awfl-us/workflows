package us.awfl.workflows.evals.experiments

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.codebase.ProjectManager
import us.awfl.workflows.evals.Experiment
import us.awfl.workflows.helpers.Worktree
import us.awfl.workflows.evals.Datasets
import us.awfl.workflows.tools.CliTools
import us.awfl.workflows.tools.Tasks.Task
import us.awfl.utils.Env

object SweBenchLite extends Experiment[BenchmarkParams, BenchmarkRunSummary] {
  val params = inputVal.flatMap(_.params).get

  override type Answer = BenchmarkAnswer

  val dataset = Datasets.sweBenchLite
  val runId = str("awfl-run")

  override def task: Step[Answer, Value[Answer]] = {
    val loadDataset = dataset.load(limit = Value(1))
    val runInstances = ParallelFor("runInstances", loadDataset.resultValue) { row =>
      val taskSession = str(CelFunc("uuid.generate"))
      val initWorktree = Worktree.init(taskSession, row.get.repo, row.get.base_commit)
      val query = str(
        ("Your job is to fix the following issue and ensure that all tests pass: ": Cel) +
        row.get.problem_statement +
        " * DON'T RESPOND UNTIL THE TESTS PASS OR YOU ARE COMPLETELY STUCK."
      )
      val task = Task(
        str("[URGENT] Important Issue"),
        str(
          row.get.problem_statement.cel +
          CelStr("""
            |TODO:
            |[ ] Investigate project architecture relevant to problem. Update this task.
            |[ ] Plan out implementation, changes needed, dependencies, etc. Update this task.
            |[ ] Implement changes. Update this task.
            |[ ] Run tests, check output.
            |[ ] Either: iterate back through this TODO list to fix tests, Or: mark task complete and respond.
            """.stripMargin).safe
        ),
        str("In Progress")
      )
      val runAgent = Switch("selectAgent", List(
        (params.agent.cel === "AWFL") -> ProjectManager(
          "runAgent",
          query,
          fund = Value(1),
          task = OptValue(obj(task)),
          env = obj(Env.get.copy(
            sessionId = taskSession,
            workdir = OptValue(initWorktree.resultValue)
          ))
        ).fn
      ))
      val getDiff = CliTools.runCommand(str("git diff --binary"), workdir = initWorktree.resultValue)
      val prediction = BenchmarkPrediction(row.get.instance_id, getDiff.resultValue, params.agent)
      List[Step[?,?]](initWorktree, runAgent, getDiff) -> obj(prediction)
    }
    val buildPredictions = Fold("buildPredictions", str(""), runInstances.resultValue) { (b, row) =>
      List() -> str(b.cel + CelFunc("json.encode_to_string", row) + CelStr("\n").safe)
    }
    val predictionsFile = str(directory + "/predictions.jsonl")
    val savePredictions = CliTools.writeFile(predictionsFile, buildPredictions.resultValue)
    val buildInstanceIds = For("buildInstanceIds", loadDataset.resultValue) { row =>
      List() -> row.get.instance_id
    }

    Try("task",
      List[Step[?,?]](loadDataset, runInstances, buildPredictions, savePredictions, buildInstanceIds) ->
        obj(BenchmarkAnswer(predictionsFile, buildInstanceIds.resultValue))
    )
  }

  override def eval: Value[Answer] => Step[Result, Value[Result]] = { answer =>
    val buildInstanceIds = Fold("buildInstanceIds", str(""), answer.get.instanceIds) { (b, id) =>
      List() -> str(b.cel + " " + id)
    }
    val evalCmd = str(
      CelStr("python -m swebench.harness.run_evaluation") +
        " --dataset_name " + dataset.name +
        " --predictions_path " + answer.get.predictionsFile +
        " --instance_ids " + buildInstanceIds.resultValue +
        " --run_id " + runId
    )
    val runEval = CliTools.runCommand(evalCmd, raiseError = true, timeoutSeconds = Value(60 * 15))
    val resultsFile = CliTools.readFile(str(("evaluation_results/": Cel) + runId + ".json"))
    Try("eval",
      List(buildInstanceIds, runEval, resultsFile) -> Value[Result](resultsFile.resultValue)
    )
  }
}

case class BenchmarkParams(agent: Value[String])

case class BenchmarkAnswer(
  predictionsFile: Value[String],
  instanceIds: ListValue[String]
)

case class BenchmarkPrediction(
  instance_id: Value[String],
  model_patch: Value[String],
  model_name_or_path: Value[String]
)

case class BenchmarkRunSummary(
  total_instances: Value[Double],
  completed_instances: Value[Double],
  resolved_instances: Value[Double],
  unresolved_instances: Value[Double],
  error_instances: Value[Double]
)

case class BenchmarkResult(
  instance_id: Value[String],
  harness: Value[String],
  model: Value[String],

  success: Value[Boolean],

  wall_time_seconds: Value[Double],

  input_tokens: Value[Double],
  output_tokens: Value[Double],
  reasoning_tokens: Value[Double],
  cached_input_tokens: Value[Double],

  estimated_cost_usd: Value[Double],

  files_modified: ListValue[String],

  patch_bytes: Value[Double],
  patch_lines: Value[Double],

  tests_passed: Value[Double],
  tests_failed: Value[Double],

  // repeated_file_reads: Value[Double],
  // crawler_count: Value[Double],
  // max_crawler_depth: Value[Double],

  context_bytes_sent: Value[Double],

  started_at: Value[String],
  completed_at: Value[String]
)