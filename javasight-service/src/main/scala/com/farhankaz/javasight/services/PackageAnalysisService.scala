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
import com.farhankaz.javasight.utils.{ConfigurationLoader, RedissonLock, Ollama}
import com.farhankaz.javasight.model.protobuf.{ImportModule, ImportFile}
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import org.bson.types.ObjectId
import akka.kafka.ProducerMessage
import akka.NotUsed
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.{ScanModuleFileCommand, ScanModuleDirectoryCommand, ModuleFileScannedEvent, FileAnalyzedEvent, PackageAnalyzedEvent, PackageDiscoveryEvent}
import spray.json._
import DefaultJsonProtocol._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.ConnectionPoolSettings
import scala.concurrent.duration._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates.set
import akka.kafka.ConsumerMessage
import java.lang
import scala.collection.JavaConverters._
import com.farhankaz.javasight.utils.CodeAnalyzer

class PackageAnalysisService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase,
    redisLock: RedissonLock,
    ollama: CodeAnalyzer
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.FileAnalyzedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val packagesAnalyzed = metricsRegistry.counter(s"${config.env}_javasight_packageanalysis_success")
  private val packageAnalysisFailures = metricsRegistry.counter(s"${config.env}_javasight_packageanalysis_failures")
  
  // Metrics for token counting
  private val packageAnalysisTokens = metricsRegistry.summary(
    "javasight_package_analysis_tokens",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val totalTokenUsage = metricsRegistry.counter(
    "javasight_token_usage_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env, "usage_type", "package_analysis")
  )
  private val javaPackagesCollection = database.getCollection[Document]("packages")
  private val javaFilesCollection = database.getCollection[Document]("files")
  private val projectContextsCollection = database.getCollection[Document]("project_contexts")
  

  protected override def startService(): Consumer.DrainingControl[Done] = {
    val producerSink = Producer.plainSink(producerSettings)

    fileAnalyzeEventsSource()
      .merge(packageAnalyzeEventsSource())
      .merge(packageDiscoveryEventsSource())
      .mapAsyncUnordered(5) { case (msg: ConsumerMessage.CommittableMessage[Array[Byte], Array[Byte]], packageId: String, projectId: String) =>
        logger.debug(s"Received package analyze event for package ${packageId}")
        allChildrenPackagesAndFilesHaveBeenAnalyzed(packageId).map { result =>
          if (!result) {
            logger.debug(s"Skipping package $packageId as not all children are analyzed")
          }
          (msg, packageId, projectId, result)
        }.recover { case ex =>
          logger.error(s"Error checking package analysis status for $packageId", ex)
          recordProcessingError()
          (msg, packageId, projectId, false)
        }
      }
      .filter { case (msg, packageId, projectId, result) => result }
      .map { case (msg, packageId, projectId, _) => (msg, packageId, projectId) }
      .mapAsync(1) { case (msg: ConsumerMessage.CommittableMessage[Array[Byte], Array[Byte]], packageId: String, projectId: String) =>
        
      analyzePackage(packageId, projectId)
        .map(eventOpt => (eventOpt, msg.committableOffset))
      }
      .collect { case (Some(event: PackageAnalyzedEvent), offset) =>
        (
          new ProducerRecord[Array[Byte], Array[Byte]](
            KafkaTopics.PackageAnalyzedEvents.toString(),
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

  private def packageDiscoveryEventsSource(): Source[(ConsumerMessage.CommittableMessage[Array[Byte],Array[Byte]], String, String),Consumer.Control] = {
    Consumer
      .committableSource(consumerSettings.withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}-discovery"), Subscriptions.topics(KafkaTopics.PackageDiscoveryEvents.toString()))
      .map { msg =>
        val event = PackageDiscoveryEvent.parseFrom(msg.record.value())
        logger.debug(s"Received package discovery event for package ${event.packageId}")
        // Update childrenDiscovered flag when receiving discovery event
        javaPackagesCollection.updateOne(
          equal("_id", new ObjectId(event.packageId)),
          set("childrenDiscovered", true)
        ).toFuture().onComplete {
          case Success(_) => logger.debug(s"Updated childrenDiscovered for package ${event.packageId}")
          case Failure(ex) => logger.error(s"Failed to update childrenDiscovered for package ${event.packageId}", ex)
        }
        (msg, event.packageId, event.projectId)
      }
  }

  private def packageAnalyzeEventsSource(): Source[(ConsumerMessage.CommittableMessage[Array[Byte],Array[Byte]], String, String),Consumer.Control] = {
    Consumer
      .committableSource(consumerSettings.withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}-package"), Subscriptions.topics(KafkaTopics.PackageAnalyzedEvents.toString()))
      .map { msg =>
        val event = PackageAnalyzedEvent.parseFrom(msg.record.value())
        logger.debug(s"Receaved package analyze event for package ${event.packageId} with parent ${event.parentPackageId}")
        (msg, event.parentPackageId, event.projectId)
      }
      .collect {
        case (msg, Some(parentPackageId), projectId) => 
          logger.debug(s"Received package analyze event from child of package ${parentPackageId}")
          (msg, parentPackageId, projectId)
      }
  }

  private def fileAnalyzeEventsSource(): Source[(ConsumerMessage.CommittableMessage[Array[Byte],Array[Byte]], String, String),Consumer.Control] = 
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        val event = FileAnalyzedEvent.parseFrom(msg.record.value())
        (msg, event.packageId, event.projectId)
      }
      .collect { 
        case (msg, Some(packageId), projectId) => (msg, packageId, projectId)
      }

  private def getProjectContext(projectId: String): Future[Option[String]] = {
    projectContextsCollection
      .find(equal("projectId", projectId))
      .first()
      .toFutureOption()
      .map(_.map(_.getString("context")))
  }

  private def analyzePackage(packageId: String, projectId: String): Future[Option[PackageAnalyzedEvent]] = {
    getPackageDocument(packageId).flatMap { packageDocOpt =>
      packageDocOpt match {
        case Some(packageDoc) if Option(packageDoc.getString("analysis")).exists(_.nonEmpty) && 
            Option(packageDoc.getLong("analysisDate")).exists(_ > System.currentTimeMillis() - (24 * 60 * 60 * 1000)) =>
          logger.debug(s"Skipping analysis for package $packageId as it has recent analysis")
          Future.successful(None)
        case Some(packageDoc) =>
          val packageName = packageDoc.getString("packageName")
          val moduleId = packageDoc.getString("module_id")
          for {
            files <- getPackageFiles(moduleId, packageName)
            projectContext <- getProjectContext(projectId)
            // Determine analysis content based on file count
            analysis <- {
              if (files.size <= 1) {
                // For packages with only a single file, set empty analysis
                logger.info(s"Package $packageName has only ${files.size} file(s), setting empty analysis")
                Future.successful("")
              } else {
                // For packages with multiple files, perform normal analysis
                generatePackageAnalysis(packageId, packageName, files, projectContext)
              }
            }
            _ <- updatePackageAnalysis(packageId, analysis)
          } yield {
            packagesAnalyzed.increment()
            logger.info(s"Successfully analyzed package ${packageId} with name ${packageName}")
            javaPackagesCollection.updateOne(
              equal("_id", new ObjectId(packageId)),
              set("analysisDate", System.currentTimeMillis())
            ).toFuture()
            Some(PackageAnalyzedEvent(
              packageId = packageId,
              packageName = packageName,
              projectId = projectId,
              moduleId = moduleId,
              parentPackageId = Option(packageDoc.getString("parentPackageId")),
              timestamp = System.currentTimeMillis()
            ))
          }
        case None =>
          packageAnalysisFailures.increment()
          logger.error(s"Failed to analyze package ${packageId}: no package document found")
          Future.successful(None)
      }
    }
  }

  private def getPackageDocument(packageId: String): Future[Option[Document]] = {
    javaPackagesCollection.find(
      equal("_id", new ObjectId(packageId))
    ).first().toFutureOption().recover {
      case ex: Exception =>
        logger.error(s"Could not find package document for id: $packageId", ex)
        recordProcessingError()
        None
    }
  }

  private def getPackageFiles(moduleId: String, packageName: String): Future[Seq[Document]] = {
    javaFilesCollection.find(
      and(
        equal("moduleId", moduleId),
        equal("packageName", packageName)
      )
    ).toFuture()
  }

  private def generatePackageAnalysis(packageId: String, packageName: String, files: Seq[Document], projectContext: Option[String]): Future[String] = {
    logger.info("Analyzing package: " + packageName)
    val fileAnalyses = files.map { file =>
      s"File: ${file.getString("filePath")}\nAnalysis: ${file.getString("shortAnalysis")}"
    }
    logger.info(s"Calling Ollama Analysis prompt for package $packageName")
    ollama.analyzePackage(packageName, fileAnalyses, projectContext)
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

  private def updatePackageAnalysis(packageId: String, analysis: String): Future[Unit] = {
    // Count tokens in the analysis
    val analysisTokenCount = countTokens(analysis)
    logger.debug(s"Analysis token count for package $packageId: $analysisTokenCount")
    
    // Record token metrics
    packageAnalysisTokens.record(analysisTokenCount)
    totalTokenUsage.increment(analysisTokenCount.toDouble)
    
    javaPackagesCollection.updateOne(
      equal("_id", new ObjectId(packageId)),
      org.mongodb.scala.model.Updates.combine(
        set("analysis", analysis),
        set("analysisTokenCount", analysisTokenCount)
      )
    ).toFuture().map(_ => () )
  }

  def allChildrenPackagesAndFilesHaveBeenAnalyzed(packageId: String): Future[Boolean] = {
    val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
    
    val currentPackageFuture: Future[Option[Document]] = javaPackagesCollection
      .find(equal("_id", new ObjectId(packageId)))
      .first()
      .toFutureOption()
      
    val childrenPackagesFuture: Future[Long] = javaPackagesCollection
      .countDocuments(and(equal("parentPackageId", packageId), not(exists("analysis"))))
      .toFuture()
      
    val packageFilesFuture: Future[Long] = javaFilesCollection
      .countDocuments(and(equal("packageId", packageId), not(exists("shortAnalysis"))))
      .toFuture()
      
    for {
      currentPackageOpt <- currentPackageFuture
      unprocessedChildrenPackages <- childrenPackagesFuture
      unprocessedPackageFiles <- packageFilesFuture
    } yield {
      currentPackageOpt match {
        case Some(currentPackage) =>
          // Check if all children have been discovered
          val childrenDiscovered: Boolean = currentPackage.getBoolean("childrenDiscovered", false)
          logger.debug(s"childrenDiscovered: $childrenDiscovered, unprocessedChildrenPackages: $unprocessedChildrenPackages, unprocessedPackageFiles: $unprocessedPackageFiles")
          // All conditions must be met
          childrenDiscovered && unprocessedChildrenPackages <= 0 && unprocessedPackageFiles <= 0
        case None =>
          logger.error(s"Package document not found for id: $packageId")
          false
      }
    }
  }
}
