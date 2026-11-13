# Native Rust development loop

## Local-first execution rule for new coding sessions

The default iterative test environment is the existing **AWFL Scala GKE
development consumer**. Run the **complete existing Jobs Express service**
locally (`npm run start:jobs` from the server repository, with `PORT=5055`
and `WORKFLOWS_BASE_URL=http://127.0.0.1:5055`) alongside compiled Rust
workflows. Use a persistent interactive foreground terminal; verify
`GET http://127.0.0.1:5055/health` in a separate command.

See [server local-development instructions](https://github.com/awfl-us/server/blob/main/docs/native-local-development.md)
for the full command, authentication boundaries, and test sequence. Do
not redeploy Cloud Run, create Cloud Tasks, or republish 26 binaries just
to test a native compiler or Node server fix. The separately available
`npm run native:local` runs real Rust with **replayed recorded HTTP**;
it is not a fresh network test. Use the isolated cloud integration canary
only after local checks pass. Keep infrastructure/IAM Terraform-managed.



## Verified production state and immediate next gate — November 13, 2026

Project Manager and its dependency closure were published to native GCS from
[the successful release workflow](https://github.com/awfl-us/workflows/actions/runs/43517486016).
The normal authenticated API and Jobs services are now deployed with
`AWFL_NATIVE_EXECUTION_ENABLED=1`; a Terraform-managed, ready GKE
`native-jobs` worker handles detached native jobs. Terraform is applied
**manually**, while normal server CI updates deployed container images using
`gcloud`/`kubectl` without running Terraform.

The first regular production native session reached the Project Manager
dependency tree but failed on absent optional `env.workdir` and
`env.callingWorkflowExec`. These fields are intentionally omitted by the web,
CLI and agents session launchers, so the Rust compiler must match the Google
`default(map.get(...), null)` behavior rather than strict field lookup.
The same native compiler must bind `${WORKFLOW_ENV}` in the workflow-file
bucket name rather than accessing a literal bucket with a dollar-sign suffix.

The follow-up change set is in
[compiler-rust #37](https://github.com/awfl-us/compiler-rust/pull/37)
and [server #110](https://github.com/awfl-us/server/pull/110).
Server #110 journals confirmed child failures as catchable or terminal and
releases the parent execution lease without treating uncertain transport
outcomes as confirmed failures. Local compiled-Rust and Node regression tests
pass.

**Production foreground smoke PASSED:** compiler-rust #37, server #110,
and workflows #21 were merged. The server release
[43526306771](https://github.com/awfl-us/server/actions/runs/43526306771)
and Project Manager native dependency publication
[43526560336](https://github.com/awfl-us/workflows/actions/runs/43526560336)
succeeded. An authenticated Project Manager session launched with the
real web/CLI-shaped optional environment, called `LIST_DIRECTORY` against
the project workspace, persisted the tool's directory listing, and resumed
to a final assistant response. The initial native execution completed with
30 recorded effects, and the follow-up execution reached `Done` without
error. This verifies a single foreground execution cycle; detached/background
recovery and uncertain remote-effect replay remain separate work.


One command builds the unchanged Scala ProjectManager and all registered
dependencies using a single JVM compiler pass and a shared Cargo workspace.

    # From awfl-us/workflows, with compiler-rust checked out alongside it
    python3 scripts/native-dev.py codebase-ProjectManager --recursive

    # Publish via the existing GCP deployer IAM identity:
    python3 scripts/native-dev.py codebase-ProjectManager --recursive --publish

Paths: --compiler /path/to/compiler-rust, --output /path/to/rust-gens,
and --bucket awfl-us-native-workflows override defaults. --skip-compile
reuses generated crates while still verifying binaries;
--skip-compile --check only validates generated manifests.

## Actual behavior

1. Compile workflow Scala sources and load registered module classes.
2. NativeBatchMain builds all native variants in one compiler JVM.
3. Check static child references and explicitly registered dynamic targets.
   ProjectManager currently spans 12 Scala modules / 26 native workflows.
   Arbitrary runtime-selected agent/tool names are not a closed dependency
   graph; register them before relying on them.
4. Build all crates in a shared Cargo workspace and shared target directory.
   Preserve timestamps for identical generated Rust sources. After the first
   build, cache a validated JVM classpath keyed by hashes of Scala sources
   and build settings. Unchanged warm runs invoke Java directly, without
   starting sbt; source changes invalidate the cache automatically.
5. Verify ELF/musl architecture, schema/ABI/source hashes, effects and
   parallel capability metadata before publishing.
6. On --publish, the existing IAM-authorized publisher uploads immutable
   binaries to the Workflows-managed GCS bucket and activates manifests with
   parallelVersion, trusted globals, storage and callback capabilities.
   Unchanged artifacts and active mappings are skipped. A single batched
   GCS index download compares all active release manifests with the verified
   local releases. Only changed workflows invoke the publisher; to bypass the
   optimization use --force-publish.

The default without --publish performs no GCS changes, IAM changes, or
Terraform changes. First release builds can take minutes; subsequent builds
reuse compiled dependencies. A measured warm run of 26 ProjectManager
workflows in the Scala GKE consumer completed in **8.3 seconds** (including
Rust source generation, binary verification and zero-change Cargo build). JSON build reports are saved in the output dir.

## Native callback integration

The Jobs native callback handler stores records in the existing
user-scoped users/{uid}/callbacks collection. The native
events.create_callback_endpoint operation creates an internal callback URL;
the existing POST /jobs/callbacks step registers that record; an
authenticated POST /jobs/callbacks/{id} stores the response exactly once.
The native await effect subscribes to the record to resume after delivery.

The initial implementation uses a bounded active Jobs wait (25 minutes
maximum), rather than a durable suspended task that can survive an entire
hour. Long waits still require an asynchronous wake-and-resume protocol.
Callbacks from local native subworkflows must be explicitly cached if their
containing Jobs request may restart.

## Tests

    python3 -m unittest discover -s tests -p 'test_native_dev.py' -v

Server native callback tests run with Node's native test runner. The
server's callbackNativeIntegration.test.js can execute a real tools-CliTools
Rust binary by passing AWFL_NATIVE_TEST_TOOLS_BINARY=/absolute/path.

The publish command changes private GCS release mappings. It does not
enable native routing flags or change the end-user one-call session launcher.
