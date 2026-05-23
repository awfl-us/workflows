#!/usr/bin/env bash
set -euo pipefail

# 1) Ensure default .awfl/config.json (pretty-printed, no session-dependent commands here)
if [[ ! -f .awfl/config.json ]]; then
  mkdir -p .awfl
  printf '%s\n' '{' '  "files": [' '    "AGENT.md"' '  ],' '  "commands": []' '}' > .awfl/config.json
fi

# 2) Emit files listed in .awfl/config.json (robust to missing/empty key)
jq -r '(.files? // [])[]' .awfl/config.json | while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  echo
  echo "[Preload file: $f]"
  if [[ -f "$f" ]]; then
    cat "$f"
  else
    echo "Missing $f"
  fi
done

# 3) Run generic (non-session) commands from .awfl/config.json (robust to missing/empty key)
jq -r '(.commands? // [])[]' .awfl/config.json | while IFS= read -r c; do
  [[ -n "$c" ]] || continue
  echo
  echo "[Preload command: $c]"
  bash -lc "$c"
done
