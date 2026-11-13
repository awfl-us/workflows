#!/usr/bin/env python3
"""Compile and optionally publish an AWFL native dependency closure.

Example:
  python3 scripts/native-dev.py codebase-ProjectManager --recursive --publish

Default compiles/builds without publishing; --publish activates verified
artifacts through the existing IAM-controlled GCS release workflow.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRIPLE = "x86_64-unknown-linux-musl"
VALID_OPS = {
    "http.get", "http.post", "http.patch", "http.delete", "log.write",
    "native.parallel", "workflow.run", "storage.object_get",
    "callback.create", "callback.await",
}
STATIC_REF = re.compile(
    r'\("workflow_id"\.to_owned\(\),\s*AwflValue::String\("([^"]+)"\.to_owned\(\)')
ANY_REF = re.compile(r'\("workflow_id"\.to_owned\(\),\s*')
NAME = re.compile(r"[A-Za-z][A-Za-z0-9_-]{0,99}\Z")


def log(message):
    print("[native-dev] " + message, flush=True)


def run(cmd, **kwargs):
    log("Running: " + " ".join(map(str, cmd)))
    subprocess.run(list(map(str, cmd)), check=True, **kwargs)


def modules_for(workflow, recursive):
    targets = json.loads((ROOT / "scripts/native-dev-targets.json").read_text())
    if workflow not in targets:
        raise ValueError("No registered native target: " + workflow)
    spec = targets[workflow]
    modules = [spec["root_class"]] + (spec["dependencies"] if recursive else [])
    if len(modules) != len(set(modules)) or not all(
        re.fullmatch(r"(?:[A-Za-z_]\w*\.)+[A-Za-z_]\w*", mod)
        for mod in modules
    ):
        raise ValueError("Invalid registered native Scala module")
    return spec, modules


def generated_crates(output, workflow, recursive, dynamic):
    crates = {}
    for mf in sorted(output.glob("*/manifest.json")):
        directory = mf.parent
        manifest = json.loads(mf.read_text())
        name = manifest.get("workflow")
        if name != directory.name or not NAME.fullmatch(str(name)):
            raise ValueError("Malformed generated name: " + str(directory))
        src = directory / "src/main.rs"
        if (not src.is_file() or not (directory / "Cargo.toml").is_file() or
            hashlib.sha256(src.read_bytes()).hexdigest() != manifest.get("source_sha256") or
            manifest.get("runtime_abi") != 3):
            raise ValueError("Malformed generated native crate: " + str(directory))
        ops = {effect["operation"] for effect in manifest["effects"]}
        if ops - VALID_OPS:
            raise ValueError(f"Unsupported effects in {name}: {sorted(ops-VALID_OPS)}")
        if (manifest.get("parallel_version", 0) == 1) != ("native.parallel" in ops):
            raise ValueError("Parallel ABI mismatch: " + name)
        crates[name] = (directory, manifest)
    if workflow not in crates:
        raise ValueError("Native root missing: " + workflow)
    if recursive:
        unknown = []
        dynamic_refs = 0
        for name, (directory, _) in crates.items():
            rust = (directory / "src/main.rs").read_text()
            static = STATIC_REF.findall(rust)
            dynamic_refs += max(0, len(ANY_REF.findall(rust)) - len(static))
            for ref in static:
                # Scala code embeds the deployment suffix placeholder.
                child = ref.replace("$" + "{WORKFLOW_ENV}", "")
                if child not in crates:
                    unknown.append((name, child))
        if unknown:
            raise ValueError("Missing statically referenced children: " + str(unknown))
        missing_dynamic = sorted(set(dynamic) - set(crates))
        if missing_dynamic:
            raise ValueError("Missing registered dynamic targets: " + str(missing_dynamic))
        log(f"Dependency closure: {len(crates)} native workflows; "
            f"{dynamic_refs} dynamic call sites (runtime-selected names require registration)")
    return crates


def make_workspace(output, crates):
    # The generated Cargo.toml includes a standalone [workspace] declaration.
    # Remove it in the disposable output directory and compile every member
    # together, sharing dependencies and only one Cargo invocation.
    for directory, _ in crates.values():
        manifest = directory / "Cargo.toml"
        text = manifest.read_text().replace("\n[workspace]\n", "\n")
        manifest.write_text(text)
    content = "[workspace]\nmembers = [\n"
    content += "".join("  " + json.dumps(name) + ",\n" for name in sorted(crates))
    content += "]\nresolver = \"2\"\n"
    workspace = output / "Cargo.toml"
    workspace.write_text(content)
    return workspace


def snapshot_sources(output):
    """Keep Cargo fingerprints on identical regenerated Rust sources."""
    if not output.exists():
        return {}
    files = list(output.glob("*/src/main.rs")) + list(output.glob("*/Cargo.toml"))
    return {
        p.relative_to(output): (
            hashlib.sha256(p.read_bytes()).digest(),
            p.stat().st_atime_ns, p.stat().st_mtime_ns)
        for p in files if p.is_file()
    }


def restore_sources(output, snapshot):
    count = 0
    for rel, (digest, atime, mtime) in snapshot.items():
        p = output / rel
        if p.is_file() and hashlib.sha256(p.read_bytes()).digest() == digest:
            os.utime(p, ns=(atime, mtime))
            count += 1
    log(f"Preserved {count} unchanged Cargo source/manifest fingerprints")


def source_fingerprint(compiler):
    # Invalidates the cached JVM classpath on changes to either Scala module,
    # dependency build definition, or source. No mtime-only cache guesses.
    trees = [ROOT, compiler / "frontend"]
    digest = hashlib.sha256()
    for tree in trees:
        sources = sorted((tree / "src").rglob("*.scala"))
        build = [tree / "build.sbt", *sorted((tree / "project").glob("*.sbt")),
                 tree / "project/build.properties"]
        for file in sources + build:
            if file.is_file():
                digest.update(str(file.relative_to(tree)).encode())
                digest.update(file.read_bytes())
    return digest.hexdigest()


def compile_all(args, output, modules):
    output.mkdir(parents=True, exist_ok=True)
    snapshot = snapshot_sources(output)
    classes_file = output / "classes.txt"
    classes_file.write_text("\n".join(modules) + "\n")
    cache_file = args.compiler / "target/native-dev-classpath.json"
    fingerprint = source_fingerprint(args.compiler)
    cache = {}
    if cache_file.is_file():
        try: cache = json.loads(cache_file.read_text())
        except (ValueError, OSError): pass
    paths = cache.get("paths", [])
    valid = cache.get("fingerprint") == fingerprint and len(paths) >= 3 and (
        all(Path(path).exists() for path in paths))
    if valid:
        log("Warm JVM classpath: skip sbt startup and unchanged Scala compilation")
        run(["java", "-cp", os.pathsep.join(paths),
             "us.awfl.compiler.rust.NativeBatchMain",
             classes_file, output, args.compiler / "runtime"])
    else:
        log("Scala inputs changed or classpath cache missing; rebuilding")
        run(["sbt", "-batch", "compile"], cwd=ROOT)
        classes = ROOT / "target/scala-3.4.2/classes"
        if not classes.is_dir():
            raise ValueError("Compiled workflow classes missing: " + str(classes))
        injection = 'set Test / unmanagedClasspath += Attributed.blank(file("' + str(classes) + '"))'
        run(["sbt", "-batch", injection,
             "Test / runMain us.awfl.compiler.rust.NativeBatchMain " +
             " ".join(map(str, [classes_file, output, args.compiler / "runtime"]))],
            cwd=args.compiler / "frontend")
        cp_command = ["sbt", "-batch", injection, "show Test / fullClasspath"]
        log("Capturing compiled JVM dependency classpath for fast next iteration")
        result = subprocess.run(cp_command, cwd=args.compiler / "frontend",
                                check=True, capture_output=True, text=True)
        matched = re.findall(r"Attributed\((/[^)]+)\)", result.stdout)
        if len(matched) < 3 or not all(Path(path).exists() for path in matched):
            raise ValueError("Cannot validate native batch JVM classpath")
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        cache_file.write_text(json.dumps({
            "fingerprint": fingerprint, "paths": matched,
        }))
        log(f"Cached JVM classpath with {len(matched)} entries")
    index = output / ".awfl-native-workflows"
    if not index.is_file():
        raise ValueError("Native batch did not produce an artifact index")
    names = set(index.read_text().splitlines())
    if not names or not all(NAME.fullmatch(name) for name in names):
        raise ValueError("Invalid native compiler artifact index")
    for path in output.iterdir():
        if path.is_dir() and (path / "manifest.json").is_file() and path.name not in names:
            shutil.rmtree(path)
    args.previous_sources = snapshot


def build_all(args, output, crates):
    workspace = make_workspace(output, crates)
    restore_sources(output, getattr(args, "previous_sources", {}))
    target = args.compiler / "target/native-dev-shared-cargo"
    env = dict(os.environ, CARGO_TARGET_DIR=str(target), CARGO_INCREMENTAL="0")
    run(["cargo", "build", "--release", "--workspace", "--target", TRIPLE,
         "--manifest-path", workspace], env=env)
    built = {}
    for name, (directory, manifest) in sorted(crates.items()):
        with (directory / "Cargo.toml").open("rb") as f:
            package = tomllib.load(f)["package"]["name"]
        binary = target / TRIPLE / "release" / package
        run([sys.executable, ROOT / "scripts/verify-native-elf.py", binary])
        built[name] = {
            "binary": str(binary),
            "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
            "manifest": str(directory / "manifest.json"),
            "operations": sorted({fx["operation"] for fx in manifest["effects"]}),
        }
    (output / "build-report.json").write_text(json.dumps(built, indent=2, sort_keys=True))
    log(f"Verified {len(built)} static musl binaries (one shared Cargo target)")
    return built


def expected_release(name, source, build):
    """Exactly the GCS alias schema written by publish-native.sh."""
    release = {
        "schemaVersion": 1,
        "workflowName": name,
        "artifactSha256": build["sha256"],
        "runtimeAbi": 3,
        "protocolVersion": 2,
        "allowedOperations": build["operations"],
        "sourceWorkflow": source["workflow"],
        "sourceSha256": source["source_sha256"],
    }
    if source.get("trusted_globals_version", 0) == 1:
        release["trustedGlobalsVersion"] = 1
    if source.get("parallel_version", 0) == 1:
        release["parallelVersion"] = 1
    return release


def changed_releases(crates, built, remote):
    """Compare against IAM-published active mappings, not local receipts."""
    return [
        name for name in sorted(built)
        if remote.get(name) != expected_release(name, crates[name][1], built[name])
    ]


def fetch_active_releases(bucket):
    # One CLI call downloads all active metadata. Individually invoking gcloud
    # 26 times was the remaining warm-publish bottleneck (minutes versus seconds).
    # Failure is a cache miss: per-release publication still validates GCS.
    with tempfile.TemporaryDirectory(prefix="awfl-native-active-") as dirname:
        command = [
            "gcloud", "storage", "cp",
            f"gs://{bucket}/native-workflows/active/*.json",
            dirname, "--quiet",
        ]
        log("Fetching remote active release manifests in one GCS call")
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            log("Remote active-release listing unavailable; verifying each release individually")
            return {}
        active = {}
        for path in Path(dirname).glob("*.json"):
            try:
                active[path.stem] = json.loads(path.read_text())
            except (ValueError, OSError):
                # Malformed remote manifest must never produce a false cache hit.
                continue
        log(f"Read {len(active)} remote active releases")
        return active


def publish_all(args, crates, built):
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,220}", args.bucket):
        raise ValueError("Invalid native artifacts bucket")
    remote = {} if args.force_publish else fetch_active_releases(args.bucket)
    changed = changed_releases(crates, built, remote)
    log(f"Releases changed={len(changed)} unchanged={len(built)-len(changed)}")
    for index, name in enumerate(changed, start=1):
        meta = built[name]
        log(f"Publish {index}/{len(changed)}: {name}")
        env = dict(os.environ, AWFL_NATIVE_PREBUILT_BINARY=meta["binary"])
        run(["bash", ROOT / "scripts/publish-native.sh",
             name, crates[name][0], args.bucket], env=env)
    log(f"Activated {len(changed)} changed releases; "
        f"{len(built)-len(changed)} already current")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workflow", help="Root deployed name")
    parser.add_argument("--recursive", action="store_true",
                        help="Build registered dependency closure")
    parser.add_argument("--publish", action="store_true",
                        help="Activate verified artifacts with GCP publisher IAM")
    parser.add_argument("--compiler", type=Path, default=ROOT.parent / "compiler-rust")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--bucket", default=os.environ.get(
        "AWFL_NATIVE_ARTIFACT_BUCKET", "awfl-us-native-workflows"))
    parser.add_argument("--force-publish", action="store_true",
                        help="Check/re-publish all releases, bypassing the batched remote index")
    parser.add_argument("--skip-compile", action="store_true",
                        help="Reuse existing generated Rust source")
    parser.add_argument("--check", action="store_true",
                        help="Only validate generated crate closure; no Cargo build")
    args = parser.parse_args()
    if args.publish and (not args.recursive or args.check):
        parser.error("--publish requires --recursive and cannot use --check")
    args.compiler = args.compiler.resolve()
    output = (args.output or (args.compiler / "target/native-dev-generated" /
                              args.workflow)).resolve()
    spec, modules = modules_for(args.workflow, args.recursive)
    started = time.monotonic()
    if not args.skip_compile:
        compile_all(args, output, modules)
    crates = generated_crates(output, args.workflow, args.recursive,
                              spec.get("dynamic_targets", []))
    if not args.check:
        built = build_all(args, output, crates)
        if args.publish:
            publish_all(args, crates, built)
    log(f"AWFL_NATIVE_DEV_PASS root={args.workflow} workflows={len(crates)} "
        f"publish={str(args.publish).lower()} elapsed={time.monotonic()-started:.1f}s")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        log("FAILED: " + str(error))
        sys.exit(1)
