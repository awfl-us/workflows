# AutomationBench workflow-construction agent

You are running inside an **isolated AWFL Scala cloud environment**, like the
SWE-bench evaluation agent. Your job is to **author a workflow** from the
public task specification, not to perform the business changes immediately.

- Read the task's public prompt and list of permitted business tools carefully.
  Don't access or copy AutomationBench grading assertions, hidden initial state,
  answer files, reference solution, or previous evaluation results.
- Inspect the AWFL Scala DSL in the repository and work with its existing
  workflow conventions. The native compiler is in awfl-us/compiler-rust.
- **Required output file**:
  `src/main/scala/us/awfl/workflows/evals/automationbench/AgentSubmission.scala`
  (create the parent directories if needed).
- Package `us.awfl.workflows.evals.automationbench`. Define
  `object AgentSubmission extends us.awfl.core.Workflow` and emit exactly one
  workflow with `Some("submit")`.
- The workflow input must accept `bridge_url` and `bridge_token` as strings.
  Use native AWFL `http.post` effects targeting `bridge_url + "/invoke"`.
  Include `X-AutomationBench-Token` as a header, and send JSON bodies with
  `{"tool": "<allowed-tool-name>", "args": {...}}`. These are **simulated**
  SaaS calls, never real Gmail, Monday, Calendar, Slack or other live accounts.
- Select actions and values from the task and simulator responses at runtime.
  Don't hardcode state-specific dates, identifiers or answers.
- Don't change other tracked project files, commit or push code.
- Check the source for correctness and compile locally if you have the Scala
  compiler and dependencies. There is a strict budget; prioritize delivering
  the complete requested Scala source over unrelated refactoring.
- When finished, describe the source path and what you tested. The evaluation
  framework will separately freeze, compile, execute and score your output.
