package us.awfl.workflows.tools

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.codebase.Crawler
import us.awfl.workflows.traits.ToolWorkflow
import us.awfl.workflows.helpers.ToolDefs._
import us.awfl.services.Llm.ToolFunctionDef
import us.awfl.workflows.tools.Tasks
import us.awfl.utils.Env
import us.awfl.workflows.tools.Tasks.Task

object SpawnCrawlers extends ToolWorkflow {
  case class Args(
    items: ListValue[String],
    instructions: Value[String],
    task: OptValue[Task],
    perCrawlerBudget: Value[Double]
  )

  val buildTool = buildList("buildCrawlerTools", List(
    ToolWithWorkflow(
      function = ToolDefFunc(
        str("SPAWN_CRAWLERS"),
        """Spawn crawlers from an items list.
          |Spawn crawlers when a task requires applying
          |a uniform approach to all nodes in a tree
          |(files, webpages, knowledge graph, etc.)
          |
          |- items: A list of items that will each be assigned it's own agent.
          |  (directories, files, webpages, sub topics, sub regions, etc.)
          |
          |- instructions: The instructions for the sub-agents.
          |  The context of the overall task at hand and
          |  what they need to accomplish in their own unique domain.
          |
          |- task: Optional, for assigning multi stepped tasks.
          |  Initialize the task assigned to each crawler.
          |  The task should include a description and
          |  a TODO list for the agent to update incrementally.
          |
          |- perCrawlerBudget: You're paying for this so allocate funds wisely.
          |
          |Each sub-agent will receive their assigned line and your general instructions.
          |Sub-agents are ran in sessions ID'd after the item you assign.
          |You can follow up with sub-agents or assign new tasks by re-using the exact item values.
          |For example, to ask a single agent a question, spawn a single crawler with the same item.
        """.stripMargin,
        ToolDefObj(
          properties = Map(
            "items" -> ToolDefArray(ToolDefStr),
            "instructions" -> ToolDefStr,
            "task" -> Tasks.createParams,
            "perCrawlerBudget" -> ToolDefNum
          ),
          required = ListValue(List("items", "instructions", "perCrawlerBudget").cel)
        )
      ).toLlm,
      workflowName = str(workflowName)
    )
  ))

  override def workflows = List({
    val argsVal = Value[Args](CelFunc(
      "json.decode",
      inputVal.flatMap(_.tool_call).flatMap(_.function).flatMap(_.arguments)
    ))
    val args = argsVal.get

    case class CrawlerResponse(response: Value[String], cost: Value[Double])

    val spawn = ParallelFor("spawn", args.items) { item =>
      val itemPrompt = ("Your sole responsibility/area of focus is: ": Cel) + item + CelStr("\n").safe
      val crawlerSession = str(("crawler_": Cel) + CelFunc("base64.encode", CelFunc("text.encode", item, "UTF-8")))
      val updatedTask = Switch("updatedTask", List(
        (("task": Cel) in argsVal) -> (List() -> {
          val safeTask = args.task.getOrElse(Value.nil).get
          obj(safeTask.copy(description = str(itemPrompt + safeTask.description)))
        }),
        (true: Cel) -> (List() -> Value.nil)
      ))
      val runCrawler = Crawler(
        name = "crawler",
        query = str(itemPrompt + args.instructions),
        fund = args.perCrawlerBudget,
        spent = Value(0),
        task = OptValue(updatedTask.resultValue),
        env = obj(Env.get.copy(sessionId = crawlerSession))
      )
      Try(
        "tryRun",
        List[Step[?, ?]](updatedTask, runCrawler) -> obj(CrawlerResponse(
          response = str(
            ("Response from the agent assigned to ": Cel) + item + CelStr(":\n").safe +
            runCrawler.result.message.flatMap(_.content)
          ),
          cost = runCrawler.result.total_cost
        )),
        err => List() -> obj(CrawlerResponse(
          response = str(
            ("The crawler assigned to ": Cel) + item + " failed: " + err.get.message
          ),
          cost = Value(0)
        ))
      ).fn
    }

    val sumSpent = Fold("sumSpent", Value[Double](0), spawn.resultValue) { (b, c) => List() -> Value(b.cel + c.get.cost) }

    Workflow(
      List[Step[?, ?]](spawn, sumSpent) -> obj(ToolWorkflow.Result(
        str(CelFunc("json.encode_to_string", spawn.resultValue)),
        sumSpent.resultValue
      ))
    )
  })
}
