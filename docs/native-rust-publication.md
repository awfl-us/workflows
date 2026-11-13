> For rapid iteration, see [Native Rust development loop](native-development-loop.md)
> for the one-command batch compilation and publication of ProjectManager.

# Native Rust workflow publication and canary status

Status: **pure-workflow canary succeeded on 2026-11-11; broad rollout is disabled**.
Compiler PRs #1–#7 and server native PRs #72/#78/#79 are merged, but this
**does not** authorize non-pilot artifacts or change the runtime flag.
The published `NativePilot` is explicitly **no-effect**; newly implemented
`Try`/HTTP-error recovery and restricted `log.write` are verified in
locally generated Rust plus Node transaction-compatible tests, **not**
through live Jobs/GCP effect traffic. For the canonical feature matrix and
ordered acceptance gates, see
[compiler-rust current state and roadmap](https://github.com/awfl-us/compiler-rust/blob/main/docs/current-state-and-roadmap.md).

## Trusted-global release capability (backwards-compatible)

New compiler-rust crates include `trusted_globals_version: 1` in
`manifest.json`. The IAM-authorized `scripts/publish-native.sh`
checks this value and copies it to the active GCS release as the optional
`trustedGlobalsVersion: 1` metadata field. Missing metadata indicates
an old ABI-3 artifact: the server does not add `trusted_globals` to that
binary's strict protocol-v2 request.

For new releases, the authenticated Workflows service pins the capability
in the Firestore execution alongside the artifact digest; Jobs passes a
four-field trusted global envelope derived from persisted execution identity
and server-owned GCP deployment settings. The capability is **not** inferred
from request input or the caller's tenant project ID. Neither existing pinned
executions nor the published `NativePilot` are implicitly migrated. Deploy
the compatible server change before publishing or activating crates that
require globals; keep the normal native enablement flag off until all
production-readiness gates are passed.

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

## Remaining rollout gates (status and required evidence)

**Completed foundational milestones:** private content-addressed GCS bucket
and IAM-owned publisher, verified static musl ELF/Alpine compatibility, shared
opt-in launcher, typed continuation storage and Firestore journal, one isolated
pure GCP canary, compiler ABI 3/IPC 2, `Try`/`Raise` error recovery and
log handler integration **in merged source code**. These are necessary, not
sufficient, for production use.

1. **Compatibility inventory and differential tests:** enumerate actually
   constructed DSL/CEL/Call operations across helper dependencies; compare
   deterministic Google and native results, error/retry envelopes, null/bytes,
   globals, time/UUID, ordering and valid failure paths. Verify more real
   unchanged workflows than `LoadConversationHistory`/`Product`, including
   full `ProjectManager` dependencies.
2. **Effects/orchestration:** validate real, IAM-authorized HTTP dispatch,
   uncertainty reconciliation and remote idempotency (including crash *before*
   journal commit). Add durable callbacks, child workflow start/await,
   provider storage/PubSub adapters and real `Parallel`/`ParallelFor` joins.
3. **Security/reliability:** independent OS/network sandbox with no native
   GCP credentials, lease heartbeat and worker takeover, orphan sweeper,
   concurrent effects, Firestore contention, cancellation propagation,
   authenticated public launcher and session/status APIs.
4. **Publisher/rollback:** authorized CI dispatch of `publish-native.yml`,
   artifact provenance, active alias atomicity, retained pinned binary for
   in-flight runs, and a demonstrated rollback with Terraform state unchanged.
5. **Performance evidence:** repeat warm/cold experiments with representative
   workflows and equivalent durability, publishing p50/p95/p99, throughput,
   CPU/RSS, Firestore operations and cost. The one **6,836 ms** GCP canary
   includes cold startup and cannot substantiate an engine speedup claim.

**Go/no-go rule:** keep `AWFL_NATIVE_EXECUTION_ENABLED=0` in regular API/Jobs
until these gates pass; a merged PR or enabled zero-traffic tagged Jobs
revision is not a normal production switch.

Rollback for new executions is to keep/restore the **public API** native
feature flag to `0`. Continue to preserve pinned binary digests and execution
state for any already started native tasks. A zero-traffic tagged Jobs
revision is not a general production enablement.
