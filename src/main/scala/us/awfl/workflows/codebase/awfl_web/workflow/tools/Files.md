# File tools (UPDATE_FILE, READ_FILE, RUN_COMMAND)

Overview
- Standard tools exposed to agents for codebase operations via CLI Pub/Sub callback pattern.
- Registered automatically by CliEventHandler (Tools.standard).

Tools
- UPDATE_FILE
  - Description: Update a file on disk with provided content.
  - Params: { filepath: string, content: string }
  - Behavior: Enqueues a CLI op; the callback writes the file atomically.

- READ_FILE
  - Description: Read a file from disk.
  - Params: { filepath: string }
  - Behavior: Enqueues a CLI op; the callback reads and returns file content (first 400 lines typical by policy).

- RUN_COMMAND
  - Description: Execute a shell command.
  - Params: { command: string }
  - Behavior: Enqueues a CLI op; the callback runs shell in a sandboxed environment and returns stdout/stderr.

Guardrails
- function.name must match Runner name exactly (UPDATE_FILE, READ_FILE, RUN_COMMAND).
- CLI layer should enforce path allowlists, safe command execution, and idempotency when applicable.

Integration
- Scala source: workflows/src/main/scala/workflows/tools/Tools.scala (standard runners)
- Pub/Sub + callback flow: workflows/src/main/scala/workflows/tools/CliTools.scala
- Registered in CliEventHandler via Tools.all(...).
