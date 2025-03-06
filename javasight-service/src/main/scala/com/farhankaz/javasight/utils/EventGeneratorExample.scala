package com.farhankaz.javasight.utils

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Example script for using the EventGenerator utility.
 * 
 * This demonstrates how to send a ProjectAnalyzedEvent to trigger the ProjectLlmContextService.
 * 
 * Usage:
 *   sbt "runMain com.farhankaz.javasight.utils.EventGeneratorExample [projectId] [analysis]"
 */
object EventGeneratorExample {
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    // Parse command line arguments
    val projectId = if (args.length > 0) args(0) else {
      println("No project ID provided. Usage: EventGeneratorExample [projectId] [analysis]")
      return
    }
    
    val analysis = if (args.length > 1) args(1) else ""
    
    implicit val system: ActorSystem = ActorSystem("EventGeneratorExample")
    implicit val ec: ExecutionContext = system.dispatcher
    
    val configLoader = ConfigurationLoader()
    val eventGenerator = new EventGenerator(configLoader)
    
    logger.info(s"Sending ProjectAnalyzedEvent for project $projectId")
    
    // Send the event
    val result = eventGenerator.sendProjectAnalyzedEvent(projectId, analysis)
    
    // Wait for completion
    result.onComplete {
      case Success(_) =>
        logger.info("Event sent successfully")
        eventGenerator.close().onComplete(_ => system.terminate())
      case Failure(ex) =>
        logger.error("Failed to send event", ex)
        eventGenerator.close().onComplete(_ => system.terminate())
    }
    
    // Wait for termination to complete
    Await.result(system.whenTerminated, 30.seconds)
    logger.info("Application terminated")
  }
}