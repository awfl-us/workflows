package us.awfl.workflows.evals

import us.awfl.dsl._

object Product {
  def apply(variables: Map[String, ListValue[Cel]]): Step[Map[String, Cel], ListValue[Map[String, Cel]]] = {
    val init: Step[Map[String, Cel], ListValue[Map[String, Cel]]] = Try("init", List() -> ListValue.empty[Map[String, Cel]])
    variables.toList.map { (n, v) => n -> ListValue[String](v.cel) }.foldLeft(init) { case (b, (name, values)) =>
      val forValues = For("forValues", values) { v =>
        val buildV = Try("buildV", List() -> obj(Map(name -> v)))
        val forB = For("forB", b.resultValue) { prev =>
          List() -> Value[Map[String, Cel]](CelFunc("map.merge", prev, buildV.resultValue))
        }
        List[Step[?, ?]](buildV, forB) -> forB.resultValue
      }
      Try("foldVars", List(b, forValues) -> forValues.resultValue)
    }
  }
}
