package com.farhankaz.javasight.services

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.ProducerMessage
import akka.kafka.ProducerSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Producer
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.farhankaz.javasight.model.kafka.PackageDiscoveryEvent
import com.farhankaz.javasight.model.kafka.ScanModuleDirectoryCommand
import com.farhankaz.javasight.model.kafka.ScanModuleFileCommand
import com.farhankaz.javasight.model.protobuf.ImportFile
import com.farhankaz.javasight.model.protobuf.ImportModule
import com.farhankaz.javasight.utils.ConfigurationLoader
import com.farhankaz.javasight.utils.FileTypeSupport
import com.github.javaparser.StaticJavaParser
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.bson.types.ObjectId
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.InsertOneModel
import org.slf4j.LoggerFactory
import scalapb.GeneratedMessage

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Failure
import scala.util.Success

class ModuleDirectoryScanService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ScanModuleDirectoryCommands,
      config,
      metricsRegistry,
      database
    ) {

  protected override val logger = LoggerFactory.getLogger(getClass)

  private val packagesInserted = metricsRegistry.counter(s"${config.env}_javasight_packages_inserted")
  private val packagesCollection = database.getCollection[Document]("packages")

  override def startService(): Consumer.DrainingControl[Done] =
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        ScanModuleDirectoryCommand.parseFrom(msg.record.value())
      }
      .map { msg =>
        logger.trace(s"Scanning module directory: ${msg.path}")
        val sourceFiles = findSourceFiles(msg.path)
        logger.trace(s"Found ${sourceFiles.size} source files in ${msg.path}")
        (msg, sourceFiles
          .headOption
          .flatMap(extractPackageName))
      }
      .mapAsync(1) { 
        case (msg, Some(packageName)) => {
          // Insert package document if package name exists
          val packageId = new ObjectId()
          val childDirs = findChildSourceDirectories(msg.path).size
          logger.info(s"Found child directories: ${childDirs} for ${packageName}")
          val packageDoc = Document(
             "_id" -> packageId,
             "module_id" -> msg.moduleId,
             "project_id" -> msg.projectId,
             "parentPackageId" -> msg.parentPackageId,
             "packageName" -> packageName,
             "createdAt" -> System.currentTimeMillis(),
             "pendingChildren" -> childDirs,
             "childrenDiscovered" -> (childDirs == 0)
         )
          for {
            _ <- packagesCollection.insertOne(packageDoc).toFuture()
            _ = packagesInserted.increment()
            _ = logger.trace(s"Found new package ${packageName} with id ${packageId} for module ${msg.moduleId}")
            baseMessages = sourceFileMessages(msg, packageId.toString()) ++ createScanDirectoryMessages(msg, Some(packageId.toString()))
            discoveryMessages <- msg.parentPackageId match {
              case Some(parentId) => {
                logger.info(s"Telling parent ${parentId} package that I (${packageId}) am done.")
                updateParentPackage(parentId, msg)
              }
              case None => Future.successful(Seq.empty)
            }
          } yield baseMessages ++ discoveryMessages
        }
        case (msg, None) => {
          msg.parentPackageId match {
            case Some(parentId) =>
              logger.info(s"Telling parent ${parentId} package that I (${msg.path}) am done.")
              updateParentPackage(parentId, msg).map(discoveryMessages =>
                createScanDirectoryMessages(msg, None) ++ discoveryMessages
              )
            case None => Future.successful(createScanDirectoryMessages(msg, None))
          }
        }
      }      
      .map(messages => ProducerMessage.multi(messages))
      .via(Producer.flexiFlow(producerSettings))
      .map(_.passThrough)
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()

  def findSourceFiles(directory: String): Seq[String] = {
    val dir = new File(directory)
    if (dir.exists() && dir.isDirectory) {
      dir.listFiles()
        .filter(f => f.isFile && FileTypeSupport.isValidSourceFile(f.getAbsolutePath))
        .map(_.getAbsolutePath)
        .toSeq
    } else {
      Seq.empty
    }
  }

  def hasSourceFiles(d: File): Boolean = {
    if (d.isDirectory) {
      d.listFiles().exists(f =>
        (f.isFile && FileTypeSupport.isValidSourceFile(f.getAbsolutePath)) ||
        (f.isDirectory && hasSourceFiles(f))
      )
    } else false
  }

  def findChildSourceDirectories(directory: String): Seq[File] = {
    val dir = new File(directory)
    if (dir.exists() && dir.isDirectory) {
      dir.listFiles()
        .filter(_.isDirectory)
        .filter { childDir =>
          hasSourceFiles(childDir)
        }
        .toSeq
    } else {
      Seq.empty
    }
  }


  def extractPackageName(filePath: String): Option[String] = {
    if (filePath.endsWith(".java")) {
      // Existing Java package extraction logic
      try {
        val fileContent = new String(Files.readAllBytes(Paths.get(filePath)))
        val parserConfiguration = new com.github.javaparser.ParserConfiguration()
          .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        val javaParser = new com.github.javaparser.JavaParser(parserConfiguration)
        val compilationUnit = javaParser.parse(fileContent).getResult.orElseThrow()
        
        compilationUnit.getPackageDeclaration.map(_.getNameAsString).toScala
      } catch {
        case e: Exception => 
          logger.warn(s"Failed to extract package from Java file: $filePath", e)
          Some(FileTypeSupport.getDirectoryAsPackage(filePath))
      }
    } else {
      // For non-Java files, use directory name as package
      Some(FileTypeSupport.getDirectoryAsPackage(filePath))
    }
  }

  def sourceFileMessages(msg: ScanModuleDirectoryCommand, packageId: String): Seq[ProducerRecord[Array[Byte],Array[Byte]]] = {
    findSourceFiles(msg.path).map { filePath =>
      val fileType = FileTypeSupport.getFileType(filePath)
      new ProducerRecord[Array[Byte], Array[Byte]](
        KafkaTopics.ScanModuleFileCommands.toString(),
        ScanModuleFileCommand(
          filePath = filePath,
          moduleId = msg.moduleId,
          projectId = msg.projectId,
          parentPackageId = Some(packageId),
          fileType = Some(fileType)
        ).toByteArray
      )
    }
  }

  private def updateParentPackage(parentId: String, msg: ScanModuleDirectoryCommand): Future[Seq[ProducerRecord[Array[Byte], Array[Byte]]]] = {
    packagesCollection.updateOne(
      Filters.eq("_id", new ObjectId(parentId)),
      Document("$inc" -> Document("pendingChildren" -> -1))
    ).toFuture().flatMap { _ =>
      packagesCollection.find(Filters.eq("_id", new ObjectId(parentId))).first().toFuture()
    }.map { parentDoc =>
      if (parentDoc.getInteger("pendingChildren", 1) == 0) {
        // Emit PackageDiscoveryEvent when all children are processed
        val discoveryEvent = new ProducerRecord[Array[Byte], Array[Byte]](
          KafkaTopics.PackageDiscoveryEvents.toString(),
          PackageDiscoveryEvent(
            packageId = parentId,
            moduleId = msg.moduleId,
            projectId = msg.projectId,
            parentPackageId = Option(parentDoc.getString("parentPackageId")),
            timestamp = System.currentTimeMillis()
          ).toByteArray
        )
        Seq(discoveryEvent)
      } else {
        Seq.empty
      }
    }
  }

  def createScanDirectoryMessages(msg: ScanModuleDirectoryCommand, parentPackageId: Option[String]): Seq[ProducerRecord[Array[Byte],Array[Byte]]] = {
    findChildSourceDirectories(msg.path)
      .map { dir =>
        logger.info(s"Creating scan directory message for child ${dir.getAbsolutePath}")
        new ProducerRecord[Array[Byte], Array[Byte]](
          KafkaTopics.ScanModuleDirectoryCommands.toString(),
          ScanModuleDirectoryCommand(
            path = dir.getAbsolutePath,
            moduleId = msg.moduleId,
            projectId = msg.projectId,
            parentPackageId = parentPackageId.orElse(msg.parentPackageId)
          ).toByteArray
        )
      }.toSeq
  }

}
