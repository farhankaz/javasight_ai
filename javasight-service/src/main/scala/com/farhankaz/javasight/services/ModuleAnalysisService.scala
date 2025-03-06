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
import com.farhankaz.javasight.utils.{ConfigurationLoader, RedissonLock}
import com.farhankaz.javasight.model.protobuf.{ImportModule, ImportFile}
import com.farhankaz.javasight.model.kafka.AnalyzeModuleCommand
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import org.bson.types.ObjectId
import akka.kafka.ProducerMessage
import akka.NotUsed
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.{PackageAnalyzedEvent, ModuleAnalyzedEvent}
import spray.json._
import DefaultJsonProtocol._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ConnectionPoolSettings
import scala.concurrent.duration._
import org.mongodb.scala.model.Filters.{equal, and}
import org.mongodb.scala.model.Updates.set
import akka.kafka.ConsumerMessage
import java.lang
import scala.collection.JavaConverters._
import com.farhankaz.javasight.utils.Ollama
import com.farhankaz.javasight.utils.CodeAnalyzer

class ModuleAnalysisService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase,
    redisLock: RedissonLock,
    ollama: CodeAnalyzer
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
        KafkaTopics.PackageAnalyzedEvents,
        config,
        metricsRegistry,
        database
      ) {
    
    // Add a counter for direct module analysis requests
    private val directModuleAnalysisRequests = metricsRegistry.counter(
      "javasight_direct_module_analysis_requests_total",
      Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
    )

  private val http = Http()
  private val modulesAnalyzed = metricsRegistry.counter(
    "javasight_modules_analyzed_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val moduleAnalysisFailures = metricsRegistry.counter(
    "javasight_module_analysis_failures_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env, "failure_reason", "analysis_error")
  )
  private val javaPackagesCollection = database.getCollection[Document]("java_packages")
  private val javaModulesCollection = database.getCollection[Document]("java_modules")
  private val projectContextsCollection = database.getCollection[Document]("project_contexts")
  
  val timeoutSettings = ConnectionPoolSettings(system)
    .withIdleTimeout(30.minutes)
    .withKeepAliveTimeout(25.minutes)
    .withMaxRetries(3)

  // Add a method to start a consumer for the direct analyze module commands
  private def directAnalyzeModuleConsumer(): Consumer.DrainingControl[Done] = {
    val producerSink = Producer.plainSink(producerSettings)

    Consumer
      .committableSource(
        consumerSettings
          .withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}-direct-analyze")
          .withGroupId(s"${config.getKafkaClientId}-${getClass.getSimpleName}-direct-analyze"),
        Subscriptions.topics(KafkaTopics.AnalyzeModuleCommands.toString()))
      .map { msg =>
        try {
          val command = AnalyzeModuleCommand.parseFrom(msg.record.value())
          logger.info(s"Received direct analyze module command for module ${command.moduleId}")
          directModuleAnalysisRequests.increment()
          (command.moduleId, command.projectId, msg.committableOffset)
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to parse direct analyze module command", ex)
            recordProcessingError()
            ("", "", msg.committableOffset)
        }
      }
      .filter { case (moduleId, projectId, _) => moduleId.nonEmpty && projectId.nonEmpty }
      .mapAsync(1) { case (moduleId, projectId, offset) =>
        val lockKey = s"module-analysis-lock:$moduleId"
        logger.debug(s"Attempting to acquire lock for module $moduleId")
        
        redisLock.withLock(lockKey) {
          logger.debug(s"Starting direct analysis for module $moduleId")
          analyzeModule(moduleId, projectId)
            .map { eventOpt =>
              logger.debug(s"Completed direct analysis for module $moduleId")
              (eventOpt, offset)
            }
            .recover { case ex =>
              logger.error(s"Direct analysis failed for module $moduleId", ex)
              recordProcessingError()
              (None, offset)
            }
        }.recover {
          case ex: RuntimeException if ex.getMessage.contains("Could not acquire lock") =>
            logger.debug(s"Skipping direct analysis for module $moduleId - lock acquisition failed")
            (None, offset)
          case ex =>
            logger.error(s"Error during direct module analysis for $moduleId", ex)
            recordProcessingError()
            (None, offset)
        }
      }
      .collect { case (Some(event), offset) =>
        (
          new ProducerRecord[Array[Byte], Array[Byte]](
            KafkaTopics.ModuleAnalyzedEvents.toString(),
            event.toByteArray
          ),
          offset
        )
      }
      .groupedWithin(10, 3.seconds)
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

  protected override def startService(): Consumer.DrainingControl[Done] = {
    // Start both consumers
    logger.info("Starting package analyzed events consumer")
    val packageAnalyzedControl = startPackageAnalyzedConsumer()
    
    logger.info("Starting direct analyze module commands consumer")
    val directAnalyzeControl = directAnalyzeModuleConsumer()
    
    // Return the package analyzed consumer control for backward compatibility
    packageAnalyzedControl
  }
  
  private def startPackageAnalyzedConsumer(): Consumer.DrainingControl[Done] = {
    val producerSink = Producer.plainSink(producerSettings)

    packageAnalyzedEventsSource()
      .mapAsync(1) { case (msg, moduleId, projectId) =>
        logger.debug(s"Processing package analyzed event for module ${moduleId}")
        allRootPackagesAnalyzed(moduleId).map { result =>
          if (!result) {
            logger.debug(s"Skipping module $moduleId as not all root packages are analyzed")
          }
          (msg, moduleId, projectId, result)
        }.recover { case ex =>
          logger.error(s"Error checking module analysis status for $moduleId", ex)
          recordProcessingError()
          (msg, moduleId, projectId, false)
        }
      }
      .filter { case (msg, moduleId, projectId, result) => result }
      .map { case (msg, moduleId, projectId, _) => (msg, moduleId, projectId) }
      .mapAsync(1) { case (msg, moduleId, projectId) =>
        val lockKey = s"module-analysis-lock:$moduleId"
        logger.debug(s"Attempting to acquire lock for module $moduleId")
        
        redisLock.withLock(lockKey) {
          logger.debug(s"Starting analysis for module $moduleId")
          analyzeModule(moduleId, projectId)
            .map { eventOpt => 
              logger.debug(s"Completed analysis for module $moduleId")
              (eventOpt, msg.committableOffset)
            }
            .recover { case ex =>
              logger.error(s"Analysis failed for module $moduleId", ex)
              recordProcessingError()
              (None, msg.committableOffset)
            }
        }.recover {
          case ex: RuntimeException if ex.getMessage.contains("Could not acquire lock") =>
            logger.debug(s"Skipping analysis for module $moduleId - lock acquisition failed")
            (None, msg.committableOffset)
          case ex =>
            logger.error(s"Error during module analysis for $moduleId", ex)
            recordProcessingError()
            (None, msg.committableOffset)
        }
      }
      .collect { case (Some(event), offset) =>
        (
          new ProducerRecord[Array[Byte], Array[Byte]](
            KafkaTopics.ModuleAnalyzedEvents.toString(),
            event.toByteArray
          ),
          offset
        )
      }
      .groupedWithin(10, 3.seconds)
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

  private def packageAnalyzedEventsSource(): Source[(ConsumerMessage.CommittableMessage[Array[Byte],Array[Byte]], String, String),Consumer.Control] = {
    Consumer
      .committableSource(
        consumerSettings
          .withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}")
          .withGroupId(s"${config.getKafkaClientId}-${getClass.getSimpleName}"), 
          Subscriptions.topics(KafkaTopics.PackageAnalyzedEvents.toString()))
      .map { msg =>
        val event = PackageAnalyzedEvent.parseFrom(msg.record.value())
        // Only process events with no parent package (root packages)
        if (event.parentPackageId.isEmpty) {
          logger.trace(s"Received root package analyzed event for module ${event.moduleId}")
          (msg, event.moduleId, event.projectId)
        } else {
          logger.trace(s"Received non-root package analyzed event for module ${event.moduleId}")
          // Skip non-root packages
          (msg, "", "")
        }
      }
      .filter { case (_, moduleId, projectId) => moduleId.nonEmpty && projectId.nonEmpty }
  }

  private def allRootPackagesAnalyzed(moduleId: String): Future[Boolean] = {
    val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
    
    javaPackagesCollection
      .find(and(
        equal("module_id", moduleId),
        equal("parentPackageId", null)
      ))
      .toFuture()
      .map { packages =>
        packages.forall { pkg =>
          Option(pkg.getLong("analysisDate")).isDefined
        }
      }
  }

  private def getProjectName(projectId: String): Future[String] = {
    database.getCollection[Document]("projects")
      .find(equal("_id", new ObjectId(projectId)))
      .first()
      .toFuture()
      .map(_.getString("projectName"))
      .recover {
        case ex: Exception =>
          logger.error(s"Could not find project document for id: $projectId", ex)
          recordProcessingError()
          "unknown-project"
      }
  }

  private def getProjectContext(projectId: String): Future[Option[String]] = {
    projectContextsCollection
      .find(equal("projectId", projectId))
      .first()
      .toFutureOption()
      .map(_.map(_.getString("context")))
  }

  private def analyzeModule(moduleId: String, projectId: String): Future[Option[ModuleAnalyzedEvent]] = {
    getModuleDocument(moduleId).flatMap { moduleDocOpt =>
      moduleDocOpt match {
        case Some(moduleDoc) if Option(moduleDoc.getString("analysis")).exists(_.nonEmpty) && 
          Option(moduleDoc.getLong("analysisDate")).exists(_ > (System.currentTimeMillis() - 3600000)) =>
          logger.debug(s"Skipping analysis for module $moduleId as it has recent analysis")
          Future.successful(None)
        case Some(moduleDoc) =>
          for {
            projectName <- getProjectName(moduleDoc.getString("projectId"))
            rootPackages <- getRootPackages(moduleId)
            projectContext <- getProjectContext(projectId)
            analysis <- generateModuleAnalysis(moduleId, moduleDoc.getString("moduleName"), rootPackages, projectName, projectContext)
            _ <- updateModuleAnalysis(moduleId, analysis)
          } yield {
            modulesAnalyzed.increment()
            logger.info(s"Successfully analyzed module ${moduleId}")
            javaModulesCollection.updateOne(
              equal("_id", new ObjectId(moduleId)),
              set("analysisDate", System.currentTimeMillis())
            ).toFuture()
            Some(ModuleAnalyzedEvent(
              moduleId = moduleId,
              projectId = projectId,
              analysis = analysis,
              timestamp = System.currentTimeMillis()
            ))
          }
        case None =>
          moduleAnalysisFailures.increment()
          logger.error(s"Failed to analyze module ${moduleId}: no module document found")
          Future.successful(None)
      }
    }
  }

  private def getModuleDocument(moduleId: String): Future[Option[Document]] = {
    javaModulesCollection.find(
      equal("_id", new ObjectId(moduleId))
    ).first().toFutureOption().recover {
      case ex: Exception =>
        logger.error(s"Could not find module document for id: $moduleId", ex)
        recordProcessingError()
        None
    }
  }

  private def getRootPackages(moduleId: String): Future[Seq[Document]] = {
    javaPackagesCollection.find(
      and(
        equal("module_id", moduleId),
        equal("parentPackageId", null)
      )
    ).toFuture()
  }

  private def generateModuleAnalysis(moduleId: String, moduleName: String, packages: Seq[Document], projectName: String, projectContext: Option[String]): Future[String] = {
    val packageAnalyses = packages.map { pkg =>
      s"Package: ${pkg.getString("packageName")}\nAnalysis: ${pkg.getString("analysis")}"
    }
    ollama.analyzeModule(moduleName, projectName, packageAnalyses, projectContext)
  }

  private def updateModuleAnalysis(moduleId: String, analysis: String): Future[Unit] = {
    javaModulesCollection.updateOne(
      equal("_id", new ObjectId(moduleId)),
      set("analysis", analysis)
    ).toFuture().map { result =>
      logger.info(s"Updated analysis for module $moduleId")
    }
  }
}
