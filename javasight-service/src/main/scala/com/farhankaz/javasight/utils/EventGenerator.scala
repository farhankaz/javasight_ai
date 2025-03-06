package com.farhankaz.javasight.utils

import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.SendProducer
import com.farhankaz.javasight.model.kafka.ProjectAnalyzedEvent
import com.farhankaz.javasight.services.KafkaTopics
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * A utility class to generate test events for the Kafka-based event processing system.
 * This can be used to test the event handling capabilities of various services,
 * particularly the ProjectLlmContextService.
 */
class EventGenerator(config: ConfigurationLoader)(implicit system: ActorSystem, ec: ExecutionContext) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Configure Kafka producer settings
  private val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(config.getKafkaBootstrapServers)
    .withClientId(s"${config.getKafkaClientId}-event-generator")
  
  // Create a Kafka producer
  private val producer = SendProducer(producerSettings)
  
  /**
   * Sends a ProjectAnalyzedEvent to the appropriate Kafka topic to trigger the
   * ProjectLlmContextService processing.
   *
   * @param projectId The ID of the project that has been analyzed
   * @param analysis Optional analysis text (not used in the current implementation)
   * @return A Future representing the completion of the send operation
   */
  def sendProjectAnalyzedEvent(projectId: String, analysis: String = ""): Future[Unit] = {
    try {
      logger.info(s"Generating ProjectAnalyzedEvent for project ID: $projectId")
      
      // Create the event with current timestamp
      val event = ProjectAnalyzedEvent(
        projectId = projectId,
        timestamp = System.currentTimeMillis()
      )
      
      // Create a Kafka record targeted to the ProjectAnalyzedEvents topic
      val record = new ProducerRecord[Array[Byte], Array[Byte]](
        KafkaTopics.ProjectAnalyzedEvents.toString, 
        event.toByteArray
      )
      
      // Send the record to Kafka
      producer.send(record).map { recordMetadata =>
        logger.info(s"Successfully sent ProjectAnalyzedEvent for project $projectId " +
          s"to topic ${recordMetadata.topic()} partition ${recordMetadata.partition()} " +
          s"offset ${recordMetadata.offset()}")
      }.recover {
        case ex: Exception =>
          logger.error(s"Failed to send ProjectAnalyzedEvent for project $projectId", ex)
          throw ex
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error creating ProjectAnalyzedEvent for project $projectId", ex)
        Future.failed(ex)
    }
  }
  
  /**
   * Closes the Kafka producer when done to free up resources
   */
  def shutdown(): Future[Unit] = {
    Future {
      logger.info("Shutting down EventGenerator")
      producer.close()
    }
  }
  
  /**
   * Alias for shutdown() to maintain compatibility with existing code
   */
  def close(): Future[Unit] = shutdown()
}

/**
 * Companion object with factory method
 */
object EventGenerator {
  def apply(config: ConfigurationLoader)(implicit system: ActorSystem, ec: ExecutionContext): EventGenerator = {
    new EventGenerator(config)
  }
}