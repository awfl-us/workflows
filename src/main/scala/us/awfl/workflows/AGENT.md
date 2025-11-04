WorkflowBuilder/Scala Workflows Agent Guide

Scope
- Location: workflows/src/main/scala/workflows
- Role: Resident expert for building and evolving Scala workflows and their CLI integration.

Project layout and build
- Scala sbt project root: workflows/ (not the repository root)
  - Build: cd workflows && sbt clean compile
  - Watch/deploy: use dev.sh helpers (e.g., ./dev.sh watch-workflows) which build and deploy from workflows/
- Source tree: workflows/src/main/scala/workflows

Core concepts
- Runner model
  - Use Tools.Runner to expose tools. There are two classes:
    1) Standard CLI tools (UPDATE_FILE, READ_FILE, RUN_COMMAND) which enqueue-and-await via Pub/Sub.
    2) Query-only workflow runners (via Tools.invokerRunner) which call a workflow directly and return its content (no enqueue).
  - No generic enqueue fallback in CliEventHandler; every callable tool must have an explicit Runner.

- CliEventHandler routing
  - Provides tool-enabled chat with TopicContextYoj context assembly.
  - Exposes only Tool defs drawn from Tools.defs(runners).
  - On tool_calls, dispatches by exact functionName → Runner.run.
  - Non-tool assistant content is sent via CliTools.enqueueResponse (Pub/Sub) for UI display.

- Preload mechanics
  - Use us.awfl.workflows.helpers.Context.preloadFile(absPath) to read files at workflow start and compose them into the system prompt.
  - Use us.awfl.workflows.helpers.Context.preloadCommand(name, command) to run a command and include stdout in the system prompt (e.g., list YAML gens or show current time).
  - For WorkflowBuilder, preload this AGENT.md plus the workflow’s own Scala file to create a self-documenting prompt. Optionally add diagnostics (yaml gens listing, current UTC time).

- TopicContextYoj filters (JS side)
  - Default order: [sizeLimiter(≈24k), toolCallBackfill].
  - sizeLimiter: greedy newer-first; preserves system messages; supports maxContentChars pre-truncation; tokenization is pluggable.
  - toolCallBackfill: inserts synthetic tool replies for missing responses and converts orphan tool messages to role=system (strips tool_call_id by default).

- Pub/Sub conventions
  - Publish endpoint: googleapis.pubsub.v1.projects.topics.publish (no /locations).
  - CLI operations topic: projects/topaigents/topics/CliOperations.

DSL typing and string construction (important)
- Strings vs Value[String]
  - Plain Scala String (e.g., s"locks.${family}.${kala}") is not a Value[String].
  - In the DSL, build string Values using CEL and wrap with str(...), e.g.:
    - val coll: Value[String] = str(("locks.": Cel) + ista.name.cel + "." + Yoj.kalaName)
  - When combining dynamic parts, convert Values to CEL with .cel and literals to CEL with ": Cel", then wrap final CEL in str(...).
- BaseValue/Value/Field
  - Value[T] is a concrete CEL-evaluable value; BaseValue[T] can be Value or a derived expression; Field is for object field paths.
  - Prefer Value[T] for IDs/collection names passed to service helpers that require Value[String].
- Common patterns
  - Stringify numeric CEL (e.g., timestamps): val endStr = Value(CelFunc("string", kala.end.cel))
  - Generate runtime tokens: val owner: Value[String] = Value("uuid.generate()")
  - Build composite IDs: val id = str(sessionId.cel + (":": Cel) + endStr.cel)

Distributed lock pattern (general)
- Collection layout
  - Family source: use ista.name (the write target name). This aligns locking with what is written and avoids init-order nulls from referencing subclass fields too early.
  - Use one collection per workflow family and kala type: locks.{ista.name}.{Yoj.kalaName}
  - Document IDs encode scope to avoid per-session root collections:
    - SegKala / SessionKala: "{sessionId}:{end}"
    - WeekKala / TermKala: "{end}"
- Semantics
  - Acquire with TTL and owner; map HTTP 409 (exists) to skip-work (acquired=false).
  - Release only if acquired and include owner for validation.
  - Keep work idempotent regardless of locks (e.g., upsert/write gates).
- Environment isolation
  - If ista.name carries an environment suffix (e.g., Dev/Prod), locks remain isolated per environment without additional scoping.
- Defensive checks and telemetry
  - Assert/require non-empty ista.name before building keys; emit counters for acquired, busy, and reclaimed tagged by ista.name and KalaType.

Patterns and snippets
- Adding a query-only runner
  - Define apply(...) that runs your workflow via googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run and returns .message.content.
  - Expose it with Tools.invokerRunner(name, description, onQuery = query => apply(...).flatMap(_.message).flatMap(_.content)).

- Registering runners in CliEventHandler
  - Pass runners = List(YourRunner.runner, ...) when constructing CliEventHandler inside your workflow.

- Prepending context to system prompt
  - Compose a base prompt + preloaded files (AGENT.md, relevant Scala sources) and optional command outputs (yaml gens listing, now UTC) into ChatMessage("system", ...).

- HTTP post helper
  - post(...) auto-prefixes /job; pass relative paths (no leading /job or jobs/).

- Error handling and UX
  - Unknown tools should not enqueue; return a short unsupported-tool message.
  - Consider surfacing a system message listing available tool names if a tool is unsupported.

- Testing and guardrails
  - Run sbt clean compile from workflows/ before committing DSL changes.
  - Smoke test: issue a READ_FILE tool_call (should enqueue/await); issue a workflow runner call (should directly call workflow).
  - Grep guardrails: ensure no .invoker remains, and Pub/Sub endpoint matches canonical value.
  - Externalize environment-sensitive values (e.g., Pub/Sub project/topic, lock TTL) when appropriate.

- Naming and consistency
  - function.name in Tool defs must match your Runner name exactly.
  - Prefer background-{AgentName} for session IDs in query-only runners.
  - For locking, always derive the family from ista.name; do not copy/compute alternative names that might be uninitialized during construction.

Acceleration project (reference)
- Canonical checklist: projects/ACCELERATION.md
- Alias: projects/ACCELERATE.md (points to ACCELERATION.md)
- Purpose: orchestrate a cost-aware, parallel acceleration program. Start with bootstrap workflows; different classes of workflow agents tackle parts in parallel; minimize LLM spend via collapse-first, prune/file limiter before sizeLimiter, caching, tiered models, and telemetry-driven auto-tuning.
- WorkflowBuilder responsibilities:
  - Lead the initiative; create/maintain bootstrap runners (TopicContext assembler, collapse indexer, budget estimator) and wire CLI integration.
  - Enforce filter order: [collapseGroupReplacer, fileContentsLimiter, sizeLimiter, toolCallBackfill] and guardrails: docIds required, tool_call_id normalized, maxContentChars pre-truncation, idempotent operations.
  - Keep this AGENT.md and ACCELERATION.md in sync with defaults/presets and examples.

Testing a workflow after creation/modification
- Verify the generated YAML updated as expected:
  - Check workflows/yaml_gens for your workflow’s YAML file; ensure its mtime changed after your edit.
- Call the workflow with the CLI:
  - ./awfl-cli.sh call <workflow-name> <args...>
  - workflow-name is the YAML filename with dots replaced by dashes (example: codebase-workflows-WorkflowBuilder.yaml → codebase-workflows-WorkflowBuilder)

Usage
- Use projects/ACCELERATION.md as the single source of truth for tasks and milestones. Check off items as completed.
- Confirm governance decisions G1–G10 in ACCELERATION.md to unblock ContextAgent defaults/presets.
