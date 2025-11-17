# AWFL Workflows (Scala)

AWFL Workflows is a Scala 3 toolkit for building, generating, and deploying Google Cloud Workflows with the AWFL DSL. It includes a ready-to-deploy, tool-enabled chat EventHandler, common CLI/task tools, and reusable helpers to accelerate new workflow development.

## Features
- Tool-enabled chat router (EventHandler) with prompts, preloads, and task guidance
- Mixin-based agents: compose behavior with small traits (Prompts, Preloads, Tasks, Cli, Tools)
- CLI/tool runners: READ_FILE, UPDATE_FILE, RUN_COMMAND, lightweight task helpers
- Helpers: ToolDispatcher, ToolDefs resolution, context preloads, task utilities, queueing
- Example workflows for reference (builders, dispatchers, domain samples)
- YAML generation to yaml_gens/ for direct deployment to Google Cloud Workflows

## Agent composition via mixins (traits)
The core design uses Scala traits so you can compose agent behavior declaratively. The built-in Agent trait wires together the chat workflow, prompts, preloads, task guidance, and CLI tools using the DSL.

- Composition stack
  - Agent extends Workflow with EventHandler with Preloads with Tasks with Cli
  - Prompts are layered by traits (Prompts → Cli → Tasks → Preloads output)
  - Tools are aggregated similarly (Tools → Cli tools → Task tools)
- Output workflows
  - Every Agent produces two workflows automatically:
    - {AgentName}: tool-enabled chat handler
    - {AgentName}-prompts: returns the composed prompt list for inspection/testing

Minimal agent example

```scala
import us.awfl.workflows.traits.Agent

object HelloAgent extends Agent {
  override def preloads = List(
    // Inject file/command output as additional system context
    PreloadFile("AGENT.md"),
    PreloadCommand("date -u")
  )

  override def prompt =
    """You are a helpful assistant for the Hello project.
       Respond succinctly and use the preloaded docs as context.""".stripMargin
}
```

Customizing tools and prompts

You can refine the allowed toolset or prompts by overriding the trait builders. For example, restrict to read-only CLI access by overriding buildTools:

```scala
import us.awfl.workflows.traits.Agent
import us.awfl.dsl.*
import us.awfl.dsl.auto.given

object ReadOnlyAgent extends Agent {
  override def prompt = "Read-only assistant"

  // Keep only READ_FILE; drop UPDATE_FILE and RUN_COMMAND
  override def buildTools = joinSteps(
    "readOnlyTools",
    super.buildTools,
    buildList("limitCliTools", List("READ_FILE"))
  )
}
```

Prompts are composed similarly. The Cli trait injects a small system note about file view limits; the Tasks trait appends task-management guidance and current task context. You can add your own guidance, e.g.:

```scala
override def buildPrompts = joinSteps(
  "extraGuidance",
  super.buildPrompts,
  buildList("myGuidance", List(ChatMessage("system", str("Prefer small, incremental changes."))))
)
```

Under the hood, EventHandler resolves the agent for the session, determines the allowed tool names, and executes tool calls via helpers.ToolDispatcher. See AGENT.md for a deeper walkthrough of routing, locking, and post-processing.

## Repository layout
- build.sbt – Scala 3, sbt build
- src/main/scala/us/awfl/workflows
  - EventHandler.scala – tool-enabled chat router
  - helpers/ – ToolDispatcher, ToolDefs, Context, Tasks, Queue
  - tools/ – CLI and task tools
  - traits/ – Agent, Tools, Tasks, Preloads, Prompts, Cli, ToolWorkflow
  - codebase/ – ProjectManager, AWFL CLI examples, builder workflows, domain samples
- yaml_gens/ – generated workflow YAML

## Prerequisites
- Java 17+
- sbt 1.11+
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

2) Generate YAML for one or more workflows (agents included)

This project uses the AWFL compiler to emit YAML. Pass fully qualified workflow class names to sbt run, for example:

```bash
sbt "run us.awfl.workflows.EventHandler"
sbt "run us.awfl.workflows.helpers.ToolDispatcher"
sbt "run us.awfl.workflows.codebase.ProjectManager"  # generates ProjectManager.yaml and ProjectManager-prompts.yaml
```

Outputs are written to yaml_gens/ (e.g., EventHandler.yaml, helpers-ToolDispatcher.yaml, ProjectManager.yaml, plus any companion -prompts workflows).

3) Inspect prompts (optional)

The -prompts workflow returns the fully composed prompt list (system prompt + CLI status + preloads + task guidance). After deployment, you can run it directly to review:

```bash
gcloud workflows run ProjectManager-prompts --location us-central1
```

4) Deploy manually with gcloud (example)

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

## Publishing to Maven Central (sbt-ci-release)
This repository is configured to publish to Maven Central using sbt-ci-release and sbt-dynver.

Prerequisites (GitHub repository secrets):
- SONATYPE_USERNAME, SONATYPE_PASSWORD
- PGP_SECRET (ASCII-armored private key), PGP_PASSPHRASE

How to publish a release:
1) Create an annotated tag for the release commit, e.g.:

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

2) Create and publish a GitHub Release for that tag. The CI workflow will run on release publish and invoke:

```bash
sbt -no-colors -Dsbt.supershell=false ci-release
```

Versioning notes:
- Versions are derived from Git tags via sbt-dynver.
- The default expects tags prefixed with "v" (e.g., v0.1.0). If you prefer unprefixed tags, uncomment the dynverTagPrefix setting in build.sbt.

Other notes:
- Do not set publishTo or credentials in build.sbt; sbt-ci-release manages Sonatype credentials from CI environment variables.
- The POM metadata (organization/name, description, homepage, licenses, scmInfo, developers) is included for Maven Central requirements.
- Ensure your release does not depend on -SNAPSHOT artifacts; Maven Central rejects releases with snapshot dependencies.

## License
MIT (see LICENSE)
