package us.awfl.workflows.helpers

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given
import us.awfl.ista.ChatMessage
import us.awfl.services.Llm.ToolChoice
import us.awfl.utils.Env
import us.awfl.workflows.traits.Agent
import us.awfl.workflows.tools.Sleep
import us.awfl.workflows.tools.Tasks.Task
import us.awfl.services.Llm.Tool

/**
 * Small: a lightweight, low-cost agent for quick direction-setting and minimal side effects.
 * - Uses a nano/small model (env.model) by default.
 * - Keeps the tool surface tiny; includes SLEEP for deliberate no-op pauses.
 */
object Small extends Agent {
  override def prompt =
    """You are Small, a lightweight agent optimized for quick direction-setting and minimal cost.
      |Behavior
      |- Keep answers concise and focused on the immediate next step.
      |- Prefer zero or near-zero side effects; use tools sparingly.
      |- If unsure or the best action is to pause, call SLEEP (a no-op) and return a brief note.
      |
      |Tasks
      |- Only create or update tasks when the user’s request clearly spans multiple steps.
      |- Keep TODOs succinct; update after meaningful progress.
      |
      |Tool etiquette
      |- Read/inspect before editing.
      |- Use SLEEP when pausing is the right choice.
      |
      |Response style
      |- 2–5 short bullets or a 1–2 sentence answer.
    """.stripMargin

  // Add the SLEEP tool alongside any server-configured tools (if present)
  override def buildTools = joinSteps("buildSmallTools", super.buildTools, Sleep.buildTools)

  // Default to a small/nano model for this agent.
  override def apply(
    name: String,
    query: Value[String],
    fund: Value[Double],
    spent: Value[Double] = Value(0),
    task: OptBase[Task] = OptValue.nil,
    toolChoice: OptBase[Tool] = OptValue.nil,
    env: BaseValue[Env] = obj(Env.get.copy(sessionId = str(workflowName), model = str("gpt-6-luna")))
  ) = super.apply(name, query, fund, spent, task, toolChoice, env)
}
