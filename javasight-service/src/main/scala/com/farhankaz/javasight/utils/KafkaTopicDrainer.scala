package com.farhankaz.javasight.utils

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Sink
import akka.stream.Materializer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import scala.concurrent.Await
import scala.concurrent.duration._
import com.farhankaz.javasight.utils.ConfigurationLoader
import akka.kafka.Subscriptions
import scala.concurrent.Future
import com.farhankaz.javasight.services.KafkaTopics._
import com.farhankaz.javasight.services._

object KafkaTopicDrainer extends App {
  // Map topics to their corresponding service class names
  val topicToServiceMap = Map(
    ImportProjectCommands.toString -> "ProjectImportService",
    ImportMavenProjectCommands.toString -> "MavenProjectImportService",
    ImportModuleCommands.toString -> "ModuleImportService",
    ScanModuleDirectoryCommands.toString -> "ModuleDirectoryScanService",
    ScanModuleFileCommands.toString -> "ModuleFileScanService",
    AnalyzeFileCommands.toString -> "FileAnalysisService",
    AnalyzePackageCommands.toString -> "PackageAnalysisService",
    ProjectImportedEvents.toString -> "ProjectImportService",
    MavenProjectImportedEvents.toString -> "MavenProjectImportService",
    ModuleImportedEvents.toString -> "ModuleImportService",
    ModuleDirectoryScannedEvents.toString -> "ModuleDirectoryScanService",
    ModuleFileScannedEvents.toString -> "FileAnalysisService",
    FileAnalyzedEvents.toString -> "PackageAnalysisService",
    PackageAnalyzedEvents.toString -> "ModuleAnalysisService",
    PackageDiscoveryEvents.toString -> "PackageAnalysisService",
    ModuleAnalyzedEvents.toString -> "ProjectAnalysisService",
    ProjectAnalyzedEvents.toString -> "ProjectMetricsService",
    ModuleMetricsEvent.toString -> "ModuleMetricsService"
  )

  implicit val system: ActorSystem = ActorSystem("KafkaTopicDrainer")
  implicit val materializer: Materializer = Materializer(system)
  implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher
  
  // Load configuration
  private val config = ConfigurationLoader(args.headOption.getOrElse("local"))
  
  // Create a stream for each topic and collect their completion futures
  val drainFutures = topicToServiceMap.map { case (topic, serviceName) =>
    println(s"Draining messages from topic: $topic using group ID: ${config.getKafkaClientId}-$serviceName")
    
    // Kafka consumer settings with service-specific group ID
    val consumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(config.getKafkaBootstrapServers)
      .withGroupId(s"${config.getKafkaClientId}-$serviceName")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    
    Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic))
      .map { record =>
        println(s"Drained message from topic $topic: offset=${record.offset()}")
      }
      .runWith(Sink.ignore)
  }

  // Wait for all draining operations to complete
  try {
    Await.result(Future.sequence(drainFutures), 60.seconds)
    println("Finished draining topics")
  } catch {
    case e: Exception =>
      println(s"Error while draining topics: ${e.getMessage}")
  } finally {
    // Ensure system is terminated after all operations complete or timeout
    Await.result(system.terminate(), 1.minute)
  }
}
