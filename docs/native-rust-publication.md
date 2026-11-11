# Native Rust workflow publication and canary status

Status: **pure-workflow canary succeeded on 2026-11-11; broad rollout is disabled**.

## Deployment ownership

- `infra/native_workflows.tf` (existing Workflows Terraform state) owns the
  private, versioned `awfl-us-native-workflows` bucket and publisher/runtime
  storage IAM. Do not add a second native artifact bucket to server Terraform.
- `awfl-us/server/infra/native-execution` (separate remote Terraform state)
  owns the native Cloud Tasks queue and OIDC task-invoker IAM.
- GitHub Actions `.github/workflows/publish-native.yml` uses the **existing
  GCP Workflows deployer service account** and OIDC, not application users,
  to publish a digest-addressed binary before updating the active alias.
  The server reads releases, checks SHA-256 and pins the digest in Firestore;
  it does **not** accept uploaded binaries or user-controlled deployment.

## Static artifact compatibility — required

Jobs uses `node:22-alpine` (musl). The first ABI 3 `NativePilot` artifact
requested glibc's `/lib64/ld-linux-x86-64.so.2` and failed at runtime
with `spawn ... ENOENT` even though the file and digest were correct.
The failed Cloud Task was deleted. The publisher now builds with
`rust:1.99.1-alpine`, target `x86_64-unknown-linux-musl`, rejects ELF
`PT_INTERP` and tests the resulting executable **inside `node:22-alpine`**.
This is checked by `.github/workflows/test-native-publisher.yml` and
`scripts/build-native-static.sh`.

A validated CI artifact was uploaded using the injected existing GCP IAM
identity after the GitHub App's manual `workflow_dispatch` request returned
HTTP **403** (`Resource not accessible by integration`). This is a
**publication automation gap**, not a missing project IAM role. Verify that
an authorized operator/CI context can dispatch `publish-native.yml`; do not
assume the GitHub App has Actions write permission. Do not use unverified
local binaries to update the alias.

Current active pure pilot manifest:

```text
Workflow: NativePilot
Manifest: gs://awfl-us-native-workflows/native-workflows/active/NativePilot.json
Binary:   gs://awfl-us-native-workflows/native-workflows/artifacts/109b82a73175a46ada8b4c5f5059b724f40017a8321b2c76edec9e62396f984e.bin
SHA-256:  109b82a73175a46ada8b4c5f5059b724f40017a8321b2c76edec9e62396f984e
ABI:      3
Protocol: 2
Effects:  []
```

## Real GCP canary evidence (November 11)

The server's shared launcher and native Jobs code are deployed (server PR
[#72](https://github.com/awfl-us/server/pull/72)), but `AWFL_NATIVE_EXECUTION_ENABLED=0`
on the regular API and Jobs revisions. For testing, Jobs revision
`jobs-00121-jaz` was tagged `native-rust-pilot`, enabled native execution,
and received **0% normal traffic**. Its Cloud Tasks request target was
`https://native-rust-pilot---jobs-4wmfqir74q-uc.a.run.app/native/execute`;
OIDC `audience` remained `https://jobs-4wmfqir74q-uc.a.run.app`.

The controlled pilot finished via **Cloud Tasks → Jobs → Firestore**:
`native_1432d417a1464e5dbc11af93e00e9f3f` returned `[2,3,9,9]`
in **6,836 ms** from enqueue to observation. A duplicate-delivery check
confirmed the completed execution and epoch were unchanged. This is one
pure-workflow sample; it does **not** validate end-user authentication,
public `/workflows/execute`, agent sessions, actual HTTP effects, or
representative p50/p95/p99 latency.

To rerun intentionally from the existing compiler-rust GKE workspace with
its injected GCP credential (not a regular user device), install
`google-cloud-firestore` and `google-cloud-tasks` and run:

```sh
python3 scripts/live_native_pilot.py \
  --jobs-url https://jobs-4wmfqir74q-uc.a.run.app \
  --dispatch-url https://native-rust-pilot---jobs-4wmfqir74q-uc.a.run.app \
  --execute --count 1 --timeout 70 --duplicate-delivery
```

`live_native_pilot.py` belongs to `awfl-us/compiler-rust`; the relative
command above is run from its repository root. The script requires an
explicit `--execute`; omitting it performs a dry run without creating tasks.
It initializes an isolated Firestore execution and **does not** represent
an end-user API smoke test.

## Remaining rollout gates

1. Test the authenticated shared `/workflows/execute` API and agent/MCP
   session, history, status/cancel, callback and Google fallback paths.
2. Complete effect reconciliation, retry/lease handling, orphan cleanup,
   process sandboxing, real authenticated HTTP handlers and adversarial
   authorization tests. Reject unknown or side-effecting release capabilities
   until proven safe.
3. Verify GCP IAM/WIF publication from the intended workflow, crash
   recovery, Terraform state ownership, and rollback under realistic load.
4. Add differential YAML/native conformance fixtures and p50/p95/p99
   measurements before expanding beyond the pure deterministic pilot.

Rollback for new executions is to keep/restore the **public API** native
feature flag to `0`. Continue to preserve pinned binary digests and execution
state for any already started native tasks. A zero-traffic tagged Jobs
revision is not a general production enablement.
