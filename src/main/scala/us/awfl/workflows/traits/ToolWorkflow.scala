package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.ista.{ToolCall, ToolCallFunction}
import us.awfl.utils.{Env, ENV}

trait ToolWorkflow extends us.awfl.core.Workflow {
  override type Input = ToolWorkflow.Input
  override type Result = ToolWorkflow.Result

  override val inputVal = init[Input]("input")

  // Generic helpers for building tool-call flows from DSL code

  // Build a ToolCall value given a function name and a params value (as Value)
  def makeToolCall(id: String, functionName: String, params: Value[?]) =
    obj(ToolCall(
      id = str(id),
      `type` = str("function"),
      function = obj(ToolCallFunction(
        str(functionName),
        str(CelFunc("json.encode_to_string", params))
      ))
    ))

  // Run a tool-call workflow (by workflow name), await callback, and return encoded callback body as a Block step
  def enqueueAndAwait(
    opName: String,
    targetWorkflowName: Value[String],
    toolCall: BaseValue[ToolCall],
    cost: BaseValue[Double],
    sessionId: Value[String] = Env.sessionId,
    background: Value[Boolean] = Env.background.getOrElse(Value(false)),
    timeoutSeconds: Value[Int] = Value(60),
    workdir: Value[String] = Env.get.workdir.getOrElse(Value.nil)
  ) = {
    val args = RunWorkflowArgs(
      targetWorkflowName,
      obj(ToolWorkflow.Input(
        toolCall,
        cost,
        OptValue(timeoutSeconds),
        env = obj(Env.get.copy(
          sessionId = sessionId,
          background = OptValue(background),
          workdir = OptValue(workdir)
        ))
      ))
    )
    val run = Call[RunWorkflowArgs[Input], Result](
      s"${opName}_run",
      "googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
      obj(args)
    )
    Block(s"${opName}_await_block", List[Step[?, ?]](run) -> run.resultValue.flatMap(_.encoded))
  }

  // Convenience: end-to-end tool call that returns the encoded callback body
  def callToolEncoded(
    opName: String,
    targetWorkflowName: Value[String],
    functionName: String,
    params: Seq[(String, BaseValue[?])],
    cost: BaseValue[Double] = obj(0.0),
    sessionId: Value[String] = Env.sessionId,
    background: Value[Boolean] = Env.background.getOrElse(Value(false)),
    timeoutSeconds: Value[Int] = Value(60),
    workdir: Value[String] = Env.get.workdir.getOrElse(Value.nil)
  ) = {
    val buildParams = Try("buildParams", List() -> obj(Map(params*)))
    val tcall     = makeToolCall(s"${opName}", functionName, buildParams.resultValue)
    val await     = enqueueAndAwait(opName, targetWorkflowName, tcall, cost, sessionId, background, timeoutSeconds, workdir)
    Block(
      s"${opName}_call_block",
      List[Step[?, ?]](
        buildParams, await
      ) ->
        // Return the encoded JSON string payload
        await.resultValue
    )
  }

  // Decode a string field from the encoded callback JSON
  def decodeField(encoded: Resolved[String], field: String): Value[String] =
    Value(CelFunc(
      "map.get",
      CelFunc("json.decode", encoded.cel),
      field
    ))
}
object ToolWorkflow {
  case class Input(
    tool_call: BaseValue[ToolCall],
    cost: BaseValue[Double],
    timeoutSeconds: OptValue[Int] = OptValue(60),
    env: BaseValue[Env] = ENV
  )
  case class Result(encoded: Value[String], cost: Value[Double])
}
