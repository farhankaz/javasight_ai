package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.{Filters, InsertOneModel}
import org.slf4j.LoggerFactory
import scalapb.GeneratedMessage
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.io.File
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._
import com.farhankaz.javasight.utils.ConfigurationLoader
import com.farhankaz.javasight.model.protobuf.{ImportModule, ImportFile}
import io.micrometer.core.instrument.MeterRegistry
import org.bson.types.ObjectId
import akka.kafka.ProducerMessage
import akka.NotUsed
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.ProjectImportedEvent
import com.farhankaz.javasight.model.kafka.ScanModuleFileCommand
import com.farhankaz.javasight.model.kafka.ScanModuleDirectoryCommand
import com.github.javaparser.StaticJavaParser
import scala.jdk.OptionConverters._
import com.farhankaz.javasight.model.kafka.ImportProjectCommand
import com.farhankaz.javasight.model.kafka.Module
import scala.xml.XML

class ProjectImportService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ImportProjectCommands,
      config,
      metricsRegistry,
      database
    ) {

  private val projects = database.getCollection[Document]("projects")
  private val projectContexts = database.getCollection[Document]("project_contexts")
  private val projectsImportedCounter = metricsRegistry.counter("projects_imported_total")
  
  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        ImportProjectCommand.parseFrom(msg.record.value())
      }
      .filter { msg =>
        if (isValidLocalPath(msg.projectLocation))
          true
        else {
          logger.error(s"Invalid project location: ${msg.projectLocation}")
          false
        }
      }
      .mapAsync(1)(importProject)
      .map { case (projectId, command) =>
        new ProducerRecord[Array[Byte], Array[Byte]](
          KafkaTopics.ProjectImportedEvents.toString(),
          ProjectImportedEvent(
            projectId.toHexString,
            command.projectName,
            command.projectLocation,
            parseProjectModules(projectId.toHexString, command),
            System.currentTimeMillis()
          ).toByteArray
          
        )
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Producer.plainSink(producerSettings))(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()
  }

  private def isValidLocalPath(path: String): Boolean = {
    val file = new File(path)
    file.exists() && file.isDirectory && Files.exists(Paths.get(path, "pom.xml"))
  }

  private def importProject(command: ImportProjectCommand) = {
    val projectId = ObjectId.get()
    
    // Create a Future sequence of both insert operations
    Future.sequence(Seq(
      projects.insertOne(Document(
        "_id" -> projectId,
        "projectName" -> command.projectName,
        "projectLocation" -> command.projectLocation
      )).toFuture(),
      
      projectContexts.insertOne(Document(
        "projectId" -> projectId.toHexString,
        "context" -> command.projectContext
      )).toFuture()
    )).map { _ =>
      projectsImportedCounter.increment()
      logger.info(s"Successfully imported project: ${command.projectName} from ${command.projectLocation}")
      (projectId, command)
    }
  }

  private def parseProjectModules(projectId: String, command: ImportProjectCommand): Seq[Module] = {
    val pomFile = new java.io.File(s"${command.projectLocation}/pom.xml")
        val pomXml = XML.loadFile(pomFile)
        val modules = (pomXml \ "modules" \ "module").map(_.text)
        if (modules.nonEmpty)
          modules.map(module =>
            Module(
              module,
              command.projectLocation + s"/$module",
              System.currentTimeMillis()
            )
          )
        else Seq(Module("default", command.projectLocation, System.currentTimeMillis()))
  }
  
}
