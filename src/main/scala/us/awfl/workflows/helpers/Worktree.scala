package us.awfl.workflows.helpers

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.workflows.helpers.Files
import us.awfl.workflows.tools.CliTools
import us.awfl.utils.Env

object Worktree {
  def init(sessionId: Value[String], repoUrl: Value[String], commit: Value[String]): Step[String, Value[String]] = {
    val initScript = Files.scripts("init_worktree.sh")
    val params =
      ("SESSION_ID='": Cel) + sessionId +
      CelStr("'\nREPO_URL='").safe + repoUrl +
      CelStr("'\nBRANCH='").safe + commit + CelStr("'\n\n").safe
    val runScript = CliTools.runCommand(
      str(params + initScript.resultValue),
      raiseError = true,
      timeoutSeconds = Value(60 * 5)
    )
    Try("initWorktree",
      List[Step[?, ?]](initScript, runScript) -> runScript.resultValue
    )
  }
}