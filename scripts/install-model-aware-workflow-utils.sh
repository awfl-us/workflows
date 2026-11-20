#!/usr/bin/env bash
# Use exact tested workflow-utils sources until the next public release.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
utils_dir="$repo_root/.workflow-utils-model-aware"
version="0.3.6-SNAPSHOT"
ref="28e85072f010d16b93f4a30a85d7f27201c193f3"
if [[ ! -d "$utils_dir/.git" ]]; then
  git clone --quiet https://github.com/awfl-us/workflow-utils.git "$utils_dir"
fi
git -C "$utils_dir" fetch --quiet origin "$ref"
git -C "$utils_dir" checkout --quiet --detach "$ref"
( cd "$utils_dir" && sbt -batch "set ThisBuild / version := \"$version\"" publishLocal )
