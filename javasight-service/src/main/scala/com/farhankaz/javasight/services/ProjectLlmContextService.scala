package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink}
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.{Filters, ReplaceOptions}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import com.farhankaz.javasight.utils.{ConfigurationLoader, RedissonLock}
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import org.bson.types.ObjectId
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.{ProjectAnalyzedEvent, ProjectLlmContextGeneratedEvent}
import scala.concurrent.duration._
import org.mongodb.scala.model.Filters.equal
import akka.kafka.ConsumerMessage
import org.apache.kafka.clients.producer.ProducerRecord
import akka.kafka.scaladsl.SendProducer
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

/**
 * Service that consumes ProjectAnalyzedEvent events and generates a hierarchical
 * markdown representation of the project context for use with LLMs.
 */
class ProjectLlmContextService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase,
    redisLock: RedissonLock
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ProjectAnalyzedEvents,
      config,
      metricsRegistry,
      database
    ) {

  private val contextsGenerated = metricsRegistry.counter(
    "javasight_llm_contexts_generated_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val contextGenerationFailures = metricsRegistry.counter(
    "javasight_llm_context_generation_failures_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  private val contextTokenCounts = metricsRegistry.summary(
    "javasight_llm_context_token_count",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )

  private val projectContextCollection = database.getCollection[Document]("project_llm_contexts")
  private val javaProjectsCollection = database.getCollection[Document]("projects")
  private val javaModulesCollection = database.getCollection[Document]("java_modules")
  private val javaPackagesCollection = database.getCollection[Document]("java_packages")
  private val javaFilesCollection = database.getCollection[Document]("java_files")
  
  private val producer = SendProducer(producerSettings)
  
  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .mapAsync(1) { msg =>
        val event = ProjectAnalyzedEvent.parseFrom(msg.record.value())
        logger.info(s"Received project analyzed event for project ${event.projectId}")
        
        redisLock.withLock(s"llm-context-generation-lock:${event.projectId}") {
          generateAndStoreContext(event.projectId)
            .map { _ =>
              contextsGenerated.increment()
              logger.info(s"Generated LLM context for project ${event.projectId}")
              msg.committableOffset
            }
            .recover { case ex =>
              logger.error(s"Failed to generate LLM context for project ${event.projectId}", ex)
              contextGenerationFailures.increment()
              recordProcessingError()
              msg.committableOffset
            }
        }.recover {
          case ex: RuntimeException if ex.getMessage.contains("Could not acquire lock") =>
            logger.debug(s"Skipping context generation for project ${event.projectId} as it is currently being processed")
            msg.committableOffset
          case ex =>
            logger.error(s"Error during context generation for project ${event.projectId}", ex)
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

  private def generateAndStoreContext(projectId: String): Future[Unit] = {
    for {
      projectOpt <- getProjectDocument(projectId)
      modules <- getModules(projectId)
      moduleContexts <- Future.sequence(modules.map(generateModuleContext))
      markdownContent = generateMarkdownContent(projectOpt, moduleContexts)
      _ <- storeProjectContext(projectId, markdownContent)
      _ <- publishContextGeneratedEvent(projectId)
    } yield ()
  }
  
  private def publishContextGeneratedEvent(projectId: String): Future[Unit] = {
    Future {
      try {
        val event = ProjectLlmContextGeneratedEvent(
          projectId = projectId,
          timestamp = System.currentTimeMillis()
        )
        
        val record = new ProducerRecord[Array[Byte], Array[Byte]](
          KafkaTopics.ProjectLlmContextGeneratedEvents.toString,
          event.toByteArray
        )
        
        logger.info(s"Publishing ProjectLlmContextGeneratedEvent for project $projectId")
        producer.send(record).map(_ => ())
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to publish context generated event for project $projectId", ex)
          throw ex
      }
    }.flatten
  }

  private def getProjectDocument(projectId: String): Future[Option[Document]] = {
    javaProjectsCollection.find(
      equal("_id", new ObjectId(projectId))
    ).first().toFutureOption().recover {
      case ex: Exception =>
        logger.error(s"Could not find project document for id: $projectId", ex)
        None
    }
  }

  private def getModules(projectId: String): Future[Seq[Document]] = {
    javaModulesCollection.find(
      equal("projectId", projectId)
    ).toFuture()
  }

  case class PackageContext(packageDoc: Document, files: Seq[Document])
  case class ModuleContext(moduleDoc: Document, packages: Seq[PackageContext])
  
  private def generateModuleContext(moduleDoc: Document): Future[ModuleContext] = {
    val moduleId = moduleDoc.getObjectId("_id").toString
    for {
      packages <- javaPackagesCollection.find(
        equal("module_id", moduleId)
      ).toFuture()
      packageContexts <- Future.sequence(packages.map { packageDoc =>
        val packageId = packageDoc.getObjectId("_id").toString
        javaFilesCollection.find(
          equal("packageId", packageId)
        ).toFuture().map(files => PackageContext(packageDoc, files))
      })
    } yield ModuleContext(moduleDoc, packageContexts)
  }

  private def generateMarkdownContent(
    projectOpt: Option[Document], 
    moduleContexts: Seq[ModuleContext]
  ): String = {
    val sb = new StringBuilder()
    
    projectOpt match {
      case Some(project) =>
        // Project heading and details
        sb.append(s"# ${project.getString("projectName")} - JavaSight Analysis Context\n\n")
        sb.append(s"## Project: ${project.getString("projectName")}\n\n")
        sb.append(s"${Option(project.getString("analysis")).getOrElse("No analysis available")}\n\n")
      
      case None =>
        sb.append("# JavaSight Analysis Context\n\n")
        sb.append("## Project\n\n")
        sb.append("No project data available\n\n")
    }
    
    // Add each module
    moduleContexts.foreach { moduleContext =>
      val moduleDoc = moduleContext.moduleDoc
      sb.append(s"## Module: ${moduleDoc.getString("moduleName")}\n\n")
      sb.append(s"${Option(moduleDoc.getString("analysis")).getOrElse("No analysis available")}\n\n")
      
      // Add each package in the module
      moduleContext.packages.foreach { packageContext =>
        val packageDoc = packageContext.packageDoc
        sb.append(s"### Package: ${packageDoc.getString("packageName")}\n\n")
        sb.append(s"${Option(packageDoc.getString("analysis")).getOrElse("No analysis available")}\n\n")
        
        // Add each file in the package
        packageContext.files.foreach { fileDoc =>
          val fileName = fileDoc.getString("filePath").split("/").last
          sb.append(s"#### File: $fileName\n\n")
          sb.append(s"${Option(fileDoc.getString("shortAnalysis")).getOrElse("No analysis available")}\n\n")
        }
      }
    }
    
    sb.toString()
  }

  private def countTokens(text: String): Int = {
    try {
      val encodingRegistry = Encodings.newDefaultEncodingRegistry()
      val encoding = encodingRegistry.getEncoding(EncodingType.CL100K_BASE) // Using common encoding for GPT models
      encoding.countTokens(text)
    } catch {
      case ex: Exception =>
        logger.error("Failed to count tokens", ex)
        0 // Return 0 on error
    }
  }

  private def storeProjectContext(projectId: String, markdownContent: String): Future[Unit] = {
    val tokenCount = countTokens(markdownContent)
    
    logger.info(s"Storing LLM context for project $projectId with $tokenCount tokens")
    contextTokenCounts.record(tokenCount)
    
    val document = Document(
      "projectId" -> projectId,
      "context" -> markdownContent,
      "tokenCount" -> tokenCount,
      "generatedAt" -> System.currentTimeMillis()
    )
    
    val options = new ReplaceOptions().upsert(true)
    
    projectContextCollection.replaceOne(
      Filters.eq("projectId", projectId),
      document,
      options
    ).toFuture().map(_ => ())
  }
}