package us.awfl.workflows.helpers

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.utils.*

/**
 * Agents
 * - Consolidated helper functions for resolving agentId by session and fetching an agent's tools.
 * - Plain helpers (no standalone workflow defs or runners).
 */
object Agents {
  // Service response envelopes
  case class SessionAgentResp(sessionId: Value[String], agentId: Value[String])
  case class AgentToolsResp(tools: ListValue[String])

  // Low-level GET helpers (Http helpers auto-prefix /job)
  def fetchSessionAgent(name: String, sessionId: Value[String])
      : Step[PostResult[SessionAgentResp], Value[PostResult[SessionAgentResp]]] = {
    val relativePath = str((("agents/session/": Cel) + sessionId))
    get[SessionAgentResp](name, relativePath, Auth())
  }

  def fetchAgentTools(name: String, agentId: Value[String])
      : Step[PostResult[AgentToolsResp], Value[PostResult[AgentToolsResp]]] = {
    val relativePath = str((("agents/": Cel) + agentId) + "/tools")
    get[AgentToolsResp](name, relativePath, Auth())
  }

  // High-level convenience helpers
  // Resolve agentId for a session; if missing (e.g., 404), fall back to "default".
  def agentIdBySession(name: String, sessionId: Value[String]) =
    Try(
      name,
      fetchSessionAgent(name, sessionId)
        .flatMap(_.body)
        .flatMap(_.agentId)
        .fn,
      _ => List() -> str("default")
    )

  // Return the list of tool names for a given agentId.
  def toolsByAgent(name: String, agentId: Value[String]) =
    fetchAgentTools(name, agentId)
      .flatMap(_.body)
      .flatMapList(_.tools)
}
