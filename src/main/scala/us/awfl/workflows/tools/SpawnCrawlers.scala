package us.awfl.workflows.tools

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.workflows.codebase.Crawler
import us.awfl.workflows.traits.ToolWorkflow
import us.awfl.workflows.helpers.ToolDefs._
import us.awfl.services.Llm.ToolFunctionDef
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.Env

object SpawnCrawlers extends ToolWorkflow {
  case class Args(
    producerCommand: Value[String],
    instructions: Value[String],
    task: Value[String],
    perCrawlerBudget: Value[Double]
  )

  val buildTool = buildList("buildCrawlerTools", List(
    ToolWithWorkflow(
      function = ToolDefFunc(
        str("SPAWN_CRAWLERS"),
        """Spawn crawlers from a producer command.
          |Spawn crawlers when a task requires applying
          |a uniform approach to all nodes in a tree
          |(files, webpages, knowledge graph, etc.)
          |
          |- producerCommand: Your command must result in a list
          |  where each line is an item that will be assigned it's own agent.
          |  (directories, files, webpages, sub topics, sub regions, etc.)
          |
          |- instructions: The instructions for the sub-agents.
          |  The context of the overall task at hand and
          |  what they need to accomplish in their own unique domain.
          |
          |- task: Initialize the task assigned to each crawler.
          |  The task should include a description and
          |  a TODO list for the agent to update incrementally.
          |
          |Each sub-agent will receive their assigned line and your general instructions.
        """.stripMargin,
        ToolDefObj(
          properties = Map(
            "producerCommand" -> ToolDefStr,
            "instructions" -> ToolDefStr,
            "task" -> ToolDefStr,
            "perCrawlerBudget" -> ToolDefNum
          ),
          required = ListValue(List("producerCommand", "instructions", "task", "perCrawlerBudget").cel)
        )
      ).toLlm,
      workflowName = str(workflowName)
    )
  ))

  override def workflows = List({
    val args = Value[Args](CelFunc(
      "json.decode",
      inputVal.flatMap(_.tool_call).flatMap(_.function).flatMap(_.arguments)
    )).get

    val runCommand = CliTools.runCommand(args.producerCommand)

    val lines = ListValue[String](CelFunc("text.split", runCommand.resultValue, CelStr("\n").safe))

    case class CrawlerResponse(response: Value[String], cost: Value[Double])

    val spawn = ParallelFor("spawn", lines) { line =>
      val linePrompt = ("Your sole responsibility/area of focus is: ": Cel) + line + CelStr("\n").safe
      val crawlerSession = str(("crawler_": Cel) + CelFunc("base64.encode", CelFunc("text.encode", line, "UTF-8")))
      val runCrawler = Crawler(
        name = "crawler",
        query = str(linePrompt + args.instructions),
        fund = args.perCrawlerBudget,
        spent = Value(0),
        task = str(linePrompt + args.task),
        env = obj(Env.get.copy(sessionId = crawlerSession))
      )
      val result = runCrawler.result
      List(runCrawler) -> obj(CrawlerResponse(
        response = str(
          ("Response from the agent assigned to ": Cel) + line + CelStr(":\n").safe +
          result.message.flatMap(_.content)
        ),
        result.total_cost
      ))
    }

    val sumSpent = Fold("sumSpent", Value[Double](0), spawn.resultValue) { (b, c) => List() -> Value(b.cel + c.get.cost) }

    Workflow(
      List[Step[?, ?]](runCommand, spawn, sumSpent) -> obj(ToolWorkflow.Result(
        str(CelFunc("json.encode_to_string", spawn.resultValue)),
        sumSpent.resultValue
      ))
    )
  })
}
