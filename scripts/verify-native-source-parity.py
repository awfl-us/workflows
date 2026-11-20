#!/usr/bin/env python3
"""Compare compiled native Rust workflow sources across a refactor.

Both arguments are local `native-dev.py --output` directories. This checks
generated native source equivalence, not side-effect or runtime correctness.
Use the memory-mode integration gates for the latter.
"""

import argparse
import hashlib
from pathlib import Path


def sources(root: Path) -> dict[str, bytes]:
    if not root.is_dir():
        raise ValueError(f"Missing native artifact directory: {root}")
    files = {p.parent.parent.name: p.read_bytes()
             for p in root.glob("*/src/main.rs")}
    if not files:
        raise ValueError(f"No compiled Rust workflow sources in {root}")
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    before, after = sources(args.baseline), sources(args.candidate)
    missing, added = sorted(before.keys() - after.keys()), sorted(after.keys() - before.keys())
    modified = sorted(name for name in before.keys() & after.keys()
                      if before[name] != after[name])
    if missing or added or modified:
        print(f"AWFL_NATIVE_SOURCE_PARITY_FAIL missing={missing} added={added} modified={modified}")
        for name in modified:
            print(name, hashlib.sha256(before[name]).hexdigest(),
                  hashlib.sha256(after[name]).hexdigest())
        raise SystemExit(1)
    print(f"AWFL_NATIVE_SOURCE_PARITY_PASS workflows={len(before)} identical={len(before)}")


if __name__ == "__main__":
    main()
