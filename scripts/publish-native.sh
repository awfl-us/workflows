#!/usr/bin/env bash
# IAM-authorized native release publication. No application-user deploy API.
# Usage: publish-native.sh <deployed-workflow-name> <crate-dir> <GCS-bucket>
# The crate MUST have been generated from the Scala AWFL DSL (ABI 3).
set -euo pipefail

if [[ "$#" != 3 ]]; then
  echo "Usage: $0 <workflow-name> <crate-dir> <GCS-bucket>" >&2
  exit 2
fi
name="$1"
crate="$2"
bucket="$3"
[[ "$name" =~ ^[A-Za-z][A-Za-z0-9_-]{0,99}$ ]] || {
  echo "Invalid workflow name" >&2
  exit 2
}
[[ "$bucket" =~ ^[a-z0-9][a-z0-9._-]{2,220}$ ]] || {
  echo "Invalid GCS bucket name" >&2
  exit 2
}
[[ -f "$crate/Cargo.toml" && -f "$crate/manifest.json" &&
   -f "$crate/src/main.rs" ]] || {
  echo "Missing generated Scala-to-Rust crate files" >&2
  exit 2
}
for required in jq sha256sum gcloud python3 docker; do
  command -v "$required" >/dev/null || {
    echo "Missing required command: $required" >&2
    exit 2
  }
done

# Prevent accidental publication of the old ABI 1/2, which does not use
# the version-2 suspend/resume protocol accepted by the native launcher.
jq -e '.runtime_abi == 3 and (.workflow | type == "string") and
  (.source_sha256 | test("^[a-f0-9]{64}$")) and
  (.effects | type == "array")' "$crate/manifest.json" >/dev/null || {
  echo "Expected generated native ABI 3 compiler manifest" >&2
  exit 2
}
source_hash=$(sha256sum "$crate/src/main.rs" | cut -d' ' -f1)
manifest_hash=$(jq -r '.source_sha256' "$crate/manifest.json")
[[ "$source_hash" == "$manifest_hash" ]] || {
  echo "Compiler source hash mismatch" >&2
  exit 2
}
operations=$(jq -c '[.effects[].operation] | unique' "$crate/manifest.json")
jq -e 'all(.[]; . == "http.get" or . == "http.post" or
  . == "http.patch" or . == "http.delete")' <<< "$operations" >/dev/null || {
  echo "Unapproved native effect operation" >&2
  exit 2
}

# Build inside the same musl family as the Jobs Alpine image. The common
# static-builder checks ELF architecture and forbids a dynamic interpreter.
target_dir=$(mktemp -d)
trap 'rm -rf "$target_dir"' EXIT
bash "$(dirname "$0")/build-native-static.sh" "$crate" "$target_dir"
package=$(python3 - "$crate/Cargo.toml" <<'PY'
import sys
import tomllib
with open(sys.argv[1], "rb") as stream:
    print(tomllib.load(stream)["package"]["name"])
PY
)
binary="$target_dir/target/x86_64-unknown-linux-musl/release/$package"
digest=$(sha256sum "$binary" | cut -d' ' -f1)
source_workflow=$(jq -r '.workflow' "$crate/manifest.json")
prefix="native-workflows"
artifact_uri="gs://$bucket/$prefix/artifacts/$digest.bin"
active_uri="gs://$bucket/$prefix/active/$name.json"

jq -nc --arg name "$name" --arg digest "$digest" \
  --arg source "$source_workflow" \
  --arg sourceSha "$source_hash" \
  --argjson operations "$operations" \
  '{schemaVersion:1,workflowName:$name,artifactSha256:$digest,
    runtimeAbi:3,protocolVersion:2,allowedOperations:$operations,
    sourceWorkflow:$source,sourceSha256:$sourceSha}' > "$target_dir/release.json"

# Publish by content digest FIRST, then switch the active alias last.
# Existing running executions retain their pinned SHA and are unaffected.
gcloud storage cp "$binary" "$artifact_uri"
gcloud storage cp "$target_dir/release.json" "$active_uri"
echo "Published native workflow $name: $digest"
echo "Active mapping: $active_uri"
