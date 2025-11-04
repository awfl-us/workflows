Executor Workflow Agent Guide

Scope
- Location: workflows/src/main/scala/workflows/codebase/workflows
- Role: Manage and evolve utils/Exec.scala

Responsibilities
- Preload and surface the contents of:
  - utils/Exec.scala (authoritative source under workflows/src/main/scala/utils)
  - This Executor.AGENT.md (agent charter and guardrails)
- Assist with safe edits to Exec.scala: improve ergonomics, add helpers, and preserve backwards compatibility.
- Encourage idempotent operations and defensive error handling.

Operational notes
- Prefer small, well-named helpers in Exec.scala that compose cleanly.
- Keep external shell invocations minimal and sanitized; when necessary, document assumptions.
- Run sbt clean compile before committing material changes.
- Consider adding simple unit tests for non-trivial helpers.

CLI integration
- Exposed via CliEventHandler with a system message that includes both the agent guide and Exec.scala contents.
- Query-only runner is provided for direct calls from the CLI.
