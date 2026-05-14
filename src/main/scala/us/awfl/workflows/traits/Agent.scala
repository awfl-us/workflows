package us.awfl.workflows.traits

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.dsl.CelOps._
import us.awfl.workflows.EventHandler
import us.awfl.services.Llm.ChatToolResponse
import us.awfl.utils.Env

trait Agent extends us.awfl.core.Workflow with EventHandler with Preloads with Tasks with Cli with Funds {
  override type Result = ChatToolResponse

  private def configPreloadCommand: Cel =
    CelStr("""python3 - <<'PY'
import json
import subprocess
from pathlib import Path

config_path = Path(".agent/config.json")
default_config = {
    "files": ["AGENT.md"],
    "commands": [
        "echo 'Files uploaded/specific to the current session:'",
        "ls -la sessions/""").safe + Env.sessionId.cel + CelStr(""""
    ]
}

if not config_path.exists():
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(json.dumps(default_config, indent=2) + "\n")

try:
    config = json.loads(config_path.read_text())
except Exception as exc:
    print(f"[Agent config preload] Failed to read {config_path}: {exc}")
    raise

print(f"[Agent config preload] {config_path}")

for filename in config.get("files", []):
    print(f"\n[Preload file: {filename}]")
    try:
        print(Path(filename).read_text())
    except Exception as exc:
        print(f"Failed to read {filename}: {exc}")

for command in config.get("commands", []):
    print(f"\n[Preload command: {command}]")
    try:
        result = subprocess.run(command, shell=True, text=True, capture_output=True)
        if result.stdout:
            print(result.stdout, end="")
        if result.stderr:
            print(result.stderr, end="")
        if result.returncode != 0:
            print(f"Command exited with status {result.returncode}")
    except Exception as exc:
        print(f"Failed to run command {command!r}: {exc}")
PY""").safe

  override def preloads = super.preloads ++ List(
    PreloadCommand(configPreloadCommand)
  )

  def apply(name: String, query: Value[String], fund: Value[Double], spent: Value[Double]): Call[RunWorkflowArgs[Input], ChatToolResponse] = {
    execute(workflowName, obj(EventHandler.Input(query, fund, OptValue(spent), env = obj(Env.get.copy(sessionId = str(workflowName))))))
  }

  override def workflows = eventHandler() :: super.workflows
}