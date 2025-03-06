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
 * Utility for generating and sending test events to Kafka topics.
 * 
 * This class is primarily used for testing the event-driven processing 
 * pipeline by manually triggering events.
 */
class EventGenerator(config: ConfigurationLoader)(implicit system: ActorSystem, ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Set up Kafka producer
  private val producerSettings = ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
    .withBootstrapServers(config.getKafkaBootstrapServers)
    .withClientId(s"${config.getKafkaClientId}-event-generator")
  
  private val producer = SendProducer(producerSettings)
  
  /**
   * Sends a ProjectAnalyzedEvent to trigger the ProjectLlmContextService.
   * 
   * @param projectId The ID of the project that was analyzed
   * @param analysis Optional analysis text (defaults to empty string)
   * @return A Future that completes when the event is sent
   */
  def sendProjectAnalyzedEvent(projectId: String, analysis: String = ""): Future[Unit] = {
    logger.info(s"Generating ProjectAnalyzedEvent for project $projectId")
    
    try {
      val event = ProjectAnalyzedEvent(
        projectId = projectId,
        analysis = analysis,
        timestamp = System.currentTimeMillis()
      )
      
      val record = new ProducerRecord[Array[Byte], Array[Byte]](
        KafkaTopics.ProjectAnalyzedEvents.toString,
        event.toByteArray
      )
      
      producer.send(record)
        .map { _ =>
          logger.info(s"Successfully sent ProjectAnalyzedEvent for project $projectId")
          () // Explicitly return Unit
        }
        .recover {
          case ex: Exception =>
            logger.error(s"Failed to send ProjectAnalyzedEvent for project $projectId", ex)
            throw ex
        }
    } catch {
      case ex: Exception =>
        logger.error(s"Error creating ProjectAnalyzedEvent for project $projectId", ex)
        Future.failed[Unit](ex)
    }
  }
  
  /**
   * Closes the Kafka producer.
   */
  def close(): Future[Unit] = {
    Future {
      producer.close()
      () // Explicitly return Unit
    }.recover {
      case ex: Exception =>
        logger.error("Error closing Kafka producer", ex)
        throw ex
    }
  }
}

/**
 * Companion object providing a convenient factory method and command-line interface.
 */
object EventGenerator extends App {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Parse command line arguments
  case class Config(
    env: String = "local",
    projectId: String = "",
    analysis: String = ""
  )
  
  val parser = new scopt.OptionParser[Config]("EventGenerator") {
    head("EventGenerator", "1.0")
    
    opt[String]('e', "env")
      .action((x, c) => c.copy(env = x))
      .text("Environment (local, dev, prod)")
    
    opt[String]('p', "project-id")
      .required()
      .action((x, c) => c.copy(projectId = x))
      .text("Project ID (required)")
    
    opt[String]('a', "analysis")
      .action((x, c) => c.copy(analysis = x))
      .text("Analysis text")
    
    help("help").text("Prints this usage text")
  }
  
  // If running as a standalone app
  if (args.nonEmpty) {
    parser.parse(args, Config()) match {
      case Some(config) =>
        implicit val system: ActorSystem = ActorSystem("EventGenerator")
        implicit val ec: ExecutionContext = system.dispatcher
        
        val configLoader = ConfigurationLoader(config.env)
        val eventGenerator = new EventGenerator(configLoader)
        
        logger.info(s"Sending ProjectAnalyzedEvent for project ${config.projectId}")
        
        val result = eventGenerator.sendProjectAnalyzedEvent(config.projectId, config.analysis)
        
        result.onComplete {
          case Success(_) =>
            logger.info("Event sent successfully")
            eventGenerator.close().onComplete(_ => system.terminate())
          case Failure(ex) =>
            logger.error("Failed to send event", ex)
            eventGenerator.close().onComplete(_ => system.terminate())
        }
        
      case None =>
        // Arguments were invalid
        System.exit(1)
    }
  }
  
  /**
   * Creates a new EventGenerator instance.
   */
  def apply(configLoader: ConfigurationLoader)(implicit system: ActorSystem, ec: ExecutionContext): EventGenerator = {
    new EventGenerator(configLoader)
  }
}