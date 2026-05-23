package us.awfl.workflows.helpers

import us.awfl.dsl._
import us.awfl.dsl.CelOps._
import us.awfl.services.Storage

object Files {
  val filesBucket = str(PROJECT_ID.cel + CelFunc("text.to_lower", "-workflow-files${WORKFLOW_ENV}"))
  
  def readFile(name: String): Step[String, Value[String]] = {
    val read = Storage.readFile("readFile", filesBucket, name)
    Block("readFileBlock", List[Step[?, ?]](
      Log("logParams", str(("Reading file from storage (": Cel) + filesBucket + "/" + name + ")")),
      read
    ) -> read.resultValue)
  }

  def scripts(name: String) = Files.readFile(s"scripts/${name}")
}
