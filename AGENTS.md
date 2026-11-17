# AWFL workflows — agent instructions

Read [AGENT.md](AGENT.md) for the Scala workflow code conventions.

**ProjectManager native compiler/testing is local-first.** Use the
existing Scala GKE environment, compiled Rust binaries, and the complete
locally running AWFL server/jobs Express service. Do **not** deploy Cloud
Run or publish every workflow after each code fix. See
[docs/native-development-loop.md](docs/native-development-loop.md)
and the authoritative
[server local-development instructions](https://github.com/awfl-us/server/blob/main/docs/native-local-development.md).

**Only when actively developing/changing ProjectManager itself**, compile
native workflow dependencies locally with (not needed for AutomationBench
against the deployed agent):

    python3 scripts/native-dev.py codebase-ProjectManager --recursive

When testing, route native HTTP effects to the locally running Jobs
`http://127.0.0.1:5055` application where suitable downstream credentials
exist. The server's `npm run native:local` uses previously recorded HTTP
results; that fast replay is **not** a fresh remote service call.

Use an isolated native Cloud Run canary after local tests to verify actual
IAM/Firestore/GCS integration. Do not change normal production
routing or published aliases just to test a benchmark; supported published
native workflows are already enabled. Manage all infrastructure and IAM
with Terraform.

## Ongoing AutomationBench evaluation (Rust)

Read [the task-specific status and handoff](docs/automationbench-native-handoff.md)
before editing or running the benchmark. **ProjectManager and its
agent/helper workflows are already deployed. Reuse them; do not rebuild
or republish the existing 26 workflow releases for this benchmark.**
The earlier full 28-workflow native rebuild was historical verification,
not a next step. Only the two benchmark-specific coordinator/experiment
names may need isolated builds if they are not already runnable. The
current `native-dev.py --publish` requires `--recursive`, so add a
selective path before publishing these two names; do not change the
existing aliases or deploy Google Workflows YAML. Use the deployed
agent execution and callback path. For grading, compile only the
freshly agent-authored `AgentSubmission.scala` in isolation. A full
`gpt-6-sol` agent-generated, independently graded benchmark run
is still pending.

The workflows Scala consumer was restarted after Firebase credential bootstrap
failed; it is reachable by attaching project `gNoQN9KUw5IbtsXLag0M`.
The missing 28 Cargo binaries were rebuilt and verified on November 17.
The reusable native dispatch smoke is
`files/scripts/evals/automationbench/smoke_native_dispatch.mjs`.
Do not rebuild the full closure again just because a consumer restarts.
Preserve unrelated local dirty paths; stage only relevant changes.
