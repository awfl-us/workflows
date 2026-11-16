#!/usr/bin/env bash
# Build a frozen, standalone native AWFL AutomationBench task 1219 artifact.
# Usage: ./build_native.sh [output-directory]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="$(cd "$HERE/../../../.." && pwd)"
COMPILER="$(cd "${AWFL_COMPILER_RUST_ROOT:-$WORKFLOWS/../compiler-rust}" && pwd)"
OUT="${1:-$WORKFLOWS/eval_runs/automationbench-native-1219/artifact}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

SOURCE="$WORKFLOWS/src/main/scala/us/awfl/workflows/evals/automationbench/EmergencyDrill.scala"
WORKFLOW="evals-automationbench-EmergencyDrill-emergency-drill"
GENERATED="$OUT/generated/$WORKFLOW"
RUNTIME="$COMPILER/runtime"

test -f "$SOURCE" || { echo "Missing workflow: $SOURCE" >&2; exit 1; }
test -f "$COMPILER/frontend/build.sbt" || { echo "Missing native compiler: $COMPILER" >&2; exit 1; }

(
  cd "$COMPILER/frontend"
  sbt -batch \
    "set Test / unmanagedSources += file(\"$SOURCE\")" \
    "Test / runMain us.awfl.compiler.rust.NativeMain us.awfl.workflows.evals.automationbench.EmergencyDrill $OUT/generated $RUNTIME"
)
test -f "$GENERATED/Cargo.toml"

export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$COMPILER/target/local-shared}"
export CARGO_INCREMENTAL=0 CARGO_PROFILE_DEV_DEBUG=0
cargo build --quiet --manifest-path "$GENERATED/Cargo.toml"

PACKAGE="$(python3 - "$GENERATED/Cargo.toml" <<'PY'
import sys, tomllib
with open(sys.argv[1], 'rb') as f:
    print(tomllib.load(f)['package']['name'])
PY
)"
cp "$CARGO_TARGET_DIR/debug/$PACKAGE" "$OUT/emergency-drill"
cp "$HERE/native_driver.py" "$OUT/native_driver.py"
chmod u+x "$OUT/emergency-drill"

python3 - "$OUT" "$SOURCE" "$COMPILER" <<'PY'
import hashlib
import json
import pathlib
import subprocess
import sys

out, source, compiler = map(pathlib.Path, sys.argv[1:])
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def rev(repo):
    return subprocess.check_output(
        ['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
manifest = {
    'platform': 'awfl-native-rust',
    'command': ['{python}', '{artifact_dir}/native_driver.py',
                '{artifact_dir}/emergency-drill'],
}
(out / 'artifact.json').write_text(json.dumps(manifest, indent=2) + '\n')
metadata = {
    'source_sha256': sha(source),
    'compiler_commit': rev(compiler),
    'binary_sha256': sha(out / 'emergency-drill'),
    'driver_sha256': sha(out / 'native_driver.py'),
    'workflow': 'evals-automationbench-EmergencyDrill-emergency-drill',
}
(out / 'build_metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
print('Frozen native artifact:', out / 'artifact.json')
print(json.dumps(metadata, indent=2))
PY
