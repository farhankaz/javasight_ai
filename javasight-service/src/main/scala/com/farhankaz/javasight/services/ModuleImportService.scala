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
import com.farhankaz.javasight.utils.FileTypeSupport
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

class ModuleImportService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ProjectImportedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val modulesImportedCounter = metricsRegistry.counter(s"${config.env}_javasight_importmodule_modules_imported")
  private val moduleCollection = database.getCollection[Document]("modules")
  
  /**
   * Check if a module directory contains any valid supported files
   * (files with supported extensions and not filtered out)
   *
   * @param modulePath Path to the module directory
   * @return true if the module contains at least one valid source file, false otherwise
   */
  private def moduleContainsSupportedFiles(modulePath: String): Boolean = {
    def scanDirectory(dir: File): Boolean = {
      val files = dir.listFiles()
      if (files == null) return false
      
      // Check if any file in the current directory is a valid source file
      val hasSupportedFile = files.exists(file =>
        file.isFile && FileTypeSupport.isValidSourceFile(file.getAbsolutePath)
      )
      
      // If found valid source file, return true, otherwise search subdirectories
      hasSupportedFile || files.filter(_.isDirectory)
                             .exists(scanDirectory)
    }
    
    val moduleDir = new File(modulePath)
    scanDirectory(moduleDir)
  }

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        ProjectImportedEvent.parseFrom(msg.record.value())
      }
      .mapConcat(msg => msg.modules.map(module => (module, msg)))
      .filter { case (module, _) =>
        val containsSupportedFiles = moduleContainsSupportedFiles(module.location)
        if (!containsSupportedFiles) {
          logger.info(s"Skipping module ${module.name} at ${module.location} as it doesn't contain any supported files")
        }
        containsSupportedFiles
      }
      .mapAsync(1) { case (module, msg) =>
        val moduleId = ObjectId.get()
        modulesImportedCounter.increment()
        logger.info(s"Successfully imported module ${module.name} from ${module.location}")
        moduleCollection.insertOne(Document(
          "_id" -> moduleId,
          "projectId" -> msg.projectId,
          "moduleName" -> module.name,
          "modulePath" -> module.location
        )).toFuture().map(_ => (moduleId, module, msg))
      }
      .map { case (moduleId, module, msg) =>
        ProducerMessage.Message(
            new ProducerRecord[Array[Byte], Array[Byte]](
              KafkaTopics.ScanModuleDirectoryCommands.toString(),
              ScanModuleDirectoryCommand(
                projectId = msg.projectId,
                moduleId = moduleId.toString,
                path = module.location,
                parentPackageId = None,
                timestamp = System.currentTimeMillis()
              ).toByteArray
            ),
            NotUsed
          )
      }
      .via(Producer.flow(producerSettings))
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()

  }
}
