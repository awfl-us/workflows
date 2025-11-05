AWFL Workflows (Scala)

This repository contains the Scala 3 workflows package for building, generating, and deploying Google Cloud Workflows using the AWFL DSL. It provides a ready-to-deploy EventHandler for tool-enabled chat, a small set of CLI/task tool runners, and helpers/utilities you can reuse to build your own workflows quickly.

What this package includes
- EventHandler: Routes chat/tool calls and assembles context for LLM-assisted sessions.
- Tool runners and defs: File IO and shell tools (READ_FILE, UPDATE_FILE, RUN_COMMAND) and lightweight task helpers.
- Helpers: Context preloads (bring files/command output into the system prompt), ToolDispatcher, Tasks, and queue utilities.
- Example workflows: BuildManager, WorkflowBuilder, ToolBuilder, Executor, BusinessAnalysis, Summaries, and others.
- YAML output: All workflows compile to YAML under yaml_gens/ for deployment to Google Cloud Workflows.

Project layout
- build.sbt – Scala 3.3.1, sbt build
- src/main/scala/us/awfl/workflows – package sources
  - EventHandler.scala – tool-enabled chat router
  - tools/ – CLI tools and task tools
  - helpers/ – Context, ToolDispatcher, ToolDefs, Tasks, Queue
  - traits/ – Agent, Tools, Tasks, Preloads, Prompts, ToolWorkflow
  - codebase/workflows – WorkflowBuilder, ToolBuilder, BuildManager, Executor, Sutradhara, etc.
  - domain samples – BusinessAnalysis, Summaries, homeschool/ExtractLesson, etc.
- yaml_gens/ – generated workflow YAML files

Prerequisites
- Java 17+
- sbt 1.9+
- Optional: gcloud CLI authenticated to a project (for deployment)

Build and generate YAML
1) Install dependencies and compile
   - sbt clean compile

2) Generate YAML for one or more workflows
   - This project uses the AWFL compiler (us.awfl.compiler.Main) to emit YAML.
   - Pass fully qualified workflow class names as arguments to sbt run, for example:
     - sbt "run us.awfl.workflows.EventHandler"
     - sbt "run us.awfl.workflows.helpers.ToolDispatcher"
     - sbt "run us.awfl.workflows.codebase.workflows.WorkflowBuilder"
   - Outputs are written to yaml_gens/ (e.g., EventHandler.yaml, helpers-ToolDispatcher.yaml, WorkflowBuilder.yaml, etc.).

Deploy to Google Cloud Workflows (example)
- gcloud workflows deploy EventHandler \
    --source yaml_gens/EventHandler.yaml \
    --location us-central1

- To run:
  - gcloud workflows run EventHandler --location us-central1
  - Or call the executions API: googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run

Use in your own project
Option A – Reuse this package directly
- If published to your artifact repository, add to your sbt build:
  libraryDependencies += "us.awfl" %% "workflows" % "0.1.0-SNAPSHOT"
- Then reference the utilities and traits from us.awfl.workflows in your workflows.

Option B – Copy and adapt (quickest for experiments)
- Copy the bits you need from src/main/scala/us/awfl/workflows into your project and wire them into your AWFL DSL workflows. Common starting points:
  - EventHandler (router for tool-enabled chat)
  - tools/CliTools (READ_FILE, UPDATE_FILE, RUN_COMMAND)
  - helpers/Context (preloadFile, preloadCommand)
  - helpers/ToolDispatcher (standalone tool-call dispatcher)

Minimal example (Agent with prompt + preloads and CLI tools)
- Define a workflow object in your project and generate YAML via sbt run. The Agent trait automatically exposes:
  - MyAgent.yaml: the tool-enabled chat workflow
  - MyAgent-prompts.yaml: a companion workflow that returns the full prompt list (system prompt + CLI status + preloads + task guidance)

  package my.app

  import us.awfl.dsl.*
  import us.awfl.workflows.traits.Agent

  object MyAgent extends Agent {
    override val workflowName = "MyAgent"

    // Preload files/command output into the system prompt at workflow start
    override def preloads = List(
      PreloadFile("AGENT.md"),
      PreloadCommand("date -u")
    )

    // Base system prompt used to guide the agent
    override def prompt =
      "You are a helpful assistant for the Hello project. Respond succinctly and use the preloaded docs as context."
  }

- Generate YAML (both workflows are emitted when running the Agent):
  sbt "run my.app.MyAgent"
  # Outputs: yaml_gens/MyAgent.yaml and yaml_gens/MyAgent-prompts.yaml

- Deploy:
  gcloud workflows deploy MyAgent --source yaml_gens/MyAgent.yaml --location us-central1

Inspecting prompts
- Open yaml_gens/MyAgent-prompts.yaml to see the composed prompt list:
  - System message with your prompt
  - CLI status prompt
  - Preloaded content from files/commands
  - Task guidance prompts (if Tasks are enabled)

Tools overview (runners/defs)
- CLI tools
  - READ_FILE { filepath }
  - UPDATE_FILE { filepath, content }
  - RUN_COMMAND { command }
- Tasks helpers
  - Create/update task records for long-running edits or batch work
- Tool dispatch
  - ToolDispatcher wraps the run loop for function tool calls and persists tool responses back into the conversation

Notes for building workflows with the DSL
- Prefer Value[T] over raw String when building dynamic names/IDs in DSL code; wrap constructed CEL into str(...). See AGENT.md for tips.
- EventHandler composes the tool-enabled chat, resolves the allowed tools for an Agent, and dispatches tool calls by exact function name.

Advanced docs
- See AGENT.md for agent construction details (prompts/preloads composition, EventHandler flow, tool resolution, and DSL string typing patterns).

License
- MIT (see LICENSE)
