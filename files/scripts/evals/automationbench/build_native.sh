#!/usr/bin/env bash
# Freeze a native AWFL benchmark artifact via the production native-dev builder,
# without publishing to GCS.
# Usage: ./build_native.sh [output-directory] [Scala-class] [workflow-variant]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="$(cd "$HERE/../../../.." && pwd)"
COMPILER="$(cd "${AWFL_COMPILER_RUST_ROOT:-$WORKFLOWS/../compiler-rust}" && pwd)"
OUT="${1:-$WORKFLOWS/eval_runs/automationbench-native-1219/artifact}"
CLASS="${2:-us.awfl.workflows.evals.automationbench.EmergencyDrill}"
WORKFLOW="${3:-evals-automationbench-EmergencyDrill-emergency-drill}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

SOURCE="$WORKFLOWS/src/main/scala/${CLASS//.//}.scala"
GENERATED="$OUT/generated"
test -f "$SOURCE" || { echo "Missing Scala source: $SOURCE" >&2; exit 1; }
test -f "$COMPILER/frontend/build.sbt" || { echo "Missing native compiler: $COMPILER" >&2; exit 1; }

# --class is local-only: this invocation cannot publish a GCS release.
python3 "$WORKFLOWS/scripts/native-dev.py" \
  --class "$CLASS" \
  --only "$WORKFLOW" \
  --compiler "$COMPILER" \
  --output "$GENERATED"

python3 - "$OUT" "$GENERATED" "$SOURCE" "$COMPILER" "$WORKFLOW" "$CLASS" "$HERE" <<'PY'
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

out, generated, source, compiler, workflow, class_name, here = sys.argv[1:]
out, generated, source, compiler, here = map(
    Path, (out, generated, source, compiler, here))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def revision(repo):
    return subprocess.check_output(
        ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()

report = json.loads((generated / "build-report.json").read_text())
if workflow not in report:
    raise SystemExit(f"Native build did not produce requested workflow: {workflow}")
built = report[workflow]
binary = Path(built["binary"]).resolve(strict=True)
if sha(binary) != built["sha256"]:
    raise SystemExit("Native binary changed since ABI/static-musl validation")

shutil.copy2(binary, out / "emergency-drill")
shutil.copy2(here / "native_driver.py", out / "native_driver.py")
(out / "artifact.json").write_text(json.dumps({
    "platform": "awfl-native-rust",
    "command": ["{python}", "{artifact_dir}/native_driver.py",
                "{artifact_dir}/emergency-drill"],
}, indent=2) + "\n")
metadata = {
    "class": class_name,
    "workflow": workflow,
    "source_sha256": sha(source),
    "compiler_commit": revision(compiler),
    "workflows_commit": revision(here.parents[3]),
    "binary_sha256": sha(out / "emergency-drill"),
    "driver_sha256": sha(out / "native_driver.py"),
    "compiler_manifest_sha256": sha(Path(built["manifest"])),
    "native_dev_build": True,
    "publish": False,
}
(out / "build_metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
print("Frozen native artifact:", out / "artifact.json")
print(json.dumps(metadata, indent=2))
PY
