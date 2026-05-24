set -euo pipefail

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

ds = load_dataset(dataset, split=split)

out_path.parent.mkdir(parents=True, exist_ok=True)
with out_path.open("w") as f:
    for row in ds:
        f.write(json.dumps(dict(row)) + "\n")
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

rows = []
with path.open() as f:
    for line in f:
        if line.strip():
            rows.append(json.loads(line))

if randomize:
    rng = random.Random(seed)
    rng.shuffle(rows)

for row in rows[:limit]:
    print(json.dumps(row))
PY