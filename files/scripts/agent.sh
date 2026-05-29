#!/usr/bin/env sh
set -eu

CONFIG=".awfl/config.json"

# 1) Ensure default .awfl/config.json
if [ ! -f "$CONFIG" ]; then
  mkdir -p .awfl
  cat > "$CONFIG" <<'JSON'
{
  "files": [
    "AGENT.md"
  ],
  "commands": [
    "pwd"
  ]
}
JSON
fi

# Pick a shell for configured commands.
if command -v bash >/dev/null 2>&1; then
  RUN_SHELL="bash"
else
  RUN_SHELL="sh"
fi

# 2) Emit files listed in config
if command -v jq >/dev/null 2>&1; then
  jq -r '(.files? // [])[]' "$CONFIG" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    printf '\n[Preload file: %s]\n' "$f"
    if [ -f "$f" ]; then
      cat "$f"
    else
      printf 'Missing %s\n' "$f"
    fi
  done

  # 3) Run commands listed in config
  jq -r '(.commands? // [])[]' "$CONFIG" | while IFS= read -r c; do
    [ -n "$c" ] || continue
    printf '\n[Preload command: %s]\n' "$c"
    "$RUN_SHELL" -lc "$c"
  done
else
  # Fallback: no jq, preload AGENT.md only.
  printf '\n[Preload file: AGENT.md]\n'
  if [ -f AGENT.md ]; then
    cat AGENT.md
  else
    printf 'Missing AGENT.md\n'
  fi
fi