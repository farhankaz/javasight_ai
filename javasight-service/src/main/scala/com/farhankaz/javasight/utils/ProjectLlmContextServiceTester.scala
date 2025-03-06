package com.farhankaz.javasight.utils

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * A utility script for testing the ProjectLlmContextService by generating ProjectAnalyzedEvent.
 * 
 * This tool can be used to fire events on the KafkaTopics.ProjectAnalyzedEvents topic
 * to trigger the context generation process in ProjectLlmContextService.
 * 
 * Usage:
 *   sbt "runMain com.farhankaz.javasight.utils.ProjectLlmContextServiceTester [projectId]"
 * 
 * Example:
 *   sbt "runMain com.farhankaz.javasight.utils.ProjectLlmContextServiceTester 6780882ac8086c7190fa1027"
 * 
 * This will fire a ProjectAnalyzedEvent for the specified project ID, which will trigger
 * the ProjectLlmContextService to generate a context for the project and store it in MongoDB.
 */
object ProjectLlmContextServiceTester {
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    // Parse command line arguments
    val projectId = if (args.length > 0) args(0) else {
      println("""
        |ERROR: No project ID provided.
        |
        |Usage: ProjectLlmContextServiceTester [projectId]
        |
        |Example: sbt "runMain com.farhankaz.javasight.utils.ProjectLlmContextServiceTester 6780882ac8086c7190fa1027"
        |
        |Note: The project ID should be a valid ObjectID from the MongoDB 'projects' collection.
        |      You can find valid project IDs by querying the collection:
        |      db.projects.find({}, {_id: 1, projectName: 1})
        |""".stripMargin)
      return
    }
    
    // Set up actor system and execution context
    implicit val system: ActorSystem = ActorSystem("ProjectLlmContextServiceTester")
    implicit val ec: ExecutionContext = system.dispatcher
    
    // Create configuration and event generator
    val configLoader = ConfigurationLoader()
    val eventGenerator = new EventGenerator(configLoader)
    
    logger.info(s"Sending ProjectAnalyzedEvent for project $projectId")
    println(s"Sending ProjectAnalyzedEvent for project $projectId...")
    
    // Send the event to trigger ProjectLlmContextService
    val result = eventGenerator.sendProjectAnalyzedEvent(projectId)
    
    // Handle completion
    result.onComplete {
      case Success(_) =>
        logger.info(s"Event sent successfully! ProjectLlmContextService should now process project $projectId")
        println("✅ Success: Event sent successfully!")
        println("The ProjectLlmContextService should now be processing the project.")
        println("Check the service logs to monitor progress.")
        eventGenerator.close().onComplete(_ => system.terminate())
        
      case Failure(ex) =>
        logger.error("Failed to send event", ex)
        println(s"❌ Error: Failed to send event: ${ex.getMessage}")
        println("Please check that Kafka is running and the service is properly configured.")
        eventGenerator.close().onComplete(_ => system.terminate())
    }
    
    // Wait for termination to complete
    Await.result(system.whenTerminated, 30.seconds)
    println("Application terminated.")
  }
}