
package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink}
import com.mongodb.client.model.{Aggregates, Filters}
import com.farhankaz.javasight.model.kafka.PackageAnalyzedEvent
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.mongodb.scala._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.{MongoClient, MongoDatabase}
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.{Aggregates => MongoAggregates}
import io.micrometer.core.instrument.MeterRegistry
import com.farhankaz.javasight.utils.ConfigurationLoader
import akka.stream.ActorAttributes

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import akka.kafka.ConsumerMessage
import org.bson.types.ObjectId

class PackageMetricsService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.PackageAnalyzedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val metricsCalculated = metricsRegistry.counter(s"${config.env}_javasight_package_metrics_calculated")
  private val metricsFailures = metricsRegistry.counter(s"${config.env}_javasight_package_metrics_failures")
  private val metricsCollection = database.getCollection("packages_metrics")
  private val filesCollection = database.getCollection("files")
  private val packagesCollection = database.getCollection("packages")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .mapAsyncUnordered(5) { msg =>
        val event = PackageAnalyzedEvent.parseFrom(msg.record.value())
        logger.info(s"Processing package metrics for package ${event.packageName}")
        processPackageMetrics(event)
          .map { _ =>
            metricsCalculated.increment()
            msg.committableOffset
          }
          .recover { case ex =>
            logger.error(s"Failed to process metrics for package ${event.packageName}", ex)
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

  private def processPackageMetrics(event: PackageAnalyzedEvent): Future[Unit] = {
    logger.info(s"Processing package metrics for package ${event.packageId}")
    
    // Pipeline to calculate file metrics (count, lines of code, and analysis token count)
    val filesPipeline = Seq(
      MongoAggregates.`match`(equal("packageId", event.packageId)),
      MongoAggregates.group("$packageId",
        sum("fileCount", 1),
        sum("linesOfCode", "$linesOfCode"),
        sum("filesAnalysisTokenCount", "$analysisTokenCount"),
        sum("codeTokenCount", "$codeTokenCount")
      )
    )

    // Get package's own analysisTokenCount
    val packageFuture = packagesCollection
      .find(equal("_id", new ObjectId(event.packageId)))
      .first()
      .toFuture()
      
    // Get aggregate metrics for files in the package
    val fileMetricsFuture = filesCollection
      .aggregate(filesPipeline)
      .headOption()
      
    // Combine the results
    for {
      packageDoc <- packageFuture
      fileMetricsOption <- fileMetricsFuture
      _ <- {
        // Extract package analysisTokenCount, default to 0 if not found
        val packageAnalysisTokenCountOpt: Option[Int] = Option(packageDoc)
          .flatMap(doc => Option(doc.getInteger("analysisTokenCount")))
          
        val packageAnalysisTokenCount: Int = packageAnalysisTokenCountOpt.getOrElse(0)
        
        val insertFuture = fileMetricsOption match {
          case Some(fileMetrics) => {
            // Extract file metrics
            val fileCount = fileMetrics.getInteger("fileCount", 0)
            val linesOfCode = fileMetrics.getInteger("linesOfCode", 0)
            val filesAnalysisTokenCount: Int = fileMetrics.getInteger("filesAnalysisTokenCount", 0)
            val codeTokenCount: Int = fileMetrics.getInteger("codeTokenCount", 0)
            
            // Calculate combined analysis token count
            val combinedAnalysisTokenCount = packageAnalysisTokenCount + filesAnalysisTokenCount
            
            val metrics = Document(
              "packageId" -> event.packageId,
              "moduleId" -> event.moduleId,
              "projectId" -> event.projectId,
              "fileCount" -> fileCount,
              "linesOfCode" -> linesOfCode,
              "packageAnalysisTokenCount" -> packageAnalysisTokenCount,
              "combinedAnalysisTokenCount" -> combinedAnalysisTokenCount,
              "codeTokenCount" -> codeTokenCount,
              "timestamp" -> System.currentTimeMillis()
            )
            
            metricsCollection.insertOne(metrics).toFuture()
          }
          case None => {
            // No files found for package, insert metrics with only package analysis tokens
            val metrics = Document(
              "packageId" -> event.packageId,
              "moduleId" -> event.moduleId,
              "projectId" -> event.projectId,
              "fileCount" -> 0,
              "linesOfCode" -> 0,
              "packageAnalysisTokenCount" -> packageAnalysisTokenCount,
              "combinedAnalysisTokenCount" -> packageAnalysisTokenCount,
              "codeTokenCount" -> 0,
              "timestamp" -> System.currentTimeMillis()
            )
            
            metricsCollection.insertOne(metrics).toFuture()
          }
        }
        
        insertFuture
      }
    } yield ()
  }
} 