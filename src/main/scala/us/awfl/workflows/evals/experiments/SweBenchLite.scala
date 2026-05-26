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
import us.awfl.services.Cloud
import us.awfl.utils.Projects

object SweBenchLite extends Experiment[BenchmarkParams, BenchmarkRunSummary] {
  val params = inputVal.flatMap(_.params).get

  override type Answer = BenchmarkAnswer

  val dataset = Datasets.sweBenchLite
  val runId = str("awfl-run")

  override def task: Step[Answer, Value[Answer]] = {
    val loadDataset = dataset.load(limit = Value(1))

    val runInstances = ParallelFor("runInstances", loadDataset.resultValue) { row =>
      val taskSession = str(CelFunc("uuid.generate"))
      val initEnv = Try("initEnv", {
        val tmpProject = Projects.create(str(("tmp/": Cel) + taskSession), Value.nil)
        val env = Env.get.copy(
          projectId = tmpProject.result.body.get.project.id,
          sessionId = taskSession,
          workdir = OptValue(str("/testbed"))//OptValue(initWorktree.resultValue)
        )
        val image = str(("ghcr.io/epoch-research/swe-bench.eval.x86_64.": Cel) + row.get.instance_id)
        List[Step[?,?]](tmpProject, Cloud.start(env, image)) -> obj(env)
      })
      val findPython = CliTools.runCommand(
        str(CelStr(
          """echo "$PATH"
            |which python || true
            |which pytest || true
            |find / -path '*bin/pytest' 2>/dev/null | head -20
            |find / -path '*bin/python' 2>/dev/null | head -20
            |""".stripMargin
        ).safe),
        env = initEnv.resultValue
      )
      
      // val initWorktree = Worktree.init(taskSession, row.get.repo, row.get.base_commit)
      val query = str(
        ("Your job is to fix the following issue and ensure that all relevant tests pass: ": Cel) +
        row.get.problem_statement +
        CelStr(" * DON'T RESPOND UNTIL THE TESTS PASS OR YOU ARE COMPLETELY STUCK.\n\n").safe +
        "FAIL_TO_PASS: " + row.get.FAIL_TO_PASS +
        CelStr("\nPython env: ").safe + findPython.resultValue
      )
      val buildTodo = Try("buildTodo", List() ->
        str(CelStr("""
                  |TODO:
                  |[ ] Investigate project architecture relevant to problem. Update this task.
                  |[ ] Plan out implementation, changes needed, dependencies, etc. Update this task.
                  |[ ] Implement changes. Update this task.
                  |[ ] Run tests, check output.
                  |[ ] Either: iterate back through this TODO list to fix tests, Or: mark task complete and respond.
                  |            """.stripMargin).safe)
      )
      val task = Task(
        str("[URGENT] Important Issue"),
        str(query.cel + buildTodo.resultValue),
        str("In Progress")
      )

      val runAgent = Switch("selectAgent", List(
        (params.agent.cel === "AWFL") -> ProjectManager(
          "runAgent",
          query,
          fund = Value(1),
          task = OptValue(obj(task)),
          env = initEnv.resultValue
        ).fn
      ))

      val getDiff = CliTools.runCommand(str("git diff --binary"), env = initEnv.resultValue)
      val prediction = BenchmarkPrediction(row.get.instance_id, getDiff.resultValue, params.agent)
      val cleanup = Try("cleanup", List(
        Cloud.stop(initEnv.result),
        Projects.delete(initEnv.result.projectId)
      ) -> obj(true))

      Try("safeRun",
        List[Step[?,?]](initEnv, findPython, buildTodo, runAgent, getDiff, cleanup) -> obj(prediction),
        err => List[Step[?,?]](
          cleanup,
          Raise("reRaiseAfterCleanup", err)
        ) -> Value.nil
      ).fn
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
        obj(BenchmarkAnswer(predictionsFile, buildInstanceIds.resultValue, params.agent))
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
    val resultsFile = CliTools.readFile(str(answer.get.model_name_or_path.cel + "." + runId + ".json"))
    Try("eval",
      List(buildInstanceIds, runEval, resultsFile) -> Value[Result](resultsFile.resultValue)
    )
  }
}

case class BenchmarkParams(agent: Value[String])

case class BenchmarkAnswer(
  predictionsFile: Value[String],
  instanceIds: ListValue[String],
  model_name_or_path: Value[String]
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