// build.sbt
ThisBuild / scalaVersion := "3.3.1"

name := "workflows"
organization := "us.awfl"
version := "0.1.0-SNAPSHOT"

// Circe Core + Generic + Parser + YAML
libraryDependencies ++= Seq(
  "us.awfl" %% "dsl" % "0.1.0-SNAPSHOT",
  "us.awfl" %% "workflow-utils" % "0.1.0-SNAPSHOT",
  "us.awfl" %% "compiler" % "0.1.0-SNAPSHOT"
)

publishMavenStyle := true
scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions")

Compile / run / mainClass := Some("us.awfl.compiler.Main")

// ThisBuild / commands += Command.args("awflC", "<className>") { (state, args) =>
//   val quoted = args.map("\"" + _ + "\"").mkString(" ")
//   s"run $quoted" :: state
// }
