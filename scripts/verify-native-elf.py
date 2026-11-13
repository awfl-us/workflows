#!/usr/bin/env python3
"""Validate the static Linux musl executable required by Alpine Jobs."""
import pathlib
import struct
import sys

def verify(path):
    data = pathlib.Path(path).read_bytes()
    if not (0 < len(data) <= 64 * 1024 * 1024):
        raise ValueError("native executable must be 1..64 MiB")
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected little-endian ELF64 executable")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 62:
        raise ValueError("expected x86-64 executable")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize = struct.unpack_from("<H", data, 54)[0]
    phcount = struct.unpack_from("<H", data, 56)[0]
    if not phcount or phsize < 56 or phoff + phcount * phsize > len(data):
        raise ValueError("invalid ELF program headers")
    for i in range(phcount):
        if struct.unpack_from("<I", data, phoff + i * phsize)[0] == 3:
            raise ValueError("dynamic ELF interpreter found; static musl required")
    return len(data)

if __name__ == "__main__":
    try:
        print("Verified static Linux x86-64 ELF (%d bytes)" % verify(sys.argv[1]))
    except (ValueError, OSError, IndexError) as exc:
        sys.exit(str(exc))
