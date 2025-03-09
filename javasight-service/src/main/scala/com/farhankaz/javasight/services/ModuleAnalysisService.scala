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
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

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
  
  // Metrics for token counting
  private val moduleAnalysisTokens = metricsRegistry.summary(
    "javasight_module_analysis_tokens",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val totalTokenUsage = metricsRegistry.counter(
    "javasight_token_usage_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env, "usage_type", "module_analysis")
  )
  
  private val javaPackagesCollection = database.getCollection[Document]("packages")
  private val javaModulesCollection = database.getCollection[Document]("modules")
  private val javaFilesCollection = database.getCollection[Document]("files")
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
      .mapAsyncUnordered(5) { case (moduleId, projectId, offset) =>
        val lockKey = s"module-analysis-lock:$moduleId"
        logger.debug(s"Attempting to acquire lock for module $moduleId")
        
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
      .mapAsyncUnordered(3) { messages =>
        val (records, offsets) = messages.unzip
        Source(records)
          .runWith(producerSink)
          .map { result =>
            recordMessageProcessed()
            offsets
          }
      }
      .mapAsyncUnordered(3) { offsets =>
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
      .mapAsyncUnordered(1) { case (msg, moduleId, projectId) =>
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
      .mapAsyncUnordered(5) { case (msg, moduleId, projectId) =>
        val lockKey = s"module-analysis-lock:$moduleId"
        logger.debug(s"Attempting to acquire lock for module $moduleId")
        
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
          logger.trace(s"Received root package analyzed event for module ${event.packageName} - ${event.moduleId} - project ${event.projectId}")
          (msg, event.moduleId, event.projectId)
        } else {
          logger.trace(s"Received non-root package analyzed event for module ${event.packageName}- ${event.moduleId} - project ${event.projectId}") 
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
            // Determine analysis content based on package count
            analysis <- {

                // For modules with multiple packages, perform normal analysis
                generateModuleAnalysis(moduleId, moduleDoc.getString("moduleName"), rootPackages, projectName, projectContext)

            }
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
    logger.debug(s"Starting module analysis for ${moduleName}")
    
    // Create a sequence of futures, each containing analysis for one package and its files
    val packageFutures: Seq[Future[String]] = packages.map { pkg =>
      val packageId = pkg.getObjectId("_id").toString
      val packageName = pkg.getString("packageName")
      val packageAnalysis = Option(pkg.getString("analysis")).getOrElse("")
      
      // Get all files for this package
      javaFilesCollection.find(equal("packageId", packageId)).toFuture().map { files =>
        // Start with the package overview
        val packageSection = s"Package: ${packageName}\nAnalysis: ${packageAnalysis}"
        
        // Add file analyses if any files exist
        if (files.nonEmpty) {
          val fileAnalyses = files.map { file =>
            val filePath = file.getString("filePath")
            val fileName = filePath.split("/").last
            val fileAnalysis = Option(file.getString("shortAnalysis")).getOrElse("")
            s"\n  File: ${fileName}\n  Analysis: ${fileAnalysis}"
          }.mkString("\n")
          
          s"${packageSection}${fileAnalyses}"
        } else {
          packageSection
        }
      }
    }
    
    // Combine all the futures into one future containing all package analyses
    val packageAnalysesFuture: Future[Seq[String]] = Future.sequence(packageFutures)
    
    // Once all package analyses with their files are collected, analyze the module
    packageAnalysesFuture.flatMap { packageAnalyses =>
      logger.debug(s"Collected analyses for ${packageAnalyses.size} packages with their files for module ${moduleName}")
      ollama.analyzeModule(moduleName, projectName, packageAnalyses, projectContext)
    }
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

  private def updateModuleAnalysis(moduleId: String, analysis: String): Future[Unit] = {
    // Count tokens in the analysis
    val analysisTokenCount = countTokens(analysis)
    logger.debug(s"Analysis token count for module $moduleId: $analysisTokenCount")
    
    // Record token metrics
    moduleAnalysisTokens.record(analysisTokenCount)
    totalTokenUsage.increment(analysisTokenCount.toDouble)
    
    javaModulesCollection.updateOne(
      equal("_id", new ObjectId(moduleId)),
      org.mongodb.scala.model.Updates.combine(
        set("analysis", analysis),
        set("analysisTokenCount", analysisTokenCount)
      )
    ).toFuture().map { result =>
      logger.info(s"Updated analysis for module $moduleId")
    }
  }
}
