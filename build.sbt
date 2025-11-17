// build.sbt
ThisBuild / scalaVersion := "3.3.1"

name := "workflows"
organization := "us.awfl"

ThisBuild / versionScheme := Some("early-semver")
// ThisBuild / dynverTagPrefix := "" // uncomment if your tags are not prefixed with "v"

// Project metadata for Maven Central
ThisBuild / description := "Scala 3 toolkit for building, generating, and deploying Google Cloud Workflows with the AWFL DSL."
ThisBuild / homepage := Some(url("https://github.com/awfl-us/workflows"))
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/awfl-us/workflows"),
    "scm:git:https://github.com/awfl-us/workflows.git",
    Some("scm:git:ssh://git@github.com/awfl-us/workflows.git")
  )
)
ThisBuild / developers := List(
  Developer(id = "awfl-us", name = "AWFL", email = "oss@awfl.us", url = url("https://github.com/awfl-us"))
)
ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / version ~= { v =>
  if (sys.env.get("CI").contains("true")) v
  else "0.1.0-SNAPSHOT"
}

publishMavenStyle := true

// Dependencies
libraryDependencies ++= Seq(
  "us.awfl" %% "dsl" % "0.1.2",
  "us.awfl" %% "workflow-utils" % "0.1.3",
  "us.awfl" %% "compiler" % "0.1.2"
)

// dependencyOverrides += "us.awfl" %% "dsl" % "0.1.0-SNAPSHOT"
// dependencyOverrides += "us.awfl" %% "compiler" % "0.1.0-SNAPSHOT"
// dependencyOverrides += "us.awfl" %% "compiler-yaml" % "0.1.0-SNAPSHOT"
// dependencyOverrides += "us.awfl" %% "workflow-utils" % "0.1.0-SNAPSHOT"

scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions")

Compile / run / mainClass := Some("us.awfl.compiler.Main")
