package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.services.Llm.Tool
import us.awfl.workflows.tools.Tools
import us.awfl.utils._
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm.ToolFunctionDef

/**
 * ToolDefs
 * - Provides a standalone workflow to assemble tool definitions exposed to the LLM.
 * - First tries the tools service (/jobs/tools/list via Http helpers) which returns an object envelope.
 * - Falls back to local Tools.defs when the service is unavailable or errors.
 */
object ToolDefs extends us.awfl.core.Workflow {
  case class Input(toolNames: ListValue[String], env: BaseValue[Env] = ENV)
  override val inputVal = init[Input]("input")

  // Service-facing variant that carries workflowName metadata alongside the LLM tool def
  case class ToolWithWorkflow(`type`: String = "function", function: ToolFunctionDef, workflowName: BaseValue[String])
  
  case class Result(defs: ListValue[ToolWithWorkflow])

  // Envelope for service response: we avoid returning a bare list so PostResult[T] wraps an object
  case class ServiceResp(items: ListValue[ToolWithWorkflow])

  // Fetch tool defs from service with optional names filter (CSV supported by service)
  // Return an object envelope so PostResult[T] is PostResult[ServiceResp] (T is an object), not a list
  private def fetchDefsFromService(name: String, toolNames: ListValue[String]): Step[PostResult[ServiceResp], Resolved[PostResult[ServiceResp]]] with ValueStep[PostResult[ServiceResp]] = {
    // Http helpers prepend /jobs/ automatically; target /jobs/tools/list
    val names = Fold(s"${name}_foldNames", str(""), toolNames) { case (b, n) =>
      List() -> Value(b.cel + "," + n)
    }
    val relativePath = str(("tools/list?names=": Cel) + names.resultValue)
    val getStep = get[ServiceResp](name, relativePath, Auth())
    Block(s"${name}_block", List[Step[_, _]](names, getStep) -> getStep.resultValue)
  }

  // Expose as callable workflow (query-only: returns value list of Tool definitions)
  def apply(name: String, sessionId: Value[String], toolNames: ListValue[String]): Call[RunWorkflowArgs[Input], Result] = {
    val args = RunWorkflowArgs(str("helpers-ToolDefs${WORKFLOW_ENV}"), obj(Input(toolNames)))
    Call(name, "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", obj(args))
  }

  override def workflows = {
    val input = inputVal.get

    // Try service first; on failure use local fallback. Extract list from envelope.
    val fetchItems = fetchDefsFromService("getToolDefs", input.toolNames)
      .flatMap(_.body)
      .flatMap(_.items)

    List(Workflow(List(fetchItems) -> obj(Result(fetchItems.resultValue))))
  }
}
