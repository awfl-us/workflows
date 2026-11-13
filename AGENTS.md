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
IAM/Firestore/GCS integration. Keep normal production native routing off;
manage all infrastructure and IAM with Terraform.
