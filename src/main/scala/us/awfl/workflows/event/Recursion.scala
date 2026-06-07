package us.awfl.workflows.event

import us.awfl.dsl._

trait Recursion {
  // def next: Step[?, ?]
  def stop: Step[NoValueT, NoValue] = Block("stopInit", List() -> noValue)
}
