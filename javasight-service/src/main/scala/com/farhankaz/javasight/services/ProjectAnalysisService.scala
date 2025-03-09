package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.{Filters, Updates}
import org.slf4j.LoggerFactory
import scalapb.GeneratedMessage
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.io.File
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._
import com.farhankaz.javasight.utils.{ConfigurationLoader, RedissonLock}
import com.farhankaz.javasight.model.protobuf.{ImportModule, ImportFile}
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import org.bson.types.ObjectId
import akka.kafka.ProducerMessage
import akka.NotUsed
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.{ModuleAnalyzedEvent, ProjectAnalyzedEvent}
import spray.json._
import DefaultJsonProtocol._
import org.mongodb.scala.model.Filters.{equal, and}
import org.mongodb.scala.model.Updates.set
import akka.kafka.ConsumerMessage
import java.lang
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import com.farhankaz.javasight.utils.Ollama
import com.farhankaz.javasight.utils.CodeAnalyzer
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

class ProjectAnalysisService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase,
    redisLock: RedissonLock,
    ollama: CodeAnalyzer
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ModuleAnalyzedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val projectsAnalyzed = metricsRegistry.counter(
    "javasight_projects_analyzed_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val projectAnalysisFailures = metricsRegistry.counter(
    "javasight_project_analysis_failures_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env, "failure_reason", "analysis_error")
  )
  
  // Metrics for token counting
  private val projectAnalysisTokens = metricsRegistry.summary(
    "javasight_project_analysis_tokens",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val totalTokenUsage = metricsRegistry.counter(
    "javasight_token_usage_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env, "usage_type", "project_analysis")
  )
  
  private val javaModulesCollection = database.getCollection[Document]("modules")
  private val javaProjectsCollection = database.getCollection[Document]("projects")
  private val importStatusCollection = database.getCollection[Document]("project_import_status")
  
  protected override def startService(): Consumer.DrainingControl[Done] = {
    val producerSink = Producer.plainSink(producerSettings)

    moduleAnalyzedEventsSource()
      .mapAsync(3) { case (msg, projectId) =>
        logger.debug(s"Received module analyzed event for project ${projectId}")
        allModulesAnalyzed(projectId).map { result =>
          if (!result) {
            logger.debug(s"Skipping project $projectId as not all modules are analyzed")
          }
          (msg, projectId, result)
        }.recover { case ex =>
          logger.error(s"Error checking project analysis status for $projectId", ex)
          recordProcessingError()
          (msg, projectId, false)
        }
      }
      .filter { case (msg, projectId, result) => result }
      .map { case (msg, projectId, _) => (msg, projectId) }
      .mapAsync(1) { case (msg, projectId) =>
        analyzeProject(projectId)
          .map(eventOpt => (eventOpt, msg.committableOffset))
      }
      .collect { case (Some(event), offset) =>
        (
          new ProducerRecord[Array[Byte], Array[Byte]](
            KafkaTopics.ProjectAnalyzedEvents.toString(),
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
          .map { result =>
            recordMessageProcessed()
            offsets
          }
      }
      .mapAsync(3) { offsets =>
        offsets
          .foldLeft(ConsumerMessage.CommittableOffsetBatch.empty)(_.updated(_))
          .commitScaladsl()
          .recover { case ex =>
            logger.error("Failed to commit offsets", ex)
            recordProcessingError()
            throw ex
          }
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()
  }

  private def moduleAnalyzedEventsSource(): Source[(ConsumerMessage.CommittableMessage[Array[Byte],Array[Byte]], String),Consumer.Control] = {
    Consumer
      .committableSource(
        consumerSettings
          .withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}")
          .withGroupId(s"${config.getKafkaClientId}-${getClass.getSimpleName}"), 
          Subscriptions.topics(KafkaTopics.ModuleAnalyzedEvents.toString()))
      .map { msg =>
        val event = ModuleAnalyzedEvent.parseFrom(msg.record.value())
        (msg, event.moduleId)
      }
      .mapAsync(3) { case (msg, moduleId) =>
        getProjectIdForModule(moduleId).map(projectId => (msg, projectId))
      }
      .filter { case (_, projectId) => projectId.nonEmpty }
      .map { case (msg, projectId) => (msg, projectId.get) }
  }

  private def getProjectIdForModule(moduleId: String): Future[Option[String]] = {
    javaModulesCollection
      .find(equal("_id", new ObjectId(moduleId)))
      .first()
      .toFuture()
      .map(doc => Option(doc.getString("projectId")))
      .recover {
        case ex: Exception =>
          logger.error(s"Could not find project ID for module $moduleId", ex)
          recordProcessingError()
          None
      }
  }

  private def allModulesAnalyzed(projectId: String): Future[Boolean] = {
    val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
    
    javaModulesCollection
      .find(equal("projectId", projectId))
      .toFuture()
      .map { modules =>
        modules.forall { module =>
          Option(module.getString("analysis")).exists(_.nonEmpty)
        }
      }
  }

  private def analyzeProject(projectId: String): Future[Option[ProjectAnalyzedEvent]] = {
    getProjectDocument(projectId).flatMap { projectDocOpt =>
      projectDocOpt match {
        case Some(projectDoc) if Option(projectDoc.getString("analysis")).exists(_.nonEmpty) && 
          Option(projectDoc.getLong("analysisDate")).exists(_ > (System.currentTimeMillis() - 3600000)) =>
          logger.debug(s"Skipping analysis for project $projectId as it has recent analysis")
          Future.successful(None)
        case Some(projectDoc) =>
          for {
            modules <- getModules(projectId)
            analysis <- generateProjectAnalysis(projectId, projectDoc.getString("projectName"), modules)
            _ <- updateProjectAnalysis(projectId, analysis)
          } yield {
            projectsAnalyzed.increment()
            logger.info(s"Successfully analyzed project ${projectId}")
            javaProjectsCollection.updateOne(
              equal("_id", new ObjectId(projectId)),
              set("analysisDate", System.currentTimeMillis())
            ).toFuture()
            Some(ProjectAnalyzedEvent(
              projectId = projectId,
              analysis = analysis,
              timestamp = System.currentTimeMillis()
            ))
          }
        case None =>
          projectAnalysisFailures.increment()
          logger.error(s"Failed to analyze project ${projectId}: no project document found")
          Future.successful(None)
      }
    }
  }

  private def getProjectDocument(projectId: String): Future[Option[Document]] = {
    javaProjectsCollection.find(
      equal("_id", new ObjectId(projectId))
    ).first().toFutureOption().recover {
      case ex: Exception =>
        logger.error(s"Could not find project document for id: $projectId", ex)
        recordProcessingError()
        None
    }
  }

  private def getModules(projectId: String): Future[Seq[Document]] = {
    javaModulesCollection.find(
      equal("projectId", projectId)
    ).toFuture()
  }

  private def generateProjectAnalysis(projectId: String, projectName: String, modules: Seq[Document]): Future[String] = {
    val moduleAnalyses = modules.map { module =>
      s"Module: ${module.getString("moduleName")}\nAnalysis: ${module.getString("analysis")}"
    }
    logger.debug("Starting project analysis for " + projectName)
    ollama.analyzeProject(projectName, moduleAnalyses)
  }
  
  /**
   * Counts the tokens in a given text using the CL100K_BASE encoding used by models like GPT-4.
   *
   * @param text The text to count tokens for
   * @return The number of tokens in the text
   */
  private def countTokens(text: String): Int = {
    try {
      val encodingRegistry = Encodings.newDefaultEncodingRegistry()
      val encoding = encodingRegistry.getEncoding(EncodingType.CL100K_BASE) // Common encoding for GPT models
      encoding.countTokens(text)
    } catch {
      case ex: Exception =>
        logger.error("Failed to count tokens", ex)
        0 // Return 0 on error
    }
  }

  private def updateProjectAnalysis(projectId: String, analysis: String): Future[Unit] = {
    // Count tokens in the analysis
    val analysisTokenCount = countTokens(analysis)
    logger.debug(s"Analysis token count for project $projectId: $analysisTokenCount")
    
    // Record token metrics
    projectAnalysisTokens.record(analysisTokenCount)
    totalTokenUsage.increment(analysisTokenCount.toDouble)
    
    // Update project analysis
    val projectUpdateFuture = javaProjectsCollection.updateOne(
      equal("_id", new ObjectId(projectId)),
      org.mongodb.scala.model.Updates.combine(
        set("analysis", analysis),
        set("analysisTokenCount", analysisTokenCount)
      )
    ).toFuture()
    
    // Update import status to 100% if this project was imported from GitHub
    val importStatusUpdateFuture = importStatusCollection.updateOne(
      equal("_id", new ObjectId(projectId)),
      org.mongodb.scala.model.Updates.combine(
        set("status", "completed"),
        set("message", "Analysis complete"),
        set("progress", 100),
        set("updatedAt", new java.util.Date())
      )
    ).toFuture()
    
    // Execute both updates and log results
    Future.sequence(Seq(projectUpdateFuture, importStatusUpdateFuture))
      .map { _ =>
        logger.info(s"Updated analysis for project $projectId and set import status to 100%")
      }
      .recover {
        case ex: Exception =>
          logger.error(s"Error updating project analysis or import status for $projectId", ex)
          throw ex
      }
  }
}
