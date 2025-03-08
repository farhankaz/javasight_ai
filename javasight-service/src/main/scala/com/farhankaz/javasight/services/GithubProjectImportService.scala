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
import java.nio.file.{Files, Paths, Path}
import java.io.File
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
  
  // Project type detection patterns
  private val mavenPattern = "pom.xml"
  private val gradlePattern = "build.gradle"
  private val gradleKtsPattern = "build.gradle.kts"
  private val antPattern = "build.xml"
  private val sbtPattern = "build.sbt"
  
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
            
          // Extract import ID - this is required for proper tracking
          val importId = extract(importIdPattern, jsonStr).getOrElse(
            throw new Exception("Missing required field: import_id"))
          
          // Log parsed data
          logger.info(s"Parsed GitHub import command: project=$projectName, url=$githubUrl, importId=$importId")
          
          try {
            val objectId = new ObjectId(importId)
            updateImportStatus(
              objectId,
              "in_progress",
              "Processing import...",
              5
            )
            
            // Create command object with import ID
            (objectId, ImportGithubProjectCommand(
              projectName = projectName,
              githubUrl = githubUrl,
              projectContext = projectContext,
              timestamp = timestamp
            ))
          } catch {
            case e: Exception => 
              logger.error(s"Invalid import ID format: $importId", e)
              throw new Exception(s"Invalid import ID format: $importId")
          }
        } catch {
          case e: Exception =>
            logger.error(s"Failed to parse JSON message: ${e.getMessage}", e)
            throw e
        }
      }
      .mapAsync(1) { case (importId, command) =>
        processGithubImport(importId, command)
      }
      .map { case (projectId, command, clonePath, projectType) =>
        new ProducerRecord[Array[Byte], Array[Byte]](
          KafkaTopics.ProjectImportedEvents.toString(),
          ProjectImportedEvent(
            projectId.toHexString,
            command.projectName,
            clonePath,
            parseProjectModules(projectId.toHexString, clonePath, projectType),
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

  private def processGithubImport(importId: ObjectId, command: ImportGithubProjectCommand): Future[(ObjectId, ImportGithubProjectCommand, String, String)] = {
    logger.info(s"Processing GitHub import for ${command.projectName} from ${command.githubUrl}, import ID: ${importId.toHexString}")
    
    // Update status to started
    updateImportStatus(
      importId,
      "in_progress",
      "Initiating GitHub repository clone",
      10
    ).flatMap { _ =>
      // Use the import ID as the project ID to maintain consistency
      val projectId = importId
      val clonePath = s"$cloneBaseDir/${projectId.toHexString}"
      
      // Create directory if it doesn't exist
      Files.createDirectories(Paths.get(cloneBaseDir))
      
      // Clone GitHub repository
      cloneRepository(command.githubUrl, clonePath, importId)
        .flatMap { _ =>
          // Detect and validate project type
          detectAndValidateProject(clonePath, importId)
            .flatMap { projectType =>
              // Create MongoDB records
              createProjectRecords(projectId, command, clonePath, importId, projectType)
                .map { _ =>
                  // Return data needed for the next step
                  (projectId, command, clonePath, projectType)
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

  private def detectAndValidateProject(path: String, importId: ObjectId): Future[String] = {
    // First update the status
    updateImportStatus(importId, "in_progress", "Detecting project type...", 50).flatMap { _ =>
      Future {
        val projectRoot = new File(path)
        
        // Check for different build files to determine project type
        val projectType = if (new File(projectRoot, mavenPattern).exists()) {
          // Validate Maven project
          try {
            val pomFile = new File(projectRoot, mavenPattern)
            XML.loadFile(pomFile) // Validate XML
            "maven"
          } catch {
            case ex: Exception => 
              throw new RuntimeException(s"Invalid pom.xml file: ${ex.getMessage}")
          }
        } else if (new File(projectRoot, gradlePattern).exists() || new File(projectRoot, gradleKtsPattern).exists()) {
          "gradle"
        } else if (new File(projectRoot, antPattern).exists()) {
          "ant"
        } else if (new File(projectRoot, sbtPattern).exists()) {
          "sbt"
        } else {
          // Check if there are any Java files
          val hasJavaFiles = Files.walk(Paths.get(path))
            .filter(p => p.toString.endsWith(".java"))
            .findAny()
            .isPresent()
            
          if (hasJavaFiles) {
            "java" // Plain Java project
          } else {
            throw new RuntimeException("Unable to determine project type. No recognized build files found and no Java files detected.")
          }
        }
        
        projectType
      }
    }.flatMap { projectType =>
      // Update status with detected project type
      updateImportStatus(
        importId, 
        "in_progress", 
        s"Project detected as ${projectType.toUpperCase} project", 
        60
      ).map(_ => projectType)
    }
  }

  private def createProjectRecords(
    projectId: ObjectId,
    command: ImportGithubProjectCommand,
    clonePath: String,
    importId: ObjectId,
    projectType: String
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
        "githubUrl" -> command.githubUrl,
        "projectType" -> projectType
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
    // Check for different README file variants (case-insensitive)
    val readmeVariants = List(
      "README.md", "Readme.md", "readme.md",
      "README.txt", "Readme.txt", "readme.txt",
      "README", "Readme", "readme"
    )
    
    val readmeFile = readmeVariants
      .map(name => Paths.get(projectPath, name))
      .find(Files.exists(_))
    
    readmeFile.flatMap { path =>
      try {
        Some(new String(Files.readAllBytes(path), "UTF-8"))
      } catch {
        case ex: Exception =>
          logger.warn(s"Failed to read README file: ${ex.getMessage}", ex)
          None
      }
    }
  }

  private def parseProjectModules(projectId: String, projectLocation: String, projectType: String): Seq[Module] = {
    projectType match {
      case "maven" => parseMavenModules(projectId, projectLocation)
      case "gradle" => parseGradleModules(projectId, projectLocation)
      case _ => 
        // For other project types, treat the project itself as a single module
        Seq(Module("default", projectLocation, System.currentTimeMillis()))
    }
  }
  
  private def parseMavenModules(projectId: String, projectLocation: String): Seq[Module] = {
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
        logger.error(s"Error parsing Maven project modules: ${ex.getMessage}", ex)
        // Fallback to default module
        Seq(Module("default", projectLocation, System.currentTimeMillis()))
    }
  }
  
  private def parseGradleModules(projectId: String, projectLocation: String): Seq[Module] = {
    try {
      // Check for settings.gradle or settings.gradle.kts which defines modules in Gradle
      val settingsFile = if (Files.exists(Paths.get(projectLocation, "settings.gradle"))) {
        Paths.get(projectLocation, "settings.gradle")
      } else if (Files.exists(Paths.get(projectLocation, "settings.gradle.kts"))) {
        Paths.get(projectLocation, "settings.gradle.kts")
      } else {
        null
      }
      
      if (settingsFile != null) {
        // Simple regex to extract module names from settings.gradle
        val includePattern = """include\s*['"](:[^'"]+)['"]""".r
        val content = new String(Files.readAllBytes(settingsFile), "UTF-8")
        
        val moduleNames = includePattern.findAllMatchIn(content).map(_.group(1).substring(1)).toSeq
        
        if (moduleNames.nonEmpty) {
          moduleNames.map(module =>
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
      } else {
        // No settings.gradle found, treat as single module
        Seq(Module("default", projectLocation, System.currentTimeMillis()))
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error parsing Gradle project modules: ${ex.getMessage}", ex)
        // Fallback to default module
        Seq(Module("default", projectLocation, System.currentTimeMillis()))
    }
  }
}
