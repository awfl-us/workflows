package us.awfl.workflows.evals.experiments

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.evals.Experiment
import us.awfl.workflows.tools.CliTools
import us.awfl.workflows.tools.Tasks.Task
import us.awfl.workflows.codebase.ProjectManager
import us.awfl.workflows.helpers.Files
import us.awfl.utils.{Env, Projects, post}
import us.awfl.services.{Cloud, Llm}

/** Task-per-cloud-project agent evaluation, mirroring SweBenchLite.
  * The public dataset is loaded outside the agent's project. A distinct
  * parent-side scorer builds and grades the frozen agent output afterward.
  */
object AutomationBench extends Experiment[AutomationBenchParams, AutomationBenchResult] {
  val params = inputVal.flatMap(_.params).get
  override type Answer = AutomationBenchBatch

  private val runner = "/workspace/workflows/files/scripts/evals/automationbench/runner.py"
  private val grader = "/workspace/workflows/files/scripts/evals/automationbench/grade_submission.py"
  private val bootstrap = "/workspace/workflows/files/scripts/evals/automationbench/bootstrap_parent.sh"
  private val uv = "/home/awfl/.local/bin/uv"
  private val upstream = "/workspace/automationbench-upstream"
  private val rootDir = str(("/workspace/workflows/": Cel) + directory)
  private val outputSource = "src/main/scala/us/awfl/workflows/evals/automationbench/AgentSubmission.scala"

  case class StartProfile(sessionId: Value[String], imageProfile: Value[String])

  override def task: Step[Answer, Value[Answer]] = {
    val mkdir = CliTools.runCommand(str(("mkdir -p ": Cel) + rootDir),
      "createOutputDir", raiseError = true)
    // An ephemeral Scala consumer is not guaranteed to have Python 3.13,
    // uv or the pinned public AutomationBench dependencies installed.
    val setup = CliTools.runCommand(str("bash " + bootstrap),
      "bootstrapParentBenchmark", raiseError = true,
      timeoutSeconds = Value(300))
    val dataset = CliTools.runCommand(str(
      (s"$uv run --project $upstream python $runner dataset --domain ": Cel) +
      params.domain + " --task-id " + params.task_id + " --limit " + input.limit +
      " --format json"),
      "loadPublicDataset", raiseError = true, timeoutSeconds = Value(120))
    val rows = ListValue[AutomationBenchTask](json.decode(dataset.resultValue))
    val agents = ParallelFor("runCloudAgents", rows) { row =>
      val session = str(uuid.generate())
      val init = Try("initCloudAgent", {
        val project = Projects.create(str(("tmp/automationbench-": Cel) + session),
          str("https://github.com/awfl-us/workflows.git"))
        // Unlike SWE-bench's prebuilt /testbed image, the generic Scala image
        // starts with an empty /workspace. Clone the source before asking the
        // agent to inspect or write any relative paths.
        val env = Env.get.copy(projectId = project.result.body.get.project.id,
          sessionId = session, model = params.model,
          workdir = OptValue(str("/workspace/workflows")))
        val start = post[StartProfile, Boolean]("startScalaConsumer", "producer/start",
          obj(StartProfile(session, str("scala"))), env = env)
        List[Step[?, ?]](project, start) -> obj(env)
      })
      // The target working directory does not exist until checkout completes.
      val bootstrapEnv = obj(init.resultValue.get.copy(
        workdir = OptValue(str("/workspace"))))
      val checkout = CliTools.runCommand(
        str("test -d /workspace/workflows/.git || git clone --depth 1 https://github.com/awfl-us/workflows.git /workspace/workflows"),
        "checkoutBenchmarkRepo", raiseError = true,
        timeoutSeconds = Value(180), env = bootstrapEnv)
      // SWE-bench uses an AGENT.md file rather than a huge CEL expression.
      // Google Workflows expressions are limited to 400 characters.
      val loadAgentMd = Files.readFile("agents/AUTOMATIONBENCH_AGENT.md")
      val saveAgentMd = CliTools.writeFile(str("AGENT.md"),
        loadAgentMd.resultValue, "writeBenchmarkAgentMd", env = init.resultValue)
      val query = str(CelStr("Build a new AWFL Scala workflow for this task: ").safe +
        row.get.task_spec)
      val todo = Task(str(("AutomationBench ": Cel) + row.get.task_id),
        query, str("In Progress"))
      val startTime = CliTools.runCommand(str("date +%s"),
        "agentStartTime", env = init.resultValue)
      val run = ProjectManager("constructWorkflow", query,
        fund = Value[Double](CelFunc("double", params.fund)),
        task = OptValue(obj(todo)), env = init.resultValue)
      val endTime = CliTools.runCommand(str("date +%s"),
        "agentEndTime", env = init.resultValue)
      val source = CliTools.readFile(str(outputSource),
        "readAgentSubmission", env = init.resultValue)
      val usage = Llm.usage("agentUsage", env = init.result)
      val cleanup = Try("cleanupAgent", List[Step[?, ?]](
        Cloud.stop(init.result), Projects.delete(init.result.projectId)
      ) -> obj(true))
      val prediction = obj(AutomationBenchSubmission(
        domain = row.get.domain, task_id = row.get.task_id,
        source = source.resultValue, model = params.model,
        agent_wall_time_seconds = Value[Double](
          core.int(endTime.resultValue) - core.int(startTime.resultValue)),
        agent_cost_usd = usage.result.total_cost
      ))
      // Init must be outside the guarded region: otherwise the native
      // exception branch cannot resolve initCloudAgentResult for cleanup.
      val runAndCleanup = Try("runAndCleanup", List[Step[?, ?]](
        checkout, loadAgentMd, saveAgentMd, startTime, run, endTime, source, usage, cleanup
      ) -> prediction, err =>
        List[Step[?, ?]](cleanup, Raise("reRaiseAfterCleanup", err)) ->
          Value.nil[AutomationBenchSubmission])
      List[Step[?, ?]](init, runAndCleanup) -> runAndCleanup.resultValue
    }
    Try("task", List[Step[?, ?]](mkdir, setup, dataset, agents) ->
      obj(AutomationBenchBatch(agents.resultValue)))
  }

  override def eval: Value[Answer] => Step[Result, Value[Result]] = { answer =>
    // Use sequential For rather than ParallelFor: builds share the Cargo cache.
    val scores = For("gradeAgentSources", answer.get.submissions) { row =>
      val source = str(rootDir + "/submissions/" + row.get.task_id + ".scala")
      val output = str(rootDir + "/scores/" + row.get.task_id)
      val write = CliTools.writeFile(source, row.get.source, "writeFrozenSource")
      // Workflows expressions are capped at 400 characters. Stage long
      // commands as separate CEL assignments rather than one huge addition.
      val commandPrefix = Try("buildGradeCommandPrefix",
        List.empty[Step[?, ?]] ->
          str(CelStr(s"python3 $grader --source ").safe + source +
            CelStr(" --output ").safe + output))
      val cmd = str(commandPrefix.resultValue.cel +
        " --domain " + row.get.domain +
        " --task-id " + row.get.task_id + " --model " + row.get.model +
        " --max-calls 30 --timeout 780")
      val run = CliTools.runCommand(cmd, "independentGrade",
        raiseError = true, timeoutSeconds = Value(60 * 15))
      List[Step[?, ?]](write, commandPrefix, run) -> obj(AutomationBenchScored(
        task_id = row.get.task_id,
        score = Value[AutomationBenchScore](json.decode(run.resultValue)),
        agent_wall_time_seconds = row.get.agent_wall_time_seconds,
        agent_cost_usd = row.get.agent_cost_usd
      ))
    }
    val serialize = Fold("serializeScores", str(""), scores.resultValue) { (acc, row) =>
      List.empty[Step[?, ?]] ->
        str(acc.cel + json.encode_to_string(row) + CelStr("\n").safe)
    }
    val resultsPath = str(rootDir + "/all-scores.jsonl")
    val save = CliTools.writeFile(resultsPath, serialize.resultValue, "saveScores")
    case class Total(count: Value[Double], strict: Value[Double],
      partial: Value[Double], cost: Value[Double],
      runtime: Value[Double], agentSeconds: Value[Double], buildSeconds: Value[Double])
    val totals = Fold("aggregateScores", obj(Total(
      Value(0.0), Value(0.0), Value(0.0), Value(0.0),
      Value(0.0), Value(0.0), Value(0.0))), scores.resultValue) { (acc, row) =>
      List.empty[Step[?, ?]] -> obj(Total(
        Value[Double](acc.get.count.cel + 1),
        Value[Double](acc.get.strict.cel + row.get.score.get.task_completed_correctly),
        Value[Double](acc.get.partial.cel + row.get.score.get.partial_credit),
        Value[Double](acc.get.cost.cel + row.get.agent_cost_usd),
        Value[Double](acc.get.runtime.cel + row.get.score.get.workflow_runtime_seconds),
        Value[Double](acc.get.agentSeconds.cel + row.get.agent_wall_time_seconds),
        Value[Double](acc.get.buildSeconds.cel + row.get.score.get.build_wall_time_seconds)
      ))
    }
    Try("evaluation", List[Step[?, ?]](scores, serialize, save, totals) ->
      obj(AutomationBenchResult(
        total_instances = totals.resultValue.get.count,
        strict_passes = totals.resultValue.get.strict,
        partial_credit_sum = totals.resultValue.get.partial,
        builder_cost_usd = totals.resultValue.get.cost,
        total_agent_wall_time_seconds = totals.resultValue.get.agentSeconds,
        total_native_build_time_seconds = totals.resultValue.get.buildSeconds,
        workflow_runtime_seconds_sum = totals.resultValue.get.runtime,
        results_jsonl = resultsPath
      )))
  }
}

case class AutomationBenchTask(
  domain: Value[String], task_id: Value[String],
  task_name: Value[String], task_spec: Value[String]
)
case class AutomationBenchParams(
  domain: Value[String], task_id: Value[String],
  platform: Value[String], model: Value[String],
  agent: Value[String], fund: Value[String]
)
case class AutomationBenchSubmission(
  domain: Value[String], task_id: Value[String],
  source: Value[String], model: Value[String],
  agent_wall_time_seconds: Value[Double], agent_cost_usd: Value[Double]
)
case class AutomationBenchBatch(submissions: ListValue[AutomationBenchSubmission])
case class AutomationBenchScore(
  task_id: Value[String], task_completed_correctly: Value[Double],
  partial_credit: Value[Double], workflow_runtime_seconds: Value[Double],
  build_wall_time_seconds: Value[Double]
)
case class AutomationBenchScored(
  task_id: Value[String], score: Value[AutomationBenchScore],
  agent_wall_time_seconds: Value[Double], agent_cost_usd: Value[Double]
)
case class AutomationBenchResult(
  total_instances: Value[Double], strict_passes: Value[Double],
  partial_credit_sum: Value[Double], builder_cost_usd: Value[Double],
  total_agent_wall_time_seconds: Value[Double],
  total_native_build_time_seconds: Value[Double],
  workflow_runtime_seconds_sum: Value[Double],
  results_jsonl: Value[String]
)
