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
import org.bson.types.ObjectId
import org.mongodb.scala.model.Updates._

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
  private val projectsCollection = database.getCollection("projects")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .mapAsyncUnordered(5) { msg =>
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
    
    // Get the project document to extract analysisTokenCount
    val projectDocFuture = projectsCollection
      .find(equal("_id", new ObjectId(event.projectId)))
      .first()
      .toFutureOption()
    
    // Update the pipeline to include token counts
    val pipeline = Seq(
      `match`(equal("projectId", event.projectId)),
      group("$projectId",
        sum("moduleCount", 1),
        sum("packageCount", "$packageCount"),
        sum("fileCount", "$fileCount"),
        sum("linesOfCode", "$linesOfCode"),
        sum("modulesCombinedAnalysisTokenCount", "$combinedAnalysisTokenCount"),
        sum("combinedCodeTokenCount", "$combinedCodeTokenCount")
      )
    )

    // Process both futures
    for {
      projectDocOpt <- projectDocFuture
      moduleMetricsOpt <- moduleMetricsCollection.aggregate(pipeline).headOption()
      result <- {
        // Extract the project's own analysisTokenCount, default to 0 if not found
        val projectAnalysisTokenCount: Int = projectDocOpt
          .flatMap(doc => Option(doc.getInteger("analysisTokenCount")))
          .map(_.toInt)
          .getOrElse(0)
        
        logger.debug(s"Project ${event.projectId} analysisTokenCount: $projectAnalysisTokenCount")
        
        moduleMetricsOpt match {
          case Some(doc) => {
            // Get modules' combined analysis token count
            val modulesCombinedAnalysisTokenCount: Int = doc.getInteger("modulesCombinedAnalysisTokenCount", 0)
            
            // Calculate the true combined analysis token count
            val trueCombinedAnalysisTokenCount = projectAnalysisTokenCount + modulesCombinedAnalysisTokenCount
            
            logger.debug(s"Project ${event.projectId} combined token count calculation: " +
              s"$projectAnalysisTokenCount (project) + $modulesCombinedAnalysisTokenCount (modules) = $trueCombinedAnalysisTokenCount")
            
            val metrics = Document(
              "projectId" -> event.projectId,
              "moduleCount" -> doc.getInteger("moduleCount", 0),
              "packageCount" -> doc.getInteger("packageCount", 0),
              "fileCount" -> doc.getInteger("fileCount", 0),
              "linesOfCode" -> doc.getInteger("linesOfCode", 0),
              "combinedAnalysisTokenCount" -> trueCombinedAnalysisTokenCount,
              "combinedCodeTokenCount" -> doc.getInteger("combinedCodeTokenCount", 0),
              "timestamp" -> System.currentTimeMillis()
            )
            
            // Insert metrics document and update the project with combined metrics
            metricsCollection.insertOne(metrics).toFuture()
            
          }
          case None => {
            // No modules found for project, use only project's own analysis token count
            val metrics = Document(
              "projectId" -> event.projectId,
              "moduleCount" -> 0,
              "packageCount" -> 0,
              "fileCount" -> 0,
              "linesOfCode" -> 0,
              "combinedAnalysisTokenCount" -> projectAnalysisTokenCount,
              "combinedCodeTokenCount" -> 0,
              "timestamp" -> System.currentTimeMillis()
            )
            
            // Insert metrics document
            metricsCollection.insertOne(metrics).toFuture()
          }
        }
      }
    } yield ()
  }
} 