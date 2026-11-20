package us.awfl.workflows

import us.awfl.dsl.*
import us.awfl.workflows.traits.Agent

/** Build a real Agent's historical wip-old lifecycle mixin contributions. */
object BackgroundLifecycleSmoke:
  object Probe extends Agent:
    override def prompt: String = "Test lifecycle wiring"
    def background: List[Step[?, ?]] = backgroundStriderSteps

  // Traverse only explicit control-flow owners; this intentionally includes
  // exception paths, so duplicate background work cannot hide there.
  private def nested(step: Step[?, ?]): List[Step[?, ?]] =
    step match
      case b: Block[?, ?] => b.steps
      case t: Try[?, ?] => t.run._1 ++ t.except._1
      case s: Switch[?, ?] => s.cases.flatMap(_._2._1)
      case p: Parallel => p.branches
      case _ => List.empty

  private def allSteps(steps: List[Step[?, ?]]): List[Step[?, ?]] =
    steps.flatMap(s => s :: allSteps(nested(s)))

  def main(args: Array[String]): Unit =
    val steps = Probe.background
    assert(steps.size == 3, s"Expected summary, extract, collapse once: $steps")
    println("BACKGROUND_LIFECYCLE_TYPES=" + steps.map(_.getClass.getSimpleName).mkString(","))
    println("BACKGROUND_LIFECYCLE_NAMES=" + steps.map(_.name).mkString(","))
    val names = steps.map(_.name)
    assert(names == List("runSummaries", "chainBlock", "collapseMessages_try"), names)
    val handler = Probe.eventHandler()
    val gates = allSteps(handler.steps._1).collect {
      case switch: Switch[?, ?] if switch.name == "maybeBackgroundStriders" => switch
    }
    assert(gates.size == 1, s"Expected exactly one background gate, found ${gates.size}")
    val gate = gates.head
    assert(gate.cases.size == 2)
    assert(gate.cases.head._1.toString.contains("sideCall"))
    assert(gate.cases.head._2._1.isEmpty, "Side calls must schedule no background work")
    assert(gate.cases(1)._2._1.map(_.name) == names,
      "Normal calls must schedule the three background Striders exactly once and in order")
    println("BACKGROUND_LIFECYCLE_GUARD_PASS")
    println("BACKGROUND_LIFECYCLE_COMPOSITION_PASS")
