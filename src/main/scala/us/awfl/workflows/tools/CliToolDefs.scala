package us.awfl.workflows.tools

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import us.awfl.workflows.helpers.ToolDefs.*

/** Agent-facing shell and filesystem capabilities.
  *
  * All operations use the same tools-CliTools callback workflow. The CLI
  * consumer implements the semantics, including atomic patching, optimistic
  * hashes, bounded reads, search and process output cursors. These definitions
  * only describe arguments; they never reimplement filesystem operations.
  */
object CliToolDefs {
  private case class Spec(
    name: String,
    description: String,
    fields: Map[String, ToolDefValue],
    required: List[String] = Nil
  ) {
    def asTool(workflowName: Value[String]): ToolWithWorkflow =
      ToolWithWorkflow(
        function = ToolDefFunc(
          str(name), description,
          ToolDefObj(fields, ListValue(required.cel))
        ).toLlm,
        workflowName = workflowName
      )
  }

  private val string = ToolDefStr
  private val number = ToolDefNum
  private val boolean = ToolDefBool

  private val specs = List(
    Spec("READ_FILE", "Read bounded file text or lines with optional SHA-256 and metadata.",
      Map("filepath" -> string, "offset" -> number, "max_bytes" -> number,
        "start_line" -> number, "end_line" -> number,
        "include_line_numbers" -> boolean, "with_metadata" -> boolean,
        "expected_hash" -> string, "encoding" -> ToolDefEnum(ListValue(List("utf-8", "base64").cel))),
      List("filepath")),
    Spec("UPDATE_FILE", "Atomically write a file, optionally with a precondition hash or create-only guard.",
      Map("filepath" -> string, "content" -> string, "create_only" -> boolean,
        "expected_hash" -> string, "create_parents" -> boolean,
        "encoding" -> ToolDefEnum(ListValue(List("utf-8", "base64").cel)),
        "permissions" -> string), List("filepath", "content")),
    Spec("APPLY_PATCH", "Atomically apply a multi-file *** Begin Patch diff (add/update/delete/move); contexts must match.",
      Map("patch" -> string, "cwd" -> string, "atomic" -> boolean,
        "expected_hashes" -> ToolDefObj(Map.empty, ListValue.empty)), List("patch")),
    Spec("SEARCH_FILES", "Search file contents or names with bounded results, excerpts and pagination.",
      Map("pattern" -> string, "root" -> string, "mode" -> ToolDefEnum(ListValue(List("content", "filename").cel)),
        "glob" -> string, "regex" -> boolean, "case_sensitive" -> boolean,
        "context_lines" -> number, "include_hidden" -> boolean,
        "respect_gitignore" -> boolean, "max_results" -> number,
        "offset" -> number), List("pattern")),
    Spec("LIST_DIRECTORY", "List workspace entries and metadata with optional recursion and bounds.",
      Map("path" -> string, "recursive" -> boolean, "max_depth" -> number,
        "include_hidden" -> boolean, "max_entries" -> number)),
    Spec("RUN_COMMAND", "Execute a shell command. For a durable async process use yield_time_ms: 0; poll COMMAND_OUTPUT.",
      Map("command" -> string, "cwd" -> string, "timeout_ms" -> number,
        "yield_time_ms" -> number, "max_output_bytes" -> number,
        "interactive" -> boolean, "pty" -> boolean,
        "session_id" -> string), List("command")),
    Spec("COMMAND_OUTPUT", "Read a running command's output chunks using its execution_id and cursor.",
      Map("execution_id" -> string, "cursor" -> number, "wait_ms" -> number,
        "max_bytes" -> number), List("execution_id")),
    Spec("COMMAND_INPUT", "Send input or EOF to a command started with interactive or pty mode.",
      Map("execution_id" -> string, "input" -> string, "input_base64" -> string,
        "eof" -> boolean, "signal" -> ToolDefEnum(ListValue(List("SIGINT", "SIGTERM").cel)),
        "rows" -> number, "cols" -> number), List("execution_id")),
    Spec("COMMAND_CANCEL", "Cancel a running command process group without stopping the shared consumer.",
      Map("execution_id" -> string), List("execution_id"))
  )

  val toolNames: List[String] = specs.map(_.name)

  def buildTools(workflowName: Value[String]) =
    buildList("buildCliTools", specs.map(_.asTool(workflowName)))
}
