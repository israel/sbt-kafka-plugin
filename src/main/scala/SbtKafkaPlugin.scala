
package com.github.israel.sbt.kafka

import com.github.israel.sbt.kafka.{KafkaPluginMeta => KPM}
import com.github.israel.sbt.zookeeper.SbtZookeeperPlugin
import com.github.israel.sbt.zookeeper.SbtZookeeperPlugin.autoImport._
import sbt.Keys._
import sbt._

/**
  * Created by israel on 12/22/15.
  */
object SbtKafkaPlugin extends sbt.AutoPlugin {


  object autoImport {

    /** Settings **/
    lazy val kafkaVersion = settingKey[String]("version of kafka")
    lazy val kafkaServerConfig = settingKey[File]("kafka server configuration file")
    lazy val log4jConfiguration = settingKey[File]("log4j configuration file")
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

  var kafkaProcess:java.lang.Process = null

  private def killKafka(force:Boolean = false)(implicit logger:Logger) = {
    val p = sys.runtime.exec("jps -l")
    val lines = io.Source.fromInputStream(p.getInputStream).getLines()
    val pidOpt = lines.collectFirst({case s if (s.contains("kafka.Kafka")) => s.split(" ")(0)})
    pidOpt match {
      case Some(pid) =>
        val command = if(force)s"kill -9 $pid" else s"kill $pid"
        sys.runtime.exec(command)
      case None => logger.debug("requested to kill kafka process but none was found")
    }
  }

  private val doStopKafka = Def.task{
    implicit val logger = streams.value.log
    logger.info("preparing to stop kafka process")

    if(kafkaProcess != null)
      kafkaProcess.destroy()
    else
      killKafka()

    var triesLeft = 20
    while(isKafkaRunning && triesLeft > 0) {
      logger.info("waiting 500ms for kafka process to finish...")
      Thread.sleep(500)
      triesLeft -= 1
    }
    if(triesLeft == 0) {
      logger.warn("failed to stop kafka process nicely, using the heavy guns...")
      killKafka(true)
      logger.info("kafka process forcefully killed (-9)")
    } else {
      logger.info("kafka process successfully stopped")
    }
    kafkaProcess = null
  }

  private val doStopZookeeper = Def.taskDyn{
    streams.value.log.debug("doStopZookeeper")
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

  private def isKafkaRunning:Boolean = {
    val p = sys.runtime.exec("jps -l")
    val lines = io.Source.fromInputStream(p.getInputStream).getLines()
    lines.exists(_.contains("kafka.Kafka"))
  }

  private val doStartKafka = Def.task {
    val logger = streams.value.log
    logger.info("preparing to start kafka process")
    if(isKafkaRunning) {
      logger.error("kafka process is already running, won't start a new one")
      throw new IllegalArgumentException("requested to start kafka process while one is already running")
    }
    val baseDir = kafkaServerRunDir.value
    if(!baseDir.isDirectory)
      baseDir.mkdir()
    val depClasspath = (dependencyClasspath in Runtime).value
    val classpath = Attributed.data(depClasspath)
    val serverConfigFile = kafkaServerConfig.value

    val resourcesJar = classpath.find{_.getName.startsWith("sbt-kafka-plugin")}
    IO.unzip(resourcesJar.get, baseDir)
    if(!serverConfigFile.exists()){
      val resourcesJar = classpath.find{_.getName.startsWith("sbt-kafka-plugin")}
      IO.withTemporaryDirectory{ tmpDir =>
        IO.unzip(resourcesJar.get, tmpDir)
        IO.copyFile(tmpDir / "kafka.server.properties", serverConfigFile)
      }
    }
    val log4jConfigFile = log4jConfiguration.value.getAbsolutePath

    val configFile = serverConfigFile.absolutePath
    val cp = classpath.map{_.getAbsolutePath}.mkString(":")
    val javaExec = System.getProperty("java.home") + "/bin/java"
    val pb = new java.lang.ProcessBuilder(javaExec, "-classpath", cp, s"-Dlog4j.configuration=$log4jConfigFile", "kafka.Kafka", configFile)
    pb.directory(baseDir).inheritIO()
    kafkaProcess = pb.start()
    Thread.sleep(2000)
    if(isKafkaRunning)
      logger.info("successfuly started kafka process")
    else {
      logger.error("failed to start kafka process")
      sys.error("failed to start kafka process")
    }
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

  private val doCleanKafkaBeforeTests = Def.taskDyn {
    streams.value.log.error("doCleanKafkaBeforeTests")
    if(cleanBeforeTests.value)
      doCleanKafka
    else
      Def.task{}
  }

  private val doStartKafkaBeforeTests = Def.taskDyn {
    streams.value.log.error("doStartKafkaBeforeTests")
    if(startBeforeTests.value)
      doStartKafka
    else
      Def.task{}
  }

  private val doCleanKafkaAfterTests = Def.taskDyn {
    streams.value.log.error("doCleanKafkaAfterTests")
    if(cleanAfterTests.value)
      cleanKafka
    else
      Def.task{}
  }

  private val doStopKafkaAfterTests = Def.taskDyn {
    streams.value.log.error("doStopKafkaAfterTests")
    if(stopAfterTests.value)
      stopKafka
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
    log4jConfiguration := (resourceDirectory in Runtime).value / "log4j.properties",
    includeZookeeper := true,
    kafkaServerRunDir := {
      val f = target.value / s"kafka-server-started@${new java.util.Date().toString.replaceAll(" ", "-")}"
      f.mkdir()
      f
    },
    stopAfterTests := true,
    startBeforeTests := true,
    cleanAfterTests := false,
    cleanBeforeTests := true,

    /** Tasks **/
    startKafka := {
      Def.sequential(doStartZookeeper, doStartKafka).value
    },

    stopKafka := Def.sequential(doStopKafka, doStopZookeeper).value,

    cleanKafka := Def.sequential(doCleanKafka, doCleanZookeeper).value

//      testOptions in Test += Tests.Setup( () => println("!!!!!!!!!!!!!! kafka-plugin") )

//    testOptions in Test += {
//      Tests.Setup(() => {
//        streams.value.log.error("K Tests.Setup")
////        cleanZookeeperFunc.value()
////        Def.sequential(doCleanKafkaBeforeTests, doStartKafkaBeforeTests).value
//      })
//      Tests.Cleanup(() => {
//        streams.value.log.error("K Tests.Cleanup")
////        Def.sequential(doStopKafkaAfterTests, doCleanKafkaAfterTests).value
//      })
//    }
  )

  override def trigger = allRequirements

}
