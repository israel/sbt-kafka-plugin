
package com.github.israel.sbt.kafka

import sbt._
import sbt.Keys._
import com.github.israel.sbt.kafka.{KafkaPluginMeta => KPM}
import com.github.israel.sbt.zookeeper.SbtZookeeperPlugin.autoImport._
import com.github.israel.sbt.zookeeper.SbtZookeeperPlugin

/**
  * Created by israel on 12/22/15.
  */
object SbtKafkaPlugin extends sbt.AutoPlugin {


  object autoImport {

    /** Settings **/
    lazy val kafkaVersion = settingKey[String]("version of kafka")
    lazy val kafkaServerConfig = settingKey[File]("kafka server configuration file")
    lazy val includeZookeeper = settingKey[Boolean]("whether to include zookeeper in the start stop and clean tasks")
    lazy val kafkaServerRunDir = settingKey[File]("Running Kafka server process from this directory. ")
    lazy val stopAfterTests = settingKey[Boolean]("Stop kafka server after tests finish")
    lazy val startBeforeTests = settingKey[Boolean]("Auto start kafka server before tests start")
    lazy val cleanAfterTests = settingKey[Boolean]("Clean data after tests finish")
    lazy val cleanBeforeTests = settingKey[Boolean]("Clean data before test starts")

    /** Tasks **/
    lazy val startKafka = taskKey[Unit]("starting the kafka server")
    lazy val stopKafka = taskKey[Unit]("stop the kafka server")
    lazy val cleanKafka = taskKey[Unit]("clean kafka run dir")

  }

  import autoImport._

  override def requires: Plugins = SbtZookeeperPlugin

  var kafkaProcess:java.lang.Process = _

  private val doStopKafka = Def.task{
    kafkaProcess.destroy()
    var triesLeft = 20
    while(kafkaProcess.isAlive && triesLeft > 0) {
      Thread.sleep(500)
      triesLeft -= 1
    }
  }

  private val doStopZookeeper = Def.taskDyn{
    if(includeZookeeper.value)
      stopZookeeper
    else
      Def.task{}
  }


  private val doStartZookeeper = Def.taskDyn {
    if(includeZookeeper.value)
      startZookeeper
    else
      Def.task{}
  }

  private val doStartKafka = Def.task {
    println("starting Kafka only")
    val baseDir = kafkaServerRunDir.value
    if(!baseDir.isDirectory)
      baseDir.mkdir()
    val logDir = (kafkaServerRunDir / "log").value.getAbsolutePath
    val depClasspath = (dependencyClasspath in Runtime).value
    val classpath = Attributed.data(depClasspath)
    val serverConfigFile = kafkaServerConfig.value
    if(!serverConfigFile.exists()){
      val resourcesJar = classpath.find{_.getName.startsWith("sbt-kafka-plugin")}
      IO.withTemporaryDirectory{ tmpDir =>
        IO.unzip(resourcesJar.get, tmpDir)
        IO.copyFile(tmpDir / "kafka.server.properties", serverConfigFile)
      }
    }
    val configFile = serverConfigFile.absolutePath
    val cp = classpath.map{_.getAbsolutePath}.mkString(":")
    val javaExec = System.getProperty("java.home") + "/bin/java"
    val pb = new java.lang.ProcessBuilder(javaExec, "-classpath", cp, "kafka.Kafka", configFile)
    pb.directory(baseDir)
    kafkaProcess = pb.start()
  }

  private val doCleanKafka = Def.task {
    val dir = kafkaServerRunDir.value
    IO.delete(dir)
  }

  private val doCleanZookeeper = Def.taskDyn {
    if(includeZookeeper.value)
      cleanZookeeper
    else
      Def.task{}
  }


  override def projectSettings = Seq(

    libraryDependencies ++= Seq(
      "org.apache.kafka" %% "kafka" % kafkaVersion.value,
      KPM.pluginGroupId % KPM.resourcesArtifactId % KPM.pluginVersion

    ),

    /** Settings **/
    kafkaVersion := "0.9.0.0",
    kafkaServerConfig := (resourceDirectory in Runtime).value / "kafka.server.properties",
    includeZookeeper := true,
    kafkaServerRunDir := {
      val f = target.value / "kafka-server"
      f.mkdir()
      f
    },
    stopAfterTests := true,
    startBeforeTests := true,
    cleanAfterTests := false,
    cleanBeforeTests := true,

    /** Tasks **/
    startKafka := Def.sequential(doStartZookeeper, doStartKafka).value,

    stopKafka := Def.sequential(doStopKafka, doStopZookeeper).value,

    cleanKafka := Def.sequential(doCleanKafka, doCleanZookeeper).value,

    testOptions += {
      Tests.Setup(() => {
        if(cleanBeforeTests.value)
          cleanKafka.value
        if(startBeforeTests.value)
          startKafka.value
      })
      Tests.Cleanup(() => {
        if(stopAfterTests.value)
          stopKafka.value
        if(cleanAfterTests.value)
          cleanKafka.value
      })
    }
  )

}
