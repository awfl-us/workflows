# AWFL workflows — agent instructions

Read [AGENT.md](AGENT.md) for the Scala workflow code conventions.

**ProjectManager native compiler/testing is local-first.** Use the
existing Scala GKE environment, compiled Rust binaries, and the complete
locally running AWFL server/jobs Express service. Do **not** deploy Cloud
Run or publish every workflow after each code fix. See
[docs/native-development-loop.md](docs/native-development-loop.md)
and the authoritative
[server local-development instructions](https://github.com/awfl-us/server/blob/main/docs/native-local-development.md).

Compile native workflow dependencies locally with:

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
before editing or running the benchmark. The evaluator and ProjectManager
dependency closure compile as **28 native Rust workflows**; a full
`gpt-6-sol` agent-generated and independently graded run is still pending.
Do **not** use the oversized Google Workflows YAML experiment or
`--recursive --publish` against the shared GCS aliases; 26 existing
production aliases differ from the local closure. Run locally with the
isolated native release registry, then prove native child/fork dispatch and
only afterward attempt one paid task 1219 run.

The workflows Scala consumer was restarted after Firebase credential bootstrap
failed; it is reachable by attaching project `gNoQN9KUw5IbtsXLag0M`.
The previously generated build report survived but Cargo binaries did not;
recompile before the native startup smoke. Preserve unrelated local dirty
paths; stage only files relevant to the change.
