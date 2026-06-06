package us.awfl.workflows.tools

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.helpers.ToolDefs.*
import us.awfl.workflows.traits.ToolWorkflow

/** A deliberately no-op tool workflow used to validate Small-agent tool wiring. */
object Sleep extends ToolWorkflow {
  val supported = List("SLEEP")

  val buildTools = buildList("buildSleepTools", List(
    ToolWithWorkflow(
      function = ToolDefFunc(
        str("SLEEP"),
        "No-op workflow. Use it when the Small agent should deliberately pause/defer without side effects.",
        ToolDefObj(
          properties = Map.empty,
          required = ListValue.nil
        )
      ).toLlm,
      workflowName = str(workflowName)
    )
  ))

  override def workflows = List(Workflow(
    List.empty[Step[?, ?]] -> obj(ToolWorkflow.Result(
      str("{\"ok\":true,\"tool\":\"SLEEP\",\"message\":\"No-op completed.\"}"),
      Value(0)
    ))
  ))
}
