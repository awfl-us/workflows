package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.services.Llm.Tool
import us.awfl.utils._
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm
import scala.annotation.tailrec

/**
 * ToolDefs
 * - Provides a standalone workflow to assemble tool definitions exposed to the LLM.
 * - First tries the tools service (/jobs/tools/list via Http helpers) which returns an object envelope.
 * - Falls back to local Tools.defs when the service is unavailable or errors.
 */
object ToolDefs extends us.awfl.core.Workflow {
  case class Input(toolNames: ListValue[String], env: BaseValue[Env] = ENV)
  override val inputVal = init[Input]("input")

  sealed trait ToolDefValue {
    def toLlm: Llm.ToolDefProperty = this match {
      case ToolDefStr => Llm.ToolDefProperty("string")
      case ToolDefNum => Llm.ToolDefProperty("number")
      case ToolDefEnum(e) => Llm.ToolDefProperty("string", `enum` = OptList(e))
      case ToolDefArray(i) => Llm.ToolDefProperty("array", items = Some(i.toLlm.toMap))
      case ToolDefObj(properties, required) =>
          Llm.ToolDefProperty(
            "object",
            properties = properties.transform { case (_, v) => v.toLlm },
            required = OptList(required)
          )
    }
  }
  object ToolDefStr extends ToolDefValue

  object ToolDefNum extends ToolDefValue

  case class ToolDefObj(
    properties: Map[String, ToolDefValue],
    required: ListValue[String]
  ) extends ToolDefValue

  case class ToolDefEnum(
    `enum`: ListValue[String] = ListValue.empty,
  ) extends ToolDefValue

  case class ToolDefArray(items: ToolDefValue) extends ToolDefValue

  case class ToolDefFunc(name: Value[String], description: String, parameters: ToolDefObj) {
    def toLlm = obj(Llm.ToolFunctionDef(
      name,
      str(description),
      obj(parameters.toLlm)
    ))
  }

  // Service-facing variant that carries workflowName metadata alongside the LLM tool def
  case class ToolWithWorkflow(
    `type`: Value[String] = str("function"),
    function: BaseValue[Llm.ToolFunctionDef],
    workflowName: Value[String]
  )
  
  case class Result(defs: ListValue[ToolWithWorkflow])

  // Envelope for service response: we avoid returning a bare list so PostResult[T] wraps an object
  case class ServiceResp(items: ListValue[ToolWithWorkflow])

  // Fetch tool defs from service with optional names filter (CSV supported by service)
  // Return an object envelope so PostResult[T] is PostResult[ServiceResp] (T is an object), not a list
  private def fetchDefsFromService(name: String, toolNames: ListValue[String]): Step[PostResult[ServiceResp], Value[PostResult[ServiceResp]]] = {
    // Http helpers prepend /jobs/ automatically; target /jobs/tools/list
    val names = Fold(s"${name}_foldNames", str(""), toolNames) { case (b, n) =>
      List() -> Value(b.cel + "," + n)
    }
    val relativePath = str(("tools/list?names=": Cel) + names.resultValue)
    val getStep = get[ServiceResp](name, relativePath, Auth())
    Block(s"${name}_block", List[Step[?, ?]](names, getStep) -> getStep.resultValue)
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
      .flatMapList(_.items)

    val maybeFetch = Switch.list("maybeFetch", List(
      (CelFunc("len", input.toolNames) > 0) -> fetchItems.fn,
      (true: Cel) -> (List.empty[Step[?, ?]] -> ListValue.empty)
    ))

    List(Workflow(List(maybeFetch) -> obj(Result(maybeFetch.resultValue))))
  }
}
