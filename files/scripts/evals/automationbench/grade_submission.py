#!/usr/bin/env python3
"""Build & independently grade one agent-authored AWFL Scala workflow.

The builder is in a different project/consumer and is given only the public
task description. Grading happens *after* the builder's source is frozen.
This experimental local grader is NOT a security sandbox for hostile code:
compiling a Scala workflow can execute authored initialization code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[3]
COMPILER = ROOT.parent / "compiler-rust"
UPSTREAM = ROOT.parent / "automationbench-upstream"
RUNNER = HERE / "runner.py"
BUILDER = HERE / "build_native.sh"
UV = pathlib.Path("/home/awfl/.local/bin/uv")

CLASS = "us.awfl.workflows.evals.automationbench.AgentSubmission"
VARIANT = "evals-automationbench-AgentSubmission-submit"
DEST = "src/main/scala/us/awfl/workflows/evals/automationbench/AgentSubmission.scala"


def run(command: list[str], *, cwd: pathlib.Path | None = None,
        timeout: int = 600, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                          text=True, timeout=timeout, check=True)


def grade(source: pathlib.Path, output: pathlib.Path,
          domain: str, task_id: str, model: str, max_calls: int,
          timeout: int) -> dict:
    if not re.fullmatch(r"[a-z][a-z_]{1,30}", domain):
        raise ValueError("Invalid domain")
    if not re.fullmatch(r"[0-9]{1,8}", task_id):
        raise ValueError("Invalid task ID")
    data = source.read_bytes()
    if not 200 <= len(data) <= 200_000:
        raise ValueError("Agent submission must be a nonempty Scala source <=200KB")
    code = data.decode("utf-8")
    if "object AgentSubmission" not in code or "Some(\"submit\")" not in code:
        raise ValueError("Expected AWFL AgentSubmission with a submit workflow")
    output.mkdir(parents=True, exist_ok=True)
    frozen = output / "agent_submission.scala"
    frozen.write_bytes(data)
    source_sha = hashlib.sha256(data).hexdigest()
    artifact_dir = output / "artifact"
    score_dir = output / "score"
    build_status = {"source_sha256": source_sha, "build_success": False}
    began = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="awfl-agent-build-",
                                     dir=str(ROOT / "eval_runs")) as directory:
        worktree = pathlib.Path(directory) / "workflows"
        # sbt-git/JGit does not support linked Git worktrees (.git files);
        # use a standalone *local* clone with a real .git directory instead.
        # This is still isolated from the canonical checkout and needs no
        # network access or credentials.
        run(["git", "clone", "--local", "--no-hardlinks", "--quiet",
             str(ROOT), str(worktree)], timeout=60)
        try:
            path = worktree / DEST
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            command = [
                str(worktree / "files/scripts/evals/automationbench/build_native.sh"),
                str(artifact_dir), CLASS, VARIANT
            ]
            env = dict(os.environ, AWFL_COMPILER_RUST_ROOT=str(COMPILER))
            try:
                completed = run(command, cwd=worktree, timeout=timeout, env=env)
            except subprocess.CalledProcessError as error:
                # Don't lose actionable compiler errors when the disposable
                # worktree is removed on the way out of this function.
                output.joinpath("failed_build.log").write_text(
                    (error.stdout or "")[-30000:] + "\nSTDERR:\n" +
                    (error.stderr or "")[-20000:])
                raise RuntimeError(
                    "Agent Scala compilation failed: " +
                    (error.stdout or "")[-1800:] + " " +
                    (error.stderr or "")[-1800:]) from error
            build_status.update({
                "build_success": True,
                "build_wall_time_seconds": round(time.monotonic() - began, 3),
                "build_log_tail": completed.stdout[-2000:],
            })
        finally:
            pass  # TemporaryDirectory removes the independent local clone.
    (output / "builder_compile.json").write_text(json.dumps(build_status, indent=2)+"\n")
    command = [str(UV), "run", "--project", str(UPSTREAM), "python", str(RUNNER),
               "evaluate", "--domain", domain, "--task-id", task_id,
               "--artifact", str(artifact_dir / "artifact.json"),
               "--output-dir", str(score_dir), "--platform", "awfl-native-rust",
               "--model", model, "--agent-harness", "awfl-project-manager",
               "--authoring-method", "cloud-agent-scala-dsl",
               "--timeout-seconds", "120", "--max-tool-calls", str(max_calls)]
    scored = run(command, cwd=UPSTREAM, timeout=180)
    result = json.loads(scored.stdout)
    result["source_sha256"] = source_sha
    result["build_wall_time_seconds"] = build_status["build_wall_time_seconds"]
    result["builder_input_tokens"] = None
    result["builder_output_tokens"] = None
    result["builder_cost_usd"] = None
    (output / "score_summary.json").write_text(json.dumps(result, indent=2)+"\n")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--model", default="gpt-6")
    parser.add_argument("--max-calls", type=int, default=30)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    if not 1 <= args.max_calls <= 1000 or not 60 <= args.timeout <= 1800:
        parser.error("Invalid tool-call or compilation timeout budget")
    print(json.dumps(grade(args.source.resolve(strict=True), args.output.resolve(),
                           args.domain, args.task_id, args.model,
                           args.max_calls, args.timeout)))


if __name__ == "__main__":
    main()
