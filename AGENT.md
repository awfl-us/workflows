Scala Workflows Agent Guide

Scope
- Location: src/main/scala/us/awfl/workflows
- Role: Resident expert for building and evolving Scala workflows (EventHandler, Agents, Prompts/Preloads) and their CLI/tool integration.

Project layout and build
- Scala sbt project
  - Build: sbt clean compile
  - Generate YAML: sbt "run us.awfl.workflows.codebase.ProjectManager"
  - YAML outputs: yaml_gens/{WorkflowName}.yaml and {WorkflowName}-prompts.yaml
- Source tree anchors
  - Event handler: src/main/scala/us/awfl/workflows/EventHandler.scala
  - Agent examples: src/main/scala/us/awfl/workflows/codebase/ProjectManager.scala, src/main/scala/us/awfl/workflows/codebase/awfl_cli/CliManager.scala
  - Traits: src/main/scala/us/awfl/workflows/traits/{Agent,Prompts,Preloads,Tasks,Tools}.scala
  - Helpers: src/main/scala/us/awfl/workflows/helpers/*

Core concepts
- Agents and prompts
  - Agents extend the Agent trait to define:
    - prompt: base system prompt text
    - preloads: files/commands to execute at start and inject as system messages
  - Each Agent automatically exposes two workflows:
    - {AgentName}: the tool-enabled chat handler (EventHandler)
    - {AgentName}-prompts: returns the composed prompt list (system prompt + CLI status + preloads + task guidance)

- EventHandler routing (latest)
  - Single entry point: eventHandler(...) builds a tool-enabled chat flow.
  - Prompts injection: Convo.completeWithTools receives Convo.Prompt(promptsWorkflow.flatMap(_.prompts)).
  - Agent → tools resolution:
    - Resolve agent id for the session: Agents.agentIdBySession
    - Fetch allowed tool names: Agents.toolsByAgent
    - Fallback to buildTools if no agent-specific list is found
    - Tool definitions are fetched via helpers.ToolDefs(sessionId, names)
  - Locking: acquire a session-scoped lock Locks.sessionKey("Convo") with owner = Exec.currentExecId; release on completion or failure.
  - Exec status lifecycle:
    - On start: Exec.updateExecStatus(..., "Running"); Exec.enqueueExecStatus("Running")
    - On success: Exec.updateExecStatus(..., "Done", terminal=true); Exec.enqueueExecStatus("Done")
    - On error: Exec.updateExecStatus(..., "Failed", terminal=true, error=...); Exec.enqueueExecStatus("Failed")
  - Conversation pipeline:
    - Save user message → complete with tools → save assistant message
    - If the assistant response has no tool calls, enqueue content to UI via Events.enqueueResponse
    - Extract tool calls → process via helpers.ToolDispatcher (separate workflow)
    - Post-processing: Summaries, ExtractTopics, ContextCollapser
  - Task seed (optional): If Input.task is provided to EventHandler, Tasks.maybeSaveInputTask creates a task for the session before completion.

Prompts and preloads composition
- traits/Prompts.buildPrompts
  - Builds a list of ChatMessage system prompts:
    - ChatMessage("system", str(prompt))
    - CliActions.cliStatusPrompt
- traits/Preloads (applied by Agent)
  - Executes preloads (PreloadFile, PreloadCommand) and appends their outputs to the prompt list.
- traits/Tasks
  - Adds task guidance prompts and exposes task-related tools.

Distributed lock pattern
- Key
  - Use Locks.sessionKey("Convo") for per-session response generation
  - Owner set to Exec.currentExecId
- Semantics
  - Acquire with TTL and owner; 409-style conflicts map to skip-work (acquired=false)
  - Always release if acquired; keep operations idempotent
- Environment isolation
  - If ista.name carries Env info, locks remain isolated per environment

Agent trait usage
- Agents bundle EventHandler + Prompts + Preloads + Tasks and set env.sessionId for you.
- Minimal example:
  - object HelloAgent extends us.awfl.workflows.traits.Agent {
      override def preloads = List(
        PreloadFile("AGENT.md"),
        PreloadCommand("date -u")
      )
      override def prompt =
        "You are a helpful assistant for the Hello project. Respond succinctly and use the preloaded docs as context."
    }
  - Generate YAML:
    - sbt "run us.awfl.workflows.codebase.ProjectManager"  // or your agent’s full name
    - Yields yaml_gens/YourAgent.yaml and yaml_gens/YourAgent-prompts.yaml
  - The -prompts workflow returns the fully constructed prompt list for inspection/testing.

TopicContextYoj filters (UI-side)
- Default order: [sizeLimiter(≈24k), toolCallBackfill]
  - sizeLimiter: greedy newer-first; preserves system messages; supports maxContentChars
  - toolCallBackfill: inserts synthetic tool replies for missing responses and converts orphan tool messages to role=system

DSL typing and string construction
- Strings vs Value[String]
  - Use CEL to build string Values and wrap with str(...):
    - val coll: Value[String] = str(("locks.": Cel) + ista.name.cel + "." + Yoj.kalaName)
  - Convert Values to CEL with .cel; wrap literals with ": Cel"
- BaseValue vs Value
  - Value[T] is a concrete CEL-evaluable value; BaseValue[T] can be Value or a derived expression
  - Prefer Value[String] for IDs/collection names passed to helpers

HTTP post helper
- post(...) auto-prefixes /job; pass relative paths (no leading /job or jobs/)

References
- EventHandler: src/main/scala/us/awfl/workflows/EventHandler.scala
- Prompts: src/main/scala/us/awfl/workflows/traits/Prompts.scala
- Preloads: src/main/scala/us/awfl/workflows/traits/Preloads.scala
- Tasks: src/main/scala/us/awfl/workflows/traits/Tasks.scala
- ProjectManager agent: src/main/scala/us/awfl/workflows/codebase/ProjectManager.scala
- CliManager agent: src/main/scala/us/awfl/workflows/codebase/awfl_cli/CliManager.scala
