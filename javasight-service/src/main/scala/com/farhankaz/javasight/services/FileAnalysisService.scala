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
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import org.bson.types.ObjectId
import akka.kafka.ProducerMessage
import akka.NotUsed
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.ProjectImportedEvent
import com.farhankaz.javasight.model.kafka.ScanModuleFileCommand
import com.farhankaz.javasight.model.kafka.ScanModuleDirectoryCommand
import com.farhankaz.javasight.model.kafka.ModuleFileScannedEvent
import com.farhankaz.javasight.model.kafka.FileAnalyzedEvent
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsBoolean
import spray.json.JsArray
import spray.json._
import DefaultJsonProtocol._
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ConnectionPoolSettings
import scala.concurrent.duration._
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Updates.set
import akka.kafka.ConsumerMessage
import org.mongodb.scala.model.Updates
import com.farhankaz.javasight.utils.Ollama
import com.farhankaz.javasight.utils.CodeAnalyzer

class FileAnalysisService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase,
    ollama: CodeAnalyzer
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ModuleFileScannedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val filesAnalyzed = metricsRegistry.counter(
    "javasight_files_analyzed_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val analysisRequests = metricsRegistry.counter(
    "javasight_file_analysis_requests_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val analysisFailures = metricsRegistry.counter(
    "javasight_file_analysis_failures_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env, "failure_reason", "analysis_error")
  )

  private val javaFilesCollection = database.getCollection[Document]("java_files")
  private val projectContextCollection = database.getCollection[Document]("project_context")

  private def getProjectContext(projectId: String): Future[Option[String]] = {
    projectContextCollection
      .find(equal("projectId", projectId))
      .first()
      .toFutureOption()
      .map(_.map(_.getString("context")))
  }

  private def checkRecentAnalysis(fileId: String): Future[Boolean] = {
    val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
    javaFilesCollection.find(
      Filters.and(
        equal("_id", new ObjectId(fileId)),
        // Filters.exists("analysis", true),
        Filters.exists("shortAnalysis", true),
        // Filters.ne("analysis", ""),
        Filters.ne("shortAnalysis", "")
      )
    ).first().toFuture().map { doc =>
      Option(doc) match {
        case Some(d) => d.nonEmpty
        case None => false
      }
    }
  }

  protected override def startService(): Consumer.DrainingControl[Done] = {
    val producerSink = Producer.plainSink(producerSettings)
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        (msg, ModuleFileScannedEvent.parseFrom(msg.record.value()))
      }
      .mapAsync(3) { case (msg, fileEvent) =>
        logger.trace(s"Received file to analyze: ${fileEvent.filePath} - ${fileEvent.parentPackageId}")
        checkRecentAnalysis(fileEvent.fileId).flatMap { hasRecentAnalysis =>
          if (hasRecentAnalysis) {
            // Return existing analysis event
            Future.successful(FileAnalyzedEvent(
              fileId = fileEvent.fileId,
              packageId = fileEvent.parentPackageId,
              projectId = fileEvent.projectId,
              moduleId = fileEvent.moduleId,
              filePath = fileEvent.filePath,
              timestamp = System.currentTimeMillis()
            ))
          } else {
            // Proceed with new analysis
            analyzeFile(fileEvent)
          }
        }.map(event => (event, msg.committableOffset))
      }
      .collect { case (event, offset) =>
        (
          new ProducerRecord[Array[Byte], Array[Byte]](
            KafkaTopics.FileAnalyzedEvents.toString(),
            event.toByteArray
          ),
          offset
        )
      }
      .groupedWithin(20, 5.seconds)
      .mapAsync(3) { messages =>
        val (records, offsets) = messages.unzip
        Source(records)
          .runWith(producerSink)
          .map(_ => offsets)
      }
      .mapAsync(3) { offsets =>
        offsets
          .foldLeft(ConsumerMessage.CommittableOffsetBatch.empty)(_.updated(_))
          .commitScaladsl()
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()

  }

  private def analyzeFile(file: ModuleFileScannedEvent): Future[FileAnalyzedEvent] = {
    logger.trace(s"Analyzing file: ${file.fileId} - ${file.filePath} - package ${file.parentPackageId}")
    analysisRequests.increment()

    if (file.filePath.contains("Record")) {
      // Skip analysis for Record files
      javaFilesCollection.updateOne(
        equal("_id", new ObjectId(file.fileId)),
        Updates.combine(
          set("shortAnalysis", "not analyzed"),
          set("analysisDate", System.currentTimeMillis())
        )
      ).toFuture().map { result =>
        filesAnalyzed.increment()
        logger.info(s"Skipped analysis for Record file ${file.filePath}")
        FileAnalyzedEvent(
          fileId = file.fileId,
          packageId = file.parentPackageId,
          projectId = file.projectId,
          moduleId = file.moduleId,
          filePath = file.filePath,
          timestamp = System.currentTimeMillis()
        )
      }
    } else {
      val fileContent = new String(Files.readAllBytes(Paths.get(file.filePath)))
      
      getProjectContext(file.projectId).flatMap { projectContext =>
        // val fullAnalysis = ollama.analyzeFile(fileContent, projectContext)
        val shortAnalysis = ollama.analyzeFileShort(fileContent, projectContext)
        
        Future.sequence(Seq(shortAnalysis)).flatMap { case Seq(shortContent) =>
          javaFilesCollection.updateOne(
            equal("_id", new ObjectId(file.fileId)),
            Updates.combine(
              // set("analysis", content),
              set("shortAnalysis", shortContent),
              set("analysisDate", System.currentTimeMillis())
            )
          ).toFuture().map { result =>
            filesAnalyzed.increment()
            logger.info(s"Analyzed file ${file.filePath} with id ${file.fileId}")
            FileAnalyzedEvent(
              fileId = file.fileId,
              packageId = file.parentPackageId,
              projectId = file.projectId,
              moduleId = file.moduleId,
              filePath = file.filePath,
              timestamp = System.currentTimeMillis()
            )
          }
        }.recoverWith { case e =>
          analysisFailures.increment()
          logger.error(s"Failed to analyze file ${file.filePath}: ${e.getMessage}")
          Future.failed(e)
        }
      }
    }
  }
}
