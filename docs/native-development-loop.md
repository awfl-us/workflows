# Native Rust development loop


## Verified state and immediate next gate — November 13, 2026

This command is **merged** in [workflows #13](https://github.com/awfl-us/workflows/pull/13).
Its required single-JVM batch compiler is also **merged** in
[compiler-rust #31](https://github.com/awfl-us/compiler-rust/pull/31).
The unchanged ProjectManager target currently resolves to **12 Scala
modules and 26 native workflows**.

- Full first build in the GKE Scala consumer: **199.3 seconds**.
  A fresh Scala checkout repeat: **107.4 seconds**.
  Warm unchanged-source rerun: **52.5 seconds**, with **0.11 seconds**
  spent by Cargo. Scala/sbt startup and project compilation now dominate
  the warm iteration time; these are observations, not future guarantees.
- **All 26** emitted x86-64 static-musl binaries passed validation and
  the known static child references closed. Six runtime-selected calls
  remain; only the explicitly registered known dynamic targets are included.
  An arbitrary future runtime-chosen workflow requires inventory updates.
- Three representative active manifests (root, Prompts, and CliTools)
  passed **fake-GCS** publisher testing. The full 26-workflow release set
  has **not yet been published to actual GCS**.
- Native Jobs [server #97](https://github.com/awfl-us/server/pull/97)
  is merged for durable parallel parents with local synchronous children.
  [Server #98](https://github.com/awfl-us/server/pull/98) is **merged** (live Jobs revision verification pending)
  for callback.create/await using the existing AWFL callback service.
  Its 93-pass native regression suite includes a real compiled CliTools
  callback continuation, but uses a simulated HTTP adapter.
- The first live native ProjectManager reached six real effects before
  an obsolete tagged Jobs revision rejected workflow.run at the parallel
  fork; the execution was cancelled. No full native success claim.

**Next:** deploy callback-capable Jobs, publish and verify **all 26 real
active GCS release mappings**, run isolated native ProjectManager through
the existing authenticated API, then fix and repeat only observed failures.
Normal production native routing stays off. See the
[active ProjectManager integration plan](https://github.com/awfl-us/compiler-rust/blob/main/docs/projectmanager-native-integration-plan-2026-11-13.md).


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
   Unchanged artifacts and active mappings are skipped.

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
