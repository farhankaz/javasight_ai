package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.ActorAttributes
import org.apache.kafka.clients.producer.ProducerRecord
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.model.{Filters, Updates}
import org.mongodb.scala.model.UpdateOptions
import io.micrometer.core.instrument.MeterRegistry
import org.bson.types.ObjectId
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import java.nio.file.{Files, Paths}
import scala.sys.process._
import com.farhankaz.javasight.utils.ConfigurationLoader
import com.farhankaz.javasight.model.kafka.{ImportGithubProjectCommand, Module, ProjectImportedEvent}
import scala.xml.XML
import scala.util.matching.Regex

class GithubProjectImportService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ImportGithubProjectCommands,
      config,
      metricsRegistry,
      database
    ) {

  private val projects = database.getCollection[Document]("projects")
  private val projectContexts = database.getCollection[Document]("project_contexts")
  private val importStatus = database.getCollection[Document]("project_import_status")
  private val projectsImportedCounter = metricsRegistry.counter("github_projects_imported_total")
  
  // Directory where GitHub repositories will be cloned
  private val cloneBaseDir = config.getString("javasight.github.repo-temp-dir")
  
  // Simple regex patterns for JSON extraction
  private val projectNamePattern = """"project_name"\s*:\s*"([^"]+)"""".r
  private val githubUrlPattern = """"github_url"\s*:\s*"([^"]+)"""".r
  private val projectContextPattern = """"project_context"\s*:\s*"([^"]*?)"""".r
  private val timestampPattern = """"timestamp"\s*:\s*"(\d+)"""".r
  private val importIdPattern = """"import_id"\s*:\s*"([^"]+)"""".r
  
  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        recordMessageProcessed()
        
        // Parse JSON string manually without external libraries
        val jsonStr = new String(msg.record.value(), "UTF-8")
        logger.info(s"Received JSON: $jsonStr")
        
        try {
          // Extract fields with regex for simplicity
          val projectName = extract(projectNamePattern, jsonStr).getOrElse(
            throw new Exception("Missing required field: project_name"))
            
          val githubUrl = extract(githubUrlPattern, jsonStr).getOrElse(
            throw new Exception("Missing required field: github_url"))
            
          val projectContext = extract(projectContextPattern, jsonStr).getOrElse("")
          
          val timestamp = extract(timestampPattern, jsonStr).map(_.toLong).getOrElse(
            System.currentTimeMillis())
            
          val importId = extract(importIdPattern, jsonStr)
          
          // Log parsed data
          logger.info(s"Parsed GitHub import command: project=$projectName, url=$githubUrl")
          if (importId.isDefined) {
            logger.info(s"Import ID from message: ${importId.get}")
            
            // If import ID was provided, create the import status record
            importId.foreach { id =>
              try {
                val objectId = new ObjectId(id)
                updateImportStatus(
                  objectId,
                  "in_progress",
                  "Processing import...",
                  5
                )
              } catch {
                case e: Exception => logger.warn(s"Invalid import ID format: $id", e)
              }
            }
          }
          
          // Create command object
          ImportGithubProjectCommand(
            projectName = projectName,
            githubUrl = githubUrl,
            projectContext = projectContext,
            timestamp = timestamp
          )
        } catch {
          case e: Exception =>
            logger.error(s"Failed to parse JSON message: ${e.getMessage}", e)
            throw e
        }
      }
      .mapAsync(1)(processGithubImport)
      .map { case (projectId, command, clonePath) =>
        new ProducerRecord[Array[Byte], Array[Byte]](
          KafkaTopics.ProjectImportedEvents.toString(),
          ProjectImportedEvent(
            projectId.toHexString,
            command.projectName,
            clonePath,
            parseProjectModules(projectId.toHexString, clonePath),
            System.currentTimeMillis()
          ).toByteArray
        )
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Producer.plainSink(producerSettings))(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()
  }
  
  // Helper to extract values with regex
  private def extract(pattern: Regex, json: String): Option[String] = {
    pattern.findFirstMatchIn(json).map(_.group(1))
  }

  private def processGithubImport(command: ImportGithubProjectCommand): Future[(ObjectId, ImportGithubProjectCommand, String)] = {
    // Create a new ObjectId for the import
    val importId = new ObjectId()
    
    logger.info(s"Processing GitHub import for ${command.projectName} from ${command.githubUrl}, import ID: ${importId.toHexString}")
    
    // Update status to started
    updateImportStatus(
      importId,
      "in_progress",
      "Initiating GitHub repository clone",
      10
    ).flatMap { _ =>
      // Create project ID
      val projectId = new ObjectId()
      val clonePath = s"$cloneBaseDir/${projectId.toHexString}"
      
      // Create directory if it doesn't exist
      Files.createDirectories(Paths.get(cloneBaseDir))
      
      // Clone GitHub repository
      cloneRepository(command.githubUrl, clonePath, importId)
        .flatMap { _ =>
          // Validate Maven project
          validateMavenProject(clonePath, importId)
            .flatMap { _ =>
              // Create MongoDB records
              createProjectRecords(projectId, command, clonePath, importId)
                .map { _ =>
                  // Return data needed for the next step
                  (projectId, command, clonePath)
                }
            }
        }
        .recoverWith { case ex =>
          // Update status on failure
          updateImportStatus(
            importId,
            "failed",
            s"Import failed: ${ex.getMessage}",
            0,
            Some(ex.getMessage)
          ).flatMap { _ =>
            // Clean up any partial clone
            Try(s"rm -rf $clonePath".!).recover {
              case e => logger.warn(s"Failed to clean up directory $clonePath: ${e.getMessage}")
            }
            
            // Re-throw the exception to stop processing
            Future.failed(ex)
          }
        }
    }
  }

  private def cloneRepository(githubUrl: String, clonePath: String, importId: ObjectId): Future[Unit] = {
    Future {
      updateImportStatus(importId, "in_progress", "Cloning repository...", 20)
      
      // Execute git clone command
      val cloneCmd = s"git clone $githubUrl $clonePath"
      logger.info(s"Executing: $cloneCmd")
      
      val cloneResult = cloneCmd.!(ProcessLogger(
        out => logger.debug(s"Clone output: $out"),
        err => logger.warn(s"Clone error: $err")
      ))
      
      if (cloneResult != 0) {
        throw new RuntimeException(s"Git clone failed with exit code $cloneResult")
      }
      
      // Update status after successful clone
      updateImportStatus(importId, "in_progress", "Repository cloned successfully", 40)
    }.flatMap(identity)
  }

  private def validateMavenProject(path: String, importId: ObjectId): Future[Unit] = {
    Future {
      updateImportStatus(importId, "in_progress", "Validating Maven project structure...", 50)
      
      // Check for pom.xml file
      val pomFile = Paths.get(path, "pom.xml").toFile
      if (!pomFile.exists() || !pomFile.isFile) {
        throw new RuntimeException("Not a valid Maven project - pom.xml not found")
      }
      
      // Try to parse pom.xml to verify it's valid XML
      try {
        XML.loadFile(pomFile)
        updateImportStatus(importId, "in_progress", "Project validated successfully", 60)
      } catch {
        case ex: Exception => 
          throw new RuntimeException(s"Invalid pom.xml file: ${ex.getMessage}")
      }
    }.flatMap(identity)
  }

  private def createProjectRecords(
    projectId: ObjectId,
    command: ImportGithubProjectCommand,
    clonePath: String,
    importId: ObjectId
  ): Future[Unit] = {
    updateImportStatus(importId, "in_progress", "Importing project...", 70)
    
    // Check for README.md content
    val readmeContent = readReadmeFile(clonePath)
    readmeContent.foreach(content =>
      logger.info(s"Found README.md in ${command.projectName}, using its content for project context"))
    
    // Use README.md content if available, otherwise fall back to command.projectContext
    val projectContext = readmeContent.getOrElse(command.projectContext)
    
    // Create a Future sequence of both insert operations
    Future.sequence(Seq(
      projects.insertOne(Document(
        "_id" -> projectId,
        "projectName" -> command.projectName,
        "projectLocation" -> clonePath,
        "githubUrl" -> command.githubUrl
      )).toFuture(),
      
      projectContexts.insertOne(Document(
        "projectId" -> projectId.toHexString,
        "context" -> projectContext
      )).toFuture()
    )).map { _ =>
      projectsImportedCounter.increment()
      logger.info(s"Successfully imported GitHub project: ${command.projectName} from ${command.githubUrl}")
      updateImportStatus(importId, "in_progress", "Project imported, starting analysis...", 80)
    }.map(_ => ())
  }

  private def updateImportStatus(
    importId: ObjectId,
    status: String,
    message: String,
    progress: Int,
    error: Option[String] = None
  ): Future[Unit] = {
    val update = if (error.isDefined) {
      Updates.combine(
        Updates.set("status", status),
        Updates.set("message", message),
        Updates.set("progress", progress),
        Updates.set("error", error.get),
        Updates.set("updatedAt", new java.util.Date())
      )
    } else {
      Updates.combine(
        Updates.set("status", status),
        Updates.set("message", message),
        Updates.set("progress", progress),
        Updates.set("updatedAt", new java.util.Date())
      )
    }
    
    importStatus
      .updateOne(
        Filters.eq("_id", importId),
        update,
        new UpdateOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())
  }

  // Helper method to read README.md content from project root
  private def readReadmeFile(projectPath: String): Option[String] = {
    val readmePath = Paths.get(projectPath, "README.md")
    if (Files.exists(readmePath)) {
      try {
        Some(new String(Files.readAllBytes(readmePath), "UTF-8"))
      } catch {
        case ex: Exception =>
          logger.warn(s"Failed to read README.md: ${ex.getMessage}", ex)
          None
      }
    } else {
      None
    }
  }

  private def parseProjectModules(projectId: String, projectLocation: String): Seq[Module] = {
    try {
      val pomFile = Paths.get(projectLocation, "pom.xml").toFile
      val pomXml = XML.loadFile(pomFile)
      val modules = (pomXml \ "modules" \ "module").map(_.text)
      
      if (modules.nonEmpty) {
        modules.map(module =>
          Module(
            module,
            s"$projectLocation/$module",
            System.currentTimeMillis()
          )
        )
      } else {
        // If no modules found, treat the project itself as a single module
        Seq(Module("default", projectLocation, System.currentTimeMillis()))
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error parsing project modules: ${ex.getMessage}", ex)
        // Fallback to default module
        Seq(Module("default", projectLocation, System.currentTimeMillis()))
    }
  }
}