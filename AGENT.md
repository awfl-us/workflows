Scala Workflows Agent Guide

Scope
- Location: src/main/scala/us/awfl/workflows
- Role: Resident expert for building and evolving Scala workflows: full Agents, EventHandler wiring, prompt/context composition, task/fund guidance, and CLI/tool integration.
- Nearby foundational modules:
  - ../dsl: typed Scala DSL for generating workflow YAML.
  - ../workflow-utils: reusable runtime workflows/services used by this project.

Project layout and build
- Scala sbt project
  - Build: sbt clean compile
  - Generate YAML: sbt "run us.awfl.workflows.codebase.ProjectManager"
  - YAML outputs: yaml_gens/{WorkflowName}.yaml and {WorkflowName}-prompts.yaml
- Source tree anchors
  - Event handler: src/main/scala/us/awfl/workflows/EventHandler.scala
  - Agent examples: src/main/scala/us/awfl/workflows/codebase/ProjectManager.scala, src/main/scala/us/awfl/workflows/codebase/awfl_cli/CliManager.scala
  - Traits: src/main/scala/us/awfl/workflows/traits/{Agent,Prompts,Preloads,Tasks,Tools,Cli,Funds}.scala
  - Helpers: src/main/scala/us/awfl/workflows/helpers/*

Core mental model
- The stack is: DSL → workflow-utils → EventHandler → agent traits → concrete agents/workflows.
- DSL provides typed workflow construction: values, lists, CEL expressions, calls, branching, loops, joins, try/catch, and YAML generation.
- workflow-utils provides reusable runtime services: conversation persistence/completion, execution status, locks, UI events, topic/context management.
- EventHandler is the main chat turn orchestration layer: save user message, compose prompts, select tools, run tool-enabled completion, dispatch tool calls, enqueue final text, update status, post-process context.
- Agent traits are the composition layer. They decide which context, task state, tool set, CLI state, fund/budget guidance, and session-specific preloads each concrete agent receives.
- Full agents are usually small: define the prompt, preloads, and allowed tools/policies; inherit the heavy machinery.

High-level agent composition
- Agent shape
  - trait Agent extends EventHandler with Preloads with Tasks with Cli with Funds.
  - A full Agent therefore gets:
    - Chat turn orchestration from EventHandler.
    - Prompt/context construction from Prompts + Preloads.
    - Task guidance and task tools from Tasks.
    - CLI status/actions from Cli.
    - Budget/fund prompts from Funds.
    - Session-file awareness from Agent’s default preloads.
  - Agent.apply(name, query, fund, spent) executes the workflow with Env.sessionId set to workflowName. This is important because context, tools, locks, tasks, and events are session-scoped.

- Context layers
  - Base prompt
    - Supplied by each concrete agent’s prompt string.
    - Should define domain, behavior, boundaries, tool etiquette, task etiquette, budget policy, and response style.
  - Prompt workflow
    - Prompts exposes {workflowName}-prompts.
    - buildPrompts starts with ChatMessage("system", prompt).
    - Other traits extend buildPrompts and append additional system context.
  - Preloads
    - Preloads appends PreloadFile and PreloadCommand outputs to prompt context.
    - PreloadFile uses Context.preloadFile; PreloadCommand uses Context.preloadCommand.
    - Use preloads for stable, high-signal docs: AGENT.md, READMEs, architecture notes, policy docs, generated status summaries.
    - Avoid huge or noisy preloads; TopicContextYoj can trim conversational history, but system/preload content still competes for model attention.
  - Session context
    - Agent adds default commands:
      - echo 'Files uploaded/specific to the current session:'
      - ls -la sessions/{Env.sessionId}
    - This grounds the agent in uploaded/session-specific files without requiring each concrete agent to remember this pattern.
  - Runtime conversation context
    - EventHandler and workflow-utils persist user/assistant/tool messages.
    - TopicContextYoj filters help keep context usable by limiting size and repairing missing tool-call replies.

- Tools
  - Tools trait default:
    - buildTools returns an empty ListValue[String] wrapped in Try.
    - This means tools are opt-in.
  - Effective tool resolution in EventHandler:
    - Resolve agent id by session.
    - Fetch configured allowed tool names for that agent.
    - If no agent-specific tool list exists, fallback to buildTools.
    - Convert names to tool definitions via helpers.ToolDefs(sessionId, names).
    - Dispatch model tool calls through helpers.ToolDispatcher.
  - Agent design guidance
    - Prefer a minimal tool surface per agent.
    - Separate read-only, write, shell, network, VCS, and task tools conceptually.
    - Gate risky side effects in the prompt and/or tool layer.
    - Use concrete allowed-tool registration when possible; use buildTools override only when tools must be computed dynamically.
    - Keep task-management tools available for agents that perform multi-step work.

- Tasks
  - Tasks extends Prompts with Tools.
  - It appends:
    - Task management guidance prompts.
    - Current task context, such as in-progress, queued, and recently completed work.
  - It also extends buildTools with TasksTool.supported, exposing task create/update capabilities.
  - Operational guidance
    - Create an In Progress task before substantial multi-file edits, multi-step debugging, or long investigations.
    - Keep a TODO list in the task description.
    - Update the task after roughly three file reads or about six tool calls, and whenever a major finding changes the plan.
    - Mark Done promptly; mark Stuck with a concrete blocker.

- Funds/budget awareness
  - Funds appends system prompts containing:
    - Original funds allocated.
    - Spent so far.
    - Threshold guidance at one-third, two-thirds, and all funds spent.
  - Agent behavior should change as budget is consumed:
    - First third: finish investigation, converge on plan.
    - Two-thirds: finish main implementation, keep task state current.
    - All funds: stop work, update task, report latest status.

- Trait composition and ordering
  - Composition is implemented through Scala trait overrides of buildPrompts/buildTools and super calls.
  - Each trait layers its own context/tools onto the previous layer, typically via joinSteps.
  - When adding a trait, verify where it enters the linearization and whether its prompt/tool additions should happen before or after tasks, funds, preloads, or CLI state.

Agent design checklist
- Define the agent’s job in one crisp paragraph.
- Specify tool etiquette: when to read, search, edit, run commands, ask for confirmation, or stop.
- Specify task etiquette: when to open/update/close tasks.
- Specify context sources: durable preloaded docs, session uploads, current task context, runtime conversation.
- Choose a minimal allowed-tool set.
- Decide whether tools are configured externally by agent/session or computed by overriding buildTools.
- Decide whether budget/fund prompts should alter behavior or hard-stop work.
- Generate and inspect {AgentName}-prompts to verify context composition.
- Smoke-test a no-tool answer, a tool-call turn, a task update, and an error/failure path.

Common agent patterns
- Project/codebase manager
  - Context: AGENT.md, build docs, module READMEs, current task state, session files.
  - Tools: file read/write, shell/test runner, task tools, maybe VCS/PR tools.
  - Prompt policy: investigate before editing, update tasks frequently, respect budget thresholds, summarize risky commands.

- Read-only research agent
  - Context: domain docs and search/index state.
  - Tools: read/search/query only.
  - Prompt policy: cite files/sources, do not mutate state, create tasks only for extended research.

- CLI/operator agent
  - Context: CLI status, environment notes, session files, safety policy.
  - Tools: shell/CLI plus task tools.
  - Prompt policy: explain intent before destructive commands, prefer dry runs, stop on ambiguous errors.

- Workflow authoring agent
  - Context: DSL guide, workflow-utils API notes, examples of EventHandler and agents.
  - Tools: file read/write, sbt compile/run, generated YAML inspection.
  - Prompt policy: keep workflow steps typed and compositional; validate with compile/YAML generation.

Positioning vs other agent frameworks/platforms
- What this system is especially good at
  - Production workflow shape: agents compile/generate to explicit workflows rather than living only as ad hoc runtime Python/JS code.
  - Strong composition model: context, tasks, funds, CLI state, tools, and session data are layered through traits.
  - Operational discipline: locks, exec status, task state, budget prompts, and UI events are first-class concerns.
  - Inspectability: the -prompts workflow makes composed prompt context testable; generated YAML gives an artifact to review/deploy.
  - Scala type leverage: the DSL gives typed values/lists/steps and makes complex workflow construction less stringly than raw YAML.

- Tradeoffs compared with common frameworks
  - Compared with LangChain/LlamaIndex-style libraries:
    - Less plug-and-play ecosystem breadth.
    - More explicit deployment/runtime structure.
    - Better fit when you care about deterministic workflows, operational status, locking, and generated artifacts.
  - Compared with OpenAI Assistants/Responses-style hosted agent APIs:
    - More control over orchestration, context layering, tools, tasks, and UI events.
    - More responsibility for framework maintenance and ergonomics.
  - Compared with AutoGen/CrewAI-style multi-agent systems:
    - Less focused on emergent agent-to-agent chat patterns.
    - More focused on a single reliable workflow agent with explicit context/tools and operational guardrails.
  - Compared with Temporal/Durable Functions-style workflow systems:
    - More LLM-native and prompt/tool aware.
    - Likely less mature as a general-purpose durable execution platform, but more direct for chat-agent workflows.

- Overall assessment
  - This is a strong architecture for serious internal agents where reliability, reviewability, task continuity, budget control, and deployment artifacts matter.
  - Its main risk is complexity: new contributors must understand DSL, workflow-utils, EventHandler, and trait linearization.
  - The best documentation strategy is to keep AGENT.md high-level and example-driven, and push low-level DSL/API details into focused reference sections.

EventHandler routing
- Single entry point: eventHandler(...) builds a tool-enabled chat flow.
- Prompts injection: Convo.completeWithTools receives Convo.Prompt(promptsWorkflow.flatMap(_.prompts)).
- Agent → tools resolution:
  - Resolve agent id for the session: Agents.agentIdBySession.
  - Fetch allowed tool names: Agents.toolsByAgent.
  - Fallback to buildTools if no agent-specific list is found.
  - Tool definitions are fetched via helpers.ToolDefs(sessionId, names).
- Locking: acquire a session-scoped lock Locks.sessionKey("Convo") with owner = Exec.currentExecId; release on completion or failure.
- Exec status lifecycle:
  - On start: Exec.updateExecStatus(..., "Running"); Exec.enqueueExecStatus("Running").
  - On success: Exec.updateExecStatus(..., "Done", terminal=true); Exec.enqueueExecStatus("Done").
  - On error: Exec.updateExecStatus(..., "Failed", terminal=true, error=...); Exec.enqueueExecStatus("Failed").
- Conversation pipeline:
  - Save user message → complete with tools → save assistant message.
  - If the assistant response has no tool calls, enqueue content to UI via Events.enqueueResponse.
  - Extract tool calls → process via helpers.ToolDispatcher.
  - Post-processing: Summaries, ExtractTopics, ContextCollapser.
- Task seed: if Input.task is provided, Tasks.maybeSaveInputTask creates a task for the session before completion.

Distributed lock pattern
- Key
  - Use Locks.sessionKey("Convo") for per-session response generation.
  - Owner set to Exec.currentExecId.
- Semantics
  - Acquire with TTL and owner; conflicts map to skip-work or retry behavior.
  - Always release if acquired; keep operations idempotent.
- Environment isolation
  - If ista.name carries Env info, locks remain isolated per environment.

TopicContextYoj filters
- Default order: [sizeLimiter(≈24k), toolCallBackfill].
- sizeLimiter
  - Greedy newer-first.
  - Preserves system messages.
  - Supports maxContentChars.
- toolCallBackfill
  - Inserts synthetic tool replies for missing responses.
  - Converts orphan tool messages to role=system.

DSL quick reference (from ../dsl)
- Keep this section as a reference, not the main mental model for agents.
- Core value types
  - BaseValue[T]: abstract base for DSL values.
  - Value[T](resolver): concrete Resolved[T] with .get and .cel; build with init[T](name), Value(cel), or str(cel) for Value[String].
  - Resolved[T]: shared ops for Value/ListValue; carries resolver and cel.
  - Obj[T](t): wraps a Scala value for passing as args via obj(...).
  - ListValue[T](resolver): list-typed value; index with l(iCel) to get Value[T].
  - Resolver(path): collects CelPath segments; ++ to concatenate.
  - encodeJson(resolved): Cel function json.encode_to_string on a Resolved.
- CEL interop
  - Cel variants: CelConst(raw), CelStr(text).safe, CelOp, CelFunc(name, ...), CelPath, CelValue.
  - Implicit conversions: String/Int/Double/Boolean/Resolved → Cel. Use ("literal": Cel) to force CEL string literal semantics and str(...) to get a Value[String].
  - Operators (CelOps): ===, !==, >, >=, <, <=, +, -, *, //, in, &&, ||, unary !.
- Steps and control flow
  - Call[In, Out](name, call, args): invoke a callable and yield Value[Out].
  - Return(name, value): stop a branch; usually at workflow end.
  - For(name, in)(item => (steps, out)): map over ListValue to ListValue.
  - ForRange(name, from, to)(i => (steps, out)): numeric range to ListValue.
  - Fold(name, b, list){ (acc, item) => (steps, newAcc) }: accumulator over list.
  - Switch(name, cases): conditional; Switch.list for list-producing branches.
  - Try(name, run, except): try/catch; Value and List modes; except gets an Error(message, code).
  - Block(name, (steps, out)): sequence steps; returns out.
  - FlatMap extension on Step[..., Value[T]]: flatMap and flatMapList helpers for chaining.
  - Log(name, text): sugar for Call("sys.log", ...). Raise(name, Error): signal error.
- Collections and helpers
  - buildList(name, List[T]) and buildValueList(name, List[BaseValue[T]]): construct ListValue via Switch over indices.
  - join(name, listA, listB, ...): concatenate ListValues; joinSteps(name, stepA, stepB, ...) joins step results.
  - len(list): CEL len(list) for bounds math.
- Optionals
  - OptValue[T] = OptResolved(resolver) | OptObj(obj).getOrElse(default: Value[T]) uses CEL default(...).
  - OptList[T].getOrElse(default: ListValue[T]).

Workflow-utils primer (from ../workflow-utils)
- Convo
  - completeWithTools(messages, tools, ...): runs chat completion with tool usage enabled and returns assistant text + tool calls.
  - Prompt(...): wrapper to inject system prompts built by Prompts/Preloads/Tasks/Funds.
  - Persists messages and extracts tool calls for dispatching.
- Exec
  - currentExecId: stable id for this run; used as lock owner and status correlation id.
  - updateExecStatus(status, terminal?, error?): write run status.
  - enqueueExecStatus mirrors status to UI stream.
- Locks
  - sessionKey("Convo"): standard per-session mutual exclusion for chat turns.
  - Acquire with TTL and owner; release on success/error.
- Events
  - enqueueResponse(sessionId, content): push assistant text to UI when no tools are called.
- TopicContextYoj
  - Trims conversation context and repairs tool-call continuity for model consumption.

Practical tips
- Prefer high-signal, durable context over massive prompt dumps.
- Keep concrete agents small: prompt + preloads + tool policy.
- Use task tools for continuity whenever work spans multiple turns or multiple files.
- Treat funds as an operational constraint, not just informational text.
- Keep tool lists minimal and review risky tools carefully.
- For generated workflow code, keep steps small; use Block, Try, joinSteps, and typed args via obj(...).
- For IDs and collection names, prefer Value[String] built via str(("locks.": Cel) + ista.name.cel + "." + Yoj.kalaName).

References
- EventHandler: src/main/scala/us/awfl/workflows/EventHandler.scala
- Agent: src/main/scala/us/awfl/workflows/traits/Agent.scala
- Prompts: src/main/scala/us/awfl/workflows/traits/Prompts.scala
- Preloads: src/main/scala/us/awfl/workflows/traits/Preloads.scala
- Tasks: src/main/scala/us/awfl/workflows/traits/Tasks.scala
- Tools: src/main/scala/us/awfl/workflows/traits/Tools.scala
- Funds: src/main/scala/us/awfl/workflows/traits/Funds.scala
- ProjectManager agent: src/main/scala/us/awfl/workflows/codebase/ProjectManager.scala
- CliManager agent: src/main/scala/us/awfl/workflows/codebase/awfl_cli/CliManager.scala
