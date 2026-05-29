#!/usr/bin/env sh
# shellcheck shell=sh
# Portable POSIX sh version (works in Debian/Ubuntu dash and macOS)
set -eu
# Enable pipefail if the shell supports it (bash/zsh); harmless no-op elsewhere
if (set -o pipefail) 2>/dev/null; then :; fi

# Generate .awfl/config.json commands from a JSON array of pytest nodeids
# Input:
#   - ENV FAIL_TO_PASS: JSON array string of nodeids (e.g., ["pkg/test_mod.py::test_x", ...])
#   - or pass via --fail-to-pass-json '...'
# Options:
#   --fail-to-pass-json  JSON array string of nodeids
#   --pytest-bin         Path to pytest executable (default: /opt/miniconda3/envs/testbed/bin/pytest)
#   --config             Path to AWFL config (default: .awfl/config.json)
#   -h|--help            Show help

usage() {
  cat <<'USAGE'
Usage: files/scripts/evals/gen_awfl_failed_tests.sh [options]

Generate .awfl/config.json commands from a JSON array of pytest nodeids.

Inputs (one required):
  ENV FAIL_TO_PASS           JSON array string of nodeids
  --fail-to-pass-json '[]'   Same as above but via flag

Options:
  --pytest-bin PATH          Pytest executable (default: /opt/miniconda3/envs/testbed/bin/pytest)
  --config PATH              Config path (default: .awfl/config.json)
  -h, --help                 Show this help and exit

Notes:
  - Preserves existing "files" list in .awfl/config.json if present; otherwise defaults to ["AGENT.md"].
  - Overwrites the "commands" array with one command per nodeid.
  - Pretty-prints the resulting JSON.
USAGE
}

PYTEST_BIN="/opt/miniconda3/envs/testbed/bin/pytest"
CONFIG=".awfl/config.json"
FAIL_TO_PASS_JSON=${FAIL_TO_PASS:-}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --fail-to-pass-json)
      if [ "$#" -lt 2 ]; then echo "--fail-to-pass-json requires an argument" >&2; exit 2; fi
      FAIL_TO_PASS_JSON=$2; shift 2 ;;
    --pytest-bin)
      if [ "$#" -lt 2 ]; then echo "--pytest-bin requires a path" >&2; exit 2; fi
      PYTEST_BIN=$2; shift 2 ;;
    --config)
      if [ "$#" -lt 2 ]; then echo "--config requires a path" >&2; exit 2; fi
      CONFIG=$2; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2 ;;
  esac
done

# Dependencies
if ! command -v jq >/dev/null 2>&1; then
  echo "Error: jq is required but not found in PATH" >&2
  exit 127
fi

# Input validation
if [ -z "$FAIL_TO_PASS_JSON" ]; then
  echo "Error: Provide FAIL_TO_PASS as an env var or via --fail-to-pass-json" >&2
  usage
  exit 2
fi

if ! printf '%s' "$FAIL_TO_PASS_JSON" | jq -e 'type=="array" and all(.[]; type=="string")' >/dev/null; then
  echo "Error: FAIL_TO_PASS is not a JSON array of strings" >&2
  exit 2
fi

# Ensure config dir exists
mkdir -p "$(dirname "$CONFIG")"

# Preserve existing files list or default
if [ -f "$CONFIG" ]; then
  FILES_JSON=$(jq -c '(.files? // ["AGENT.md"])' "$CONFIG")
else
  FILES_JSON='["AGENT.md"]'
fi

# Build commands array from nodeids
COMMANDS_JSON=$(printf '%s' "$FAIL_TO_PASS_JSON" | jq -c --arg cmd "$PYTEST_BIN" 'map($cmd + " " + .)')
COUNT=$(printf '%s' "$COMMANDS_JSON" | jq 'length')

# Write pretty-printed config (portable mktemp)
TMPDIR=${TMPDIR:-/tmp}
TMP=$(mktemp "${TMPDIR%/}/awfl.XXXXXX" 2>/dev/null || mktemp -t awfl)
jq -n --argjson files "$FILES_JSON" --argjson commands "$COMMANDS_JSON" '{files:$files, commands:$commands}' > "$TMP"
mv "$TMP" "$CONFIG"

# Summary
printf '%s
' "Updated $CONFIG"
# shellcheck disable=SC2016
printf '%s
' "- Preserved files:" $(printf '%s' "$FILES_JSON" | jq -r '.[]' | sed 's/^/  - /')
printf '%s
' "- Commands count: $COUNT"
printf '%s
' "- Pytest bin: $PYTEST_BIN"
