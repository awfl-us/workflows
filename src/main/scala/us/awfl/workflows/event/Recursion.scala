package us.awfl.workflows.event

import us.awfl.dsl.*

/** Original wip-old composable terminal hook. */
trait Recursion {
  def stop: Step[NoValueT, NoValue] = Block("stopInit", List() -> noValue)
}
