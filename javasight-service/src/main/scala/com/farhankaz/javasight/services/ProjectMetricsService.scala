package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink}
import com.farhankaz.javasight.model.kafka.ProjectAnalyzedEvent
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

class ProjectMetricsService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ProjectAnalyzedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val metricsCalculated = metricsRegistry.counter(s"${config.env}_javasight_project_metrics_calculated")
  private val metricsFailures = metricsRegistry.counter(s"${config.env}_javasight_project_metrics_failures")
  private val metricsCollection = database.getCollection("java_projects_metrics")
  private val moduleMetricsCollection = database.getCollection("java_modules_metrics")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .mapAsync(1) { msg =>
        val event = ProjectAnalyzedEvent.parseFrom(msg.record.value())
        processProjectMetrics(event)
          .map { _ =>
            metricsCalculated.increment()
            msg.committableOffset
          }
          .recover { case ex =>
            logger.error(s"Failed to process metrics for project ${event.projectId}", ex)
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

  private def processProjectMetrics(event: ProjectAnalyzedEvent): Future[Unit] = {
    logger.info(s"Processing project metrics for project ${event.projectId}")
    
    val pipeline = Seq(
      `match`(equal("projectId", event.projectId)),
      group("$projectId",
        sum("moduleCount", 1),
        sum("packageCount", "$packageCount"),
        sum("fileCount", "$fileCount"),
        sum("linesOfCode", "$linesOfCode")
      )
    )

    moduleMetricsCollection
      .aggregate(pipeline)
      .headOption()
      .map {
        case Some(doc) => {
          val metrics = Document(
            "projectId" -> event.projectId,
            "moduleCount" -> doc.getInteger("moduleCount", 0),
            "packageCount" -> doc.getInteger("packageCount", 0),
            "fileCount" -> doc.getInteger("fileCount", 0),
            "linesOfCode" -> doc.getInteger("linesOfCode", 0),
            "timestamp" -> System.currentTimeMillis()
          )
          
          metricsCollection.insertOne(metrics).toFuture()
        }
        case None => {
          // No modules found for project, insert zero metrics
          val metrics = Document(
            "projectId" -> event.projectId,
            "moduleCount" -> 0,
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