# Native Rust development loop

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
   Preserve timestamps for identical generated Rust sources.
5. Verify ELF/musl architecture, schema/ABI/source hashes, effects and
   parallel capability metadata before publishing.
6. On --publish, the existing IAM-authorized publisher uploads immutable
   binaries to the Workflows-managed GCS bucket and activates manifests with
   parallelVersion, trusted globals, storage and callback capabilities.
   Unchanged artifacts and active mappings are skipped.

The default without --publish performs no GCS changes, IAM changes, or
Terraform changes. First release builds can take minutes; subsequent builds
reuse compiled dependencies. JSON build reports are saved in the output dir.

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
