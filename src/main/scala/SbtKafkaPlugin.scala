import java.io.FileOutputStream
import java.lang

import sbt.Keys._
import sbt._

import scala.util.{Failure, Success, Try}

/**
  * Created by israel on 21/03/2016.
  */
object SbtKafkaPlugin extends sbt.AutoPlugin {

  object autoImport {

    /** Settings **/
    lazy val kafkaVersion = settingKey[String]("version of kafka")
    lazy val kafkaConfigFile = settingKey[Option[File]]("kafka server configuration file")
    lazy val zookeeperConfigFile = settingKey[Option[File]]("zookeeper server configuration file")
    lazy val kafkaServerRunDir = settingKey[File]("running Kafka server process from this directory.")
    lazy val cleanBeforeStart = settingKey[Boolean]("whether to clean all before starting")

    /** Tasks **/
    lazy val startKafka = taskKey[Unit]("start kafka server")
    lazy val stopKafka = taskKey[Unit]("stop kafka server")
    lazy val cleanKafka = taskKey[Unit]("clean run dir")
  }

  import autoImport._


  private val doCleanBeforeStart = Def.taskDyn {
    if(cleanBeforeStart.value)
      cleanKafka
    else
      Def.task{}
  }

  private val doStartKafka = Def.task {
    val logger = streams.value.log
    if(isKafkaRunning)
      logger.info("kafka is already running, stop it before retrying to start a new one")
    else {
      val baseDir = kafkaServerRunDir.value

      // If not created yet, extract kafka binaries and copy config files
      if(!baseDir.isDirectory) {
        val depClasspath = (dependencyClasspath in Runtime).value
        val classpath = Attributed.data(depClasspath)
        val kafkaBinary = classpath.find{_.getName.startsWith("kafka-dist")}.get
        // extract kafka tgz
        val pid = Process(Seq("tar","-xzf",kafkaBinary.getAbsolutePath), target.value).!
        kafkaConfigFile.value match {
          case Some(configFile) =>
            IO.copyFile(configFile, baseDir / "config" / "server.properties")
          case None =>
            val defaultKafkaConfig = this.getClass.getClassLoader.getResourceAsStream("kafka.server.properties")
            val fos = new FileOutputStream(baseDir / "config" / "server.properties")
            IO.transferAndClose(defaultKafkaConfig, fos)
            fos.close()
        }
        zookeeperConfigFile.value match {
          case Some(configFile) =>
            IO.copyFile(configFile, baseDir / "config" / "zookeeper.properties")
          case None =>
            val defaultZookeeperConfig = this.getClass.getClassLoader.getResourceAsStream("zookeeper.server.properties")
            val fos = new FileOutputStream(baseDir / "config" / "zookeeper.properties")
            IO.transferAndClose(defaultZookeeperConfig, fos)
            fos.close()
        }
      }

      // start zookeeper
      logger.info("starting zookeeper process")
      val zooPb = new lang.ProcessBuilder("./bin/zookeeper-server-start.sh", "config/zookeeper.properties")
      zooPb.directory(baseDir).redirectError(new File("zookeeper.err")).redirectOutput(new File("zookeeper.out"))
      val zooProcess = zooPb.start()
      Thread.sleep(10000)

      // start kafka process
      logger.info("starting kafka process")
      val kafkaPb = new lang.ProcessBuilder("./bin/kafka-server-start.sh", "config/server.properties")
      kafkaPb.directory(baseDir).redirectError(new File("kafka.err")).redirectOutput(new File("kafka.out"))
      val kafkaProcess = kafkaPb.start()
      Thread.sleep(10000)

    }

  }

  override def projectSettings = Seq(

    libraryDependencies ++= Seq(
      ("org.apache.kafka" % "kafka-dist" % kafkaVersion.value)
//        .artifacts(Artifact("kafka-dist","tgz","tgz"))
        .from(s"http://www-us.apache.org/dist/kafka/${kafkaVersion.value}/kafka_${scalaBinaryVersion.value}-${kafkaVersion.value}.tgz")
    ),

    classpathTypes ~= {_ + "tgz"},

    /** Settings **/
    kafkaVersion := "0.10.1.0",
    kafkaConfigFile := None,
    zookeeperConfigFile := None,
    kafkaServerRunDir := target.value / s"kafka_${scalaBinaryVersion.value}-${kafkaVersion.value}",
    cleanBeforeStart := true,


    /** Tasks **/
    stopKafka := {
      val logger = streams.value.log
      // first stop kafka
      logger.info("trying to stop kafka process")
      if (stopJavaProcessByName("kafka.Kafka") || stopJavaProcessByName("kafka.Kafka", true)) {
        logger.info("successfully stopped kafka process")
        logger.info("trying to stop zookeeper process")
        if(stopJavaProcessByName("org.apache.zookeeper.server.quorum.QuorumPeerMain") ||
          stopJavaProcessByName("org.apache.zookeeper.server.quorum.QuorumPeerMain", true))
          logger.info("successfully stopped zookeeper process")
        else
          logger.error("failed to stop zookeeper process")
      } else {
        logger.error("failed to stop kafka process. leaving zookeeper as is")
      }
    },

    startKafka := Def.sequential(doCleanBeforeStart, doStartKafka).value,

    cleanKafka := {
      IO.delete(kafkaServerRunDir.value)
    }

  )

  private def stopJavaProcessByName(processName:String, forcibly:Boolean = false): Boolean = {
    val p = sys.runtime.exec("jps -l")
    val lines = io.Source.fromInputStream(p.getInputStream).getLines().toSeq
    val pidOpt = lines.collectFirst({case s if (s.contains(processName)) => s.split(" ")(0)})
    val success = pidOpt match {
      case Some(pid) =>
        val command = if(forcibly)s"kill -9 $pid" else s"kill $pid"
        sys.runtime.exec(command)
        retry(Thread.sleep(2000))(isKafkaRunning)(10)
      case None => false
    }
    success
  }

  private def isKafkaRunning:Boolean = {
    val p = sys.runtime.exec("jps -l")
    val lines = io.Source.fromInputStream(p.getInputStream).getLines()
    lines.exists(_.contains("kafka.Kafka"))
  }


  private def retry(f: => Unit)(stopCondition: => Boolean)(maxRetries:Int = 10) :Boolean = {
    var round = 0
    do {
      round += 1
      f
    } while(stopCondition && round <= maxRetries)
    (round < maxRetries)
  }


}
