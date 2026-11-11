#!/usr/bin/env bash
# Compile an AWFL-generated Rust crate with the same musl libc used by Jobs.
# Usage: build-native-static.sh <crate-dir> <output-dir>
set -euo pipefail
if [[ "$#" != 2 ]]; then
  echo "Usage: $0 <crate-dir> <output-dir>" >&2
  exit 2
fi
for tool in python3 docker; do
  command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 2; }
done

crate=$(realpath "$1")
test -f "$crate/Cargo.toml" || { echo "Missing generated Cargo.toml" >&2; exit 2; }
mkdir -p "$2"
output=$(realpath "$2")

# The generated Cargo.toml records the relative path to the trusted
# awfl-native-runtime. Mount the smallest shared source tree, read-only,
# so both the crate and runtime resolve unmodified inside Docker.
source_root=$(python3 - "$crate" <<'PY'
import os
import pathlib
import sys
import tomllib
crate = pathlib.Path(sys.argv[1]).resolve(strict=True)
with (crate / "Cargo.toml").open("rb") as stream:
    config = tomllib.load(stream)
runtime = pathlib.Path(config["dependencies"]["awfl-native-runtime"]["path"])
runtime = (crate / runtime).resolve(strict=True)
root = os.path.commonpath([str(crate), str(runtime)])
if root == "/" or not (runtime / "Cargo.toml").is_file():
    raise SystemExit("Untrusted or unrelated native runtime path")
print(root)
PY
)

docker run --rm --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  -e HOME=/native-output/home \
  -e CARGO_HOME=/native-output/cargo-cache \
  --mount "type=bind,source=$source_root,target=$source_root,readonly" \
  --mount "type=bind,source=$output,target=/native-output" \
  --workdir "$crate" \
  rust:1.99.1-alpine \
  sh -c 'cargo build --locked --release --target x86_64-unknown-linux-musl --target-dir /native-output/target'

package=$(python3 - "$crate/Cargo.toml" <<'PY'
import sys
import tomllib
with open(sys.argv[1], "rb") as stream:
    print(tomllib.load(stream)["package"]["name"])
PY
)
[[ "$package" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid Cargo package name" >&2; exit 2; }
binary="$output/target/x86_64-unknown-linux-musl/release/$package"
test -f "$binary" || { echo "Native executable missing" >&2; exit 1; }

# Verify ELF64 x86-64 and the absence of PT_INTERP: the downloaded executable
# must not require a dynamic loader absent from the Jobs Alpine image.
python3 - "$binary" <<'PY'
import pathlib
import struct
import sys
b = pathlib.Path(sys.argv[1]).read_bytes()
if b[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", b, 18)[0] != 62:
    raise SystemExit("Expected x86-64 Linux ELF executable")
phoff = struct.unpack_from("<Q", b, 32)[0]
entry_size = struct.unpack_from("<H", b, 54)[0]
count = struct.unpack_from("<H", b, 56)[0]
if not count or phoff + count * entry_size > len(b):
    raise SystemExit("Malformed ELF program headers")
for i in range(count):
    if struct.unpack_from("<I", b, phoff + i * entry_size)[0] == 3:
        raise SystemExit("Dynamic ELF interpreter found; expected static musl")
print("Verified static Linux x86-64 ELF")
PY
echo "$binary"
