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
  private val metricsCollection = database.getCollection("java_modules_metrics")
  private val packageMetricsCollection = database.getCollection("java_packages_metrics")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .mapAsync(1) { msg =>
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
    
    val pipeline = Seq(
      `match`(equal("moduleId", event.moduleId)),
      group("$moduleId",
        sum("packageCount", 1),
        sum("totalFiles", "$fileCount"),
        sum("totalLinesOfCode", "$linesOfCode")
      )
    )

    packageMetricsCollection
      .aggregate(pipeline)
      .headOption()
      .map {
        case Some(doc) => {
          val metrics = Document(
            "moduleId" -> event.moduleId,
            "projectId" -> event.projectId,
            "packageCount" -> doc.getInteger("packageCount", 0),
            "fileCount" -> doc.getInteger("totalFiles", 0),
            "linesOfCode" -> doc.getInteger("totalLinesOfCode", 0),
            "timestamp" -> System.currentTimeMillis()
          )
          
          metricsCollection.insertOne(metrics).toFuture()
        }
        case None => {
          // No packages found for module, insert zero metrics
          val metrics = Document(
            "moduleId" -> event.moduleId,
            "projectId" -> event.projectId,
            "packageCount" -> 0,
            "fileCount" -> 0,
            "linesOfCode" -> 0,
            "timestamp" -> System.currentTimeMillis()
          )
          
          metricsCollection.insertOne(metrics).toFuture()
        }
      }
      .flatten
      .map(_ => ())
  }
} 