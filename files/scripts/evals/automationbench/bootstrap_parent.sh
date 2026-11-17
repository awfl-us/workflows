#!/usr/bin/env bash
# Initialize the parent-side AutomationBench evaluator in a fresh Scala cloud consumer.
# The task agent never receives this checkout, its simulator state, or its grader.
set -euo pipefail

# The parent performs native compilation and offline grading after the
# builder returns. Fail before starting the paid builder if its image lacks
# the Scala and Rust toolchains.
for tool in java sbt cargo rustc; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'AUTOMATIONBENCH_PARENT_MISSING_TOOL=%s; start a Scala consumer\n' "$tool" >&2
    exit 2
  fi
done

ROOT="/workspace/automationbench-upstream"
PIN="4a8e1061254004d9dac807054eed33fad7d1ff14"
UV="/home/awfl/.local/bin/uv"
if [[ ! -d "$ROOT/.git" ]]; then
  git clone --quiet https://github.com/zapier/AutomationBench.git "$ROOT"
fi
if [[ "$(git -C "$ROOT" rev-parse HEAD)" != "$PIN" ]]; then
  git -C "$ROOT" fetch --quiet origin "$PIN"
  git -C "$ROOT" checkout --quiet --detach "$PIN"
fi
if [[ ! -x "$UV" ]]; then
  python3 -m pip install --user --disable-pip-version-check --quiet 'uv==0.13.8'
fi
cd "$ROOT"
"$UV" sync --locked --quiet
"$UV" run --locked python -c 'import automationbench, datasets'
printf 'AUTOMATIONBENCH_PARENT_READY pin=%s\n' "$PIN"
