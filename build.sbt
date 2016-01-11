
lazy val commonSettings = Seq(
  shellPrompt := { s => Project.extract(s).currentProject.id + " > " },
  version := "0.1",
  organization := "com.github.israel"
)

val resourcesProjectName = "sbt-kafka-plugin-resources"

lazy val root = (project in file(".")).
  settings(commonSettings : _*).
  settings(
    name := "sbt-kafka-plugin",
    sbtPlugin := true,
    addSbtPlugin("com.github.israel" % "sbt-zookeeper-plugin" % "0.1"),
    sourceGenerators in Compile += task[Seq[File]] {
      val file =  (sourceManaged in Compile).value / "com" / "github" / "israel" / "sbt" / "kafka" / "BuildUtils.scala"
      IO.write(file,
        s"""
           |package com.github.israel.sbt.kafka
           |
        |object KafkaPluginMeta {
           |  val pluginVersion = "${version.value}"
           |  val pluginSbtVersion = "${sbtVersion.value}"
           |  val pluginArtifactId = "${name.value}"
           |  val resourcesArtifactId = "${resourcesProjectName}_${scalaBinaryVersion.value}"
           |  val pluginGroupId = "${organization.value}"
           |}
      """.stripMargin)
      Seq(file)
    }
  ).aggregate(resources)

lazy val resources = (project in file("./resources")).
  settings(commonSettings : _*).
  settings(
    name := resourcesProjectName,
    // disable publishing the main API jar
    publishArtifact in (Compile, packageDoc) := false,
    // disable publishing the main sources jar
    publishArtifact in (Compile, packageSrc) := false
  )

