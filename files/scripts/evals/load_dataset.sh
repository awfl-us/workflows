#!/usr/bin/env sh
# shellcheck shell=sh
# Portable POSIX sh version (works on Debian/Ubuntu dash and macOS)
set -eu
# Enable pipefail if supported (bash/zsh); harmless no-op elsewhere
if (set -o pipefail) 2>/dev/null; then :; fi

: "${DATASET:?DATASET is required}"
: "${SPLIT:?SPLIT is required}"
: "${LIMIT:?LIMIT is required}"

DATASET_SAFE="$(printf '%s' "$DATASET" | tr '/: @' '----' | tr -c 'A-Za-z0-9._-' '-')"
DATASET_DIR="evals/datasets/${DATASET_SAFE}/${SPLIT}"

mkdir -p "$DATASET_DIR"

if [ ! -f "$DATASET_DIR/dataset.jsonl" ]; then
  python - "$DATASET" "$SPLIT" "$DATASET_DIR/dataset.jsonl" <<'PY'
import json
import sys
from pathlib import Path

dataset, split, out_path = sys.argv[1], sys.argv[2], Path(sys.argv[3])

try:
    from datasets import load_dataset
except ImportError:
    raise SystemExit("Missing dependency: pip install datasets")


def _clean_patch(obj):
    if isinstance(obj, dict):
        for key in ("patch", "test_patch"):
            if key in obj:
                # Keep key for schema stability but strip the large diff/test content
                obj[key] = ""
        for v in obj.values():
            _clean_patch(v)
    elif isinstance(obj, list):
        for i in obj:
            _clean_patch(i)


ds = load_dataset(dataset, split=split)

out_path.parent.mkdir(parents=True, exist_ok=True)
with out_path.open("w") as f:
    for row in ds:
        row_dict = dict(row)
        _clean_patch(row_dict)
        f.write(json.dumps(row_dict) + "\n")
PY
fi

python - "$DATASET_DIR/dataset.jsonl" "$LIMIT" "$RANDOMIZE" "$SEED" <<'PY'
import json
import random
import sys
from pathlib import Path

path = Path(sys.argv[1])
limit = int(sys.argv[2])
randomize = sys.argv[3].lower() in {"1", "true", "yes", "y"}
seed = int(sys.argv[4])


def _clean_patch(obj):
    if isinstance(obj, dict):
        for key in ("patch", "test_patch"):
            if key in obj:
                obj[key] = ""
        for v in obj.values():
            _clean_patch(v)
    elif isinstance(obj, list):
        for i in obj:
            _clean_patch(i)

rows = []
with path.open() as f:
    for line in f:
        if line.strip():
            rows.append(json.loads(line))

if randomize:
    rng = random.Random(seed)
    rng.shuffle(rows)

for row in rows[:limit]:
    _clean_patch(row)
    print(json.dumps(row))
PY