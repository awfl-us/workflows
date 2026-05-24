package us.awfl.workflows.evals

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.dsl.auto.given

object Product {
  def apply(variables: Map[String, ListValue[String]]): Step[Map[String, Cel], ListValue[Map[String, Cel]]] = {
    val init: Step[Map[String, Cel], ListValue[Map[String, Cel]]] =
      buildList("init", List(Map.empty))
    
    variables.toList.map { (n, v) => n -> ListValue[String](v.cel) }.foldLeft(init) { case (b, (name, values)) =>
      case class ParamsList(list: ListValue[Map[String, Cel]])
      val forValues = Fold(s"forValues_${name}", obj(ParamsList(ListValue.empty)), values) { (b2, v) =>
        val buildV = Try("buildV", List() -> obj(Map(name -> v)))
        val forB = For("forB", b.resultValue) { prev =>
          List() -> Value[Map[String, Cel]](CelFunc("map.merge", prev, buildV.resultValue))
        }
        val joined = join("joinParams", b2.get.list, forB.resultValue)
        List[Step[?, ?]](buildV, forB, joined) -> obj(ParamsList(joined.resultValue))
      }
      Block("foldVars", List[Step[?,?]](b, forValues) -> forValues.result.list)
    }
  }
}
