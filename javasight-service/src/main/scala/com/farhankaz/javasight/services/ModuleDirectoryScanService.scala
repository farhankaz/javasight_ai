package com.farhankaz.javasight.services

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.{Filters, InsertOneModel}
import org.slf4j.LoggerFactory
import scalapb.GeneratedMessage
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.io.File
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._
import com.farhankaz.javasight.utils.ConfigurationLoader
import com.farhankaz.javasight.model.protobuf.{ImportModule, ImportFile}
import io.micrometer.core.instrument.MeterRegistry
import org.bson.types.ObjectId
import akka.kafka.ProducerMessage
import akka.NotUsed
import akka.stream.ActorAttributes
import org.mongodb.scala.MongoDatabase
import com.farhankaz.javasight.model.kafka.{ProjectImportedEvent, ScanModuleFileCommand, ScanModuleDirectoryCommand, PackageDiscoveryEvent}
import com.github.javaparser.StaticJavaParser
import scala.jdk.OptionConverters._

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
  private val packagesCollection = database.getCollection[Document]("java_packages")

  override def startService(): Consumer.DrainingControl[Done] =
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        ScanModuleDirectoryCommand.parseFrom(msg.record.value())
      }
      .map { msg =>
        logger.trace(s"Scanning module directory: ${msg.path}")
        val javaFiles = findJavaFiles(msg.path)
        logger.trace(s"Found ${javaFiles.size} java files in ${msg.path}")
        (msg, javaFiles
          .headOption
          .flatMap(extractPackageName))
      }
      .mapAsync(1) { 
        case (msg, Some(packageName)) => {
          // Insert package document if package name exists
          val packageId = new ObjectId()
          val childDirs = findChildJavaDirectories(msg.path).size
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
            baseMessages = javaFileMessages(msg, packageId.toString()) ++ createScanDirectoryMessages(msg, Some(packageId.toString()))
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

  def findJavaFiles(directory: String): Seq[String] = {
    val dir = new File(directory)
    if (dir.exists() && dir.isDirectory) {
      dir.listFiles()
        .filter(f => f.isFile && f.getName.endsWith(".java"))
        .filterNot(f => f.getName.endsWith("Test.java") || f.getPath.contains("test"))
        .map(_.getAbsolutePath)
        .toSeq
    } else {
      Seq.empty
    }
  }

  def hasJavaFiles(d: File): Boolean = {
    if (d.isDirectory) {
      d.listFiles().exists(f =>
        (f.isFile && f.getName.endsWith(".java") &&
          !f.getName.endsWith("Test.java") &&
          !f.getPath.contains("test") && !f.getPath().contains("target")) ||
        (f.isDirectory && hasJavaFiles(f))
      )
    } else false
  }

  def findChildJavaDirectories(directory: String): Seq[File] = {
    val dir = new File(directory)
    if (dir.exists() && dir.isDirectory) {
      dir.listFiles()
        .filter(_.isDirectory)
        .filter { childDir =>
          hasJavaFiles(childDir)
        }
        .toSeq
    } else {
      Seq.empty
    }
  }


  def extractPackageName(filePath: String): Option[String] = {
    val fileContent = new String(Files.readAllBytes(Paths.get(filePath)))
    val parserConfiguration = new com.github.javaparser.ParserConfiguration()
      .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    val javaParser = new com.github.javaparser.JavaParser(parserConfiguration)
    val compilationUnit = javaParser.parse(fileContent).getResult.orElseThrow()
    
    compilationUnit.getPackageDeclaration.map(_.getNameAsString).toScala
  }

  def javaFileMessages(msg: ScanModuleDirectoryCommand, packageId: String): Seq[ProducerRecord[Array[Byte],Array[Byte]]] = {
    findJavaFiles(msg.path).map { filePath =>
      new ProducerRecord[Array[Byte], Array[Byte]](
        KafkaTopics.ScanModuleFileCommands.toString(),
        ScanModuleFileCommand(
          filePath = filePath,
          moduleId = msg.moduleId,
          projectId = msg.projectId,
          parentPackageId = Some(packageId)
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
    findChildJavaDirectories(msg.path)
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
