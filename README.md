# AWFL Workflows (Scala)

AWFL Workflows is a Scala 3 toolkit for building, generating, and deploying Google Cloud Workflows with the AWFL DSL. It includes a ready-to-deploy, tool-enabled chat EventHandler, common CLI/task tools, and reusable helpers to accelerate new workflow development.

## Features
- Tool-enabled chat router (EventHandler) with prompts, preloads, and task guidance
- CLI/tool runners: READ_FILE, UPDATE_FILE, RUN_COMMAND, lightweight task helpers
- Helpers: ToolDispatcher, ToolDefs resolution, context preloads, task utilities, queueing
- Example workflows for reference (builders, dispatchers, domain samples)
- YAML generation to yaml_gens/ for direct deployment to Google Cloud Workflows

## Repository layout
- build.sbt – Scala 3, sbt build
- src/main/scala/us/awfl/workflows
  - EventHandler.scala – tool-enabled chat router
  - helpers/ – ToolDispatcher, ToolDefs, Context, Tasks, Queue
  - tools/ – CLI and task tools
  - traits/ – Agent, Tools, Tasks, Preloads, Prompts, ToolWorkflow
  - codebase/ – ProjectManager, AWFL CLI examples, builder workflows, domain samples
- yaml_gens/ – generated workflow YAML

## Prerequisites
- Java 17+
- sbt 1.9+
- gcloud CLI authenticated to your GCP project (for deployment)
- Optional but recommended: pipx to install the awfl CLI for watch-and-deploy

Before deploying, ensure the Workflows API is enabled and you are authenticated:

```bash
gcloud auth login
# Optionally also:
gcloud auth application-default login

gcloud config set project YOUR_PROJECT_ID
gcloud services enable workflows.googleapis.com
```

## Quickstart
1) Build

```bash
sbt clean compile
```

2) Generate YAML for one or more workflows

This project uses the AWFL compiler to emit YAML. Pass fully qualified workflow class names to sbt run, for example:

```bash
sbt "run us.awfl.workflows.EventHandler"
sbt "run us.awfl.workflows.helpers.ToolDispatcher"
sbt "run us.awfl.workflows.codebase.ProjectManager"
```

Outputs are written to yaml_gens/ (e.g., EventHandler.yaml, helpers-ToolDispatcher.yaml, ProjectManager.yaml, plus any companion -prompts workflows).

3) Deploy manually with gcloud (example)

```bash
gcloud workflows deploy EventHandler \
  --source yaml_gens/EventHandler.yaml \
  --location us-central1

# To run
gcloud workflows run EventHandler --location us-central1
```

## Developer workflow with the awfl CLI (watch and auto-deploy)
The awfl CLI can watch your Scala sources, regenerate YAML on changes, and automatically deploy to Google Cloud Workflows.

- Install:

```bash
pipx install awfl
```

- Run the watcher from the project root:

```bash
awfl dev watch
```

What it does:
- Watches Scala sources for changes
- Invokes sbt to regenerate the relevant workflow YAML files under yaml_gens/
- Deploys updated workflows to Google Cloud Workflows

Requirements:
- gcloud must be installed, authenticated, and configured with your target project (and region as applicable)
- The Workflows API must be enabled

Stop the watcher with Ctrl+C.

## Using Agents, Prompts, and Preloads
Agents let you define a base system prompt and preloads that inject file/command output at workflow start. Each Agent automatically exposes two workflows:
- {AgentName}.yaml – the tool-enabled chat workflow
- {AgentName}-prompts.yaml – a companion workflow that returns the composed prompt list

See AGENT.md for detailed guidance and end-to-end examples, including the EventHandler flow, agent→tools resolution, locks/Exec lifecycle, and prompts/preloads/tasks composition.

## Tools overview
- CLI tools
  - READ_FILE { filepath }
  - UPDATE_FILE { filepath, content }
  - RUN_COMMAND { command }
- Task helpers
  - Create/update task records for long-running edits or batch work
- Tool dispatch
  - ToolDispatcher runs function tool calls and persists tool responses back into the conversation

## Notes for building workflows with the DSL
- Prefer Value[T] over raw String when constructing dynamic names/IDs; wrap CEL with str(...)
- EventHandler resolves the allowed tool set for an Agent and dispatches tool calls by exact function name

## Advanced documentation
- See AGENT.md for agent construction details and links to reference implementations (ProjectManager, CliManager)

## License
MIT (see LICENSE)
