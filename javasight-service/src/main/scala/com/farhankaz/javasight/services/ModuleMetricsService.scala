package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink}
import com.farhankaz.javasight.model.kafka.ModuleMetricsEvent
import org.mongodb.scala._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Filters._
import io.micrometer.core.instrument.MeterRegistry
import com.farhankaz.javasight.utils.ConfigurationLoader
import akka.stream.ActorAttributes
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import akka.kafka.ConsumerMessage
import org.bson.types.ObjectId

class ModuleMetricsService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ModuleAnalyzedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val metricsCalculated = metricsRegistry.counter(s"${config.env}_javasight_module_metrics_calculated")
  private val metricsFailures = metricsRegistry.counter(s"${config.env}_javasight_module_metrics_failures")
  private val metricsCollection = database.getCollection("modules_metrics")
  private val packageMetricsCollection = database.getCollection("packages_metrics")
  private val modulesCollection = database.getCollection("modules")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .mapAsyncUnordered(5) { msg =>
        val event = ModuleMetricsEvent.parseFrom(msg.record.value())
        processModuleMetrics(event)
          .map { _ =>
            metricsCalculated.increment()
            msg.committableOffset
          }
          .recover { case ex =>
            logger.error(s"Failed to process metrics for module ${event.moduleId}", ex)
            metricsFailures.increment()
            recordProcessingError()
            msg.committableOffset
          }
      }
      .groupedWithin(20, 5.seconds)
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

  private def processModuleMetrics(event: ModuleMetricsEvent): Future[Unit] = {
    logger.info(s"Processing module metrics for module ${event.moduleId}")
    
    // Get the module document to extract analysisTokenCount
    val moduleDocFuture = modulesCollection
      .find(equal("_id", new ObjectId(event.moduleId)))
      .first()
      .toFutureOption()
    
    // Aggregate package metrics
    val pipeline = Seq(
      `match`(equal("moduleId", event.moduleId)),
      group("$moduleId",
        sum("packageCount", 1),
        sum("totalFiles", "$fileCount"),
        sum("totalLinesOfCode", "$linesOfCode"),
        sum("combinedAnalysisTokenCount", "$combinedAnalysisTokenCount"),
        sum("combinedCodeTokenCount", "$codeTokenCount"),
      )
    )

    val packageMetricsFuture = packageMetricsCollection
      .aggregate(pipeline)
      .headOption()
    
    // Combine results from both futures
    for {
      moduleDocOpt <- moduleDocFuture
      packageMetricsOpt <- packageMetricsFuture
      _ <- {
        // Extract the module's own analysisTokenCount, default to 0 if not found
        val moduleAnalysisTokenCount: Int = moduleDocOpt
          .flatMap(doc => Option(doc.getInteger("analysisTokenCount")))
          .map(_.toInt)
          .getOrElse(0)

          
        
        logger.debug(s"Module ${event.moduleId} analysisTokenCount: $moduleAnalysisTokenCount")
        
        packageMetricsOpt match {
          case Some(doc) => {
            // Get packages combined analysis token count
            val packagesCombinedAnalysisTokenCount: Int = doc.getInteger("combinedAnalysisTokenCount", 0)
            
            // Calculate the true combined analysis token count
            val trueCombinedAnalysisTokenCount = moduleAnalysisTokenCount + packagesCombinedAnalysisTokenCount
            
            logger.debug(s"Module ${event.moduleId} combined token count calculation: $moduleAnalysisTokenCount (module) + $packagesCombinedAnalysisTokenCount (packages) = $trueCombinedAnalysisTokenCount")
            
            // Create document with proper types
            val metrics = Document(
              "moduleId" -> event.moduleId,
              "projectId" -> event.projectId,
              "packageCount" -> doc.getInteger("packageCount", 0),
              "fileCount" -> doc.getInteger("totalFiles", 0),
              "linesOfCode" -> doc.getInteger("totalLinesOfCode", 0),
              "combinedAnalysisTokenCount" -> trueCombinedAnalysisTokenCount,
              "combinedCodeTokenCount" -> doc.getInteger("combinedCodeTokenCount", 0),
              "timestamp" -> System.currentTimeMillis()
            )

            metricsCollection.insertOne(metrics).toFuture()
          }
          case None => {
            // No packages found for module, only use module's own analysis token count
            val metrics = Document(
              "moduleId" -> event.moduleId,
              "projectId" -> event.projectId,
              "packageCount" -> 0,
              "fileCount" -> 0,
              "linesOfCode" -> 0,
              "combinedAnalysisTokenCount" -> moduleAnalysisTokenCount,
              "combinedCodeTokenCount" -> 0,
              "timestamp" -> System.currentTimeMillis()
            )
            
            metricsCollection.insertOne(metrics).toFuture()
          }
        }
      }
    } yield ()
  }
} 