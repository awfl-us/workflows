package utils.strider

import dsl.*
import dsl.CelOps.*
import dsl.auto.given
import ista.ChatMessage
import utils.Yoj
import utils.Convo
import utils.Segments
import utils.Locks
import services.Firebase
import services.Firebase.Segment
import services.Firebase.create
import services.Firebase.update
import utils.SegKala

object StriderObj {
  val inputVal = init[StriderInput]("input")
  val input = inputVal.get
  given Convo.SessionId = Convo.SessionId(input.sessionId)

  private val windowSeconds  = Value[Double](CelFunc("default", input.windowSeconds, Segments.DefaultWindowSeconds))
  private val overlapSeconds = Value[Int](CelFunc("default", input.overlapSeconds, Segments.DefaultOverlapSeconds))

  val segments: Step[SegKala, ListValue[SegKala]] =
    Segments.forSession("segmentsForSession", input.sessionId, windowSeconds, overlapSeconds)

  val segment       = segments.resultValue(len(segments.resultValue) - 1)
  val segmentKala   = SegKala(input.sessionId, input.segmentEnd, windowSeconds)
}
