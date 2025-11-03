package services

import dsl._
import dsl.auto.given
import dsl.Cel._
import dsl.CelOps._
import io.circe.generic.auto._

object GoogleStorage {

  case class ReadFileArgs(bucket: Field, `object`: Field, alt: BaseValue[String] = str("media"))
  case class FileContent(content: Field)

  /**
   * Reads the content of a file from a Google Cloud Storage bucket.
   * @param name Workflow step name.
   * @param bucket Bucket name.
   * @param object Path to object within the bucket.
   */
  def readFile(name: String, bucket: Field, `object`: Field): Call[ReadFileArgs, FileContent] = {
    val args = ReadFileArgs(bucket, `object`)
    Call(name, "googleapis.storage.v1.objects.get", obj(args))
  }
} 