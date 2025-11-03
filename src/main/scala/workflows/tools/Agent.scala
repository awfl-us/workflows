package workflows.tools

import dsl.*
import dsl.CelOps.*
import dsl.auto.given
import ista.ToolCall
import services.Llm.ChatToolResponse
import workflows.EventHandler
import workflows.traits.ToolWorkflow
import utils.Exec
import utils.Env

/**
 * tools/Agent
 * - Accepts CliTools.ToolWorkflowInput and invokes another workflow whose name matches the tool_call.function.name.
 * - Passes a EventHandler.Input built from the tool arguments: { query, task, (optional) model }.
 * - Returns CliTools.ToolWorkflowResult with the invoked workflow's ChatToolResponse encoded as JSON.
 *
 * Usage (tool args expected):
 * - name: <workflowName> (provided by tool_call.function.name)
 * - args:
 *   - query: string (required)
 *   - task: object (optional, will be forwarded to EventHandler.Input.task)
 *   - model: any (optional, forwarded to EventHandler.Input.model; if absent, a small default is used)
 */
object Agent extends workflows.traits.ToolWorkflow {
  override def workflows = List({
    val toolCall   = input.tool_call

    // Target workflow name is the tool name itself (no prefixing); caller is responsible for matching deployment name
    val nameV: BaseValue[String] = toolCall.get.function.get.name
    val nameStr: Cel = CelFunc("string", nameV.cel)

    val fn = toolCall.get.function.get
    val query: BaseValue[String] = fn.arg("query")
    val taskField: Field = Field(CelFunc("default", fn.arg("task").cel, CelConst("null")))
    // Model is optional; provide a conservative default suitable for tooling if omitted
    val modelField: Field = Field(CelFunc("default", fn.arg("model").cel, "gpt-5"))
    val fund = Value[Double](CelFunc("default", fn.arg("fund").cel, 0))

    val ehInstance = new EventHandler { def prompt = "" }

    val args = RunWorkflowArgs(
      // Invoke the workflow whose name matches the tool name directly
      str(nameStr + "${WORKFLOW_ENV}"),
      obj(
        ehInstance.Input(
          query = query,
          fund = fund,
          task = taskField,
          env = obj(Env.get.copy(sessionId = Value(nameStr)))
        )
      )
    )

    val run = Call[RunWorkflowArgs[ehInstance.Input], ChatToolResponse](
      "run_agent_workflow",
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(args)
    )

    val encoded = str(CelFunc("json.encode_to_string", run.resultValue.cel))

    Workflow(
      List(run) -> obj(ToolWorkflow.Result(encoded, run.result.total_cost))
    )
  })

  // Helper to invoke this workflow from elsewhere
  def apply(
    name: String,
    toolCall: BaseValue[ToolCall],
    cost: BaseValue[Double],
  ): Call[RunWorkflowArgs[ToolWorkflow.Input], ToolWorkflow.Result] = {
    val args = RunWorkflowArgs(str("tools-Agent${WORKFLOW_ENV}"), obj(ToolWorkflow.Input(toolCall, cost)))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }
}
