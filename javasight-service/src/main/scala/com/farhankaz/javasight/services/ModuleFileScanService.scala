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
import com.farhankaz.javasight.model.kafka.ModuleFileScannedEvent
import com.farhankaz.javasight.model.kafka.ScanModuleDirectoryCommand
import com.farhankaz.javasight.model.kafka.ScanModuleFileCommand
import com.farhankaz.javasight.model.protobuf.ImportFile
import com.farhankaz.javasight.model.protobuf.ImportModule
import com.farhankaz.javasight.utils.ConfigurationLoader
import com.farhankaz.javasight.utils.FileTypeSupport
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.utils.Java
import org.bson.types.ObjectId
import org.cthing.locc4j.Counts
import org.cthing.locc4j.FileCounter
import org.cthing.locc4j.Language
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
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Failure
import scala.util.Success

class ModuleFileScanService(
    config: ConfigurationLoader,
    metricsRegistry: MeterRegistry,
    database: MongoDatabase
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends BaseKafkaService(
      KafkaTopics.ScanModuleFileCommands,
      config,
      metricsRegistry,
      database
    ) {

  private val filesProcessed = metricsRegistry.counter(s"${config.env}_javasight_importmodule_files_processed")
  private val javaFilesCollection = database.getCollection[Document]("files")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        ScanModuleFileCommand.parseFrom(msg.record.value())
      }
      .map(processFile)
      .groupedWithin(100, 2.seconds)
      .mapAsync(1) { sourceFiles =>
        filesProcessed.increment(sourceFiles.size)
        logger.info(s"Processed batch of ${sourceFiles.size} files")
        javaFilesCollection.bulkWrite(sourceFiles.map(toInsert)).toFuture().map(_ => sourceFiles)
      }
      .mapConcat(identity)
      .map { file =>
        new ProducerRecord[Array[Byte], Array[Byte]](KafkaTopics.ModuleFileScannedEvents.toString(), file.toModuleFileScannedEvent().toByteArray)
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      .toMat(Producer.plainSink(producerSettings))(Keep.both)
      .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
      .run()
  }

  private def processFile(file: ScanModuleFileCommand): SourceFile = {
    val fileType = file.fileType.getOrElse(FileTypeSupport.getFileType(file.filePath))
    
    if (fileType == "java") {
      processJavaFile(file)
    } else {
      processNonJavaFile(file, fileType)
    }
  }
  
  private def processJavaFile(file: ScanModuleFileCommand): SourceFile = {
    try {
      val fileContent = new String(Files.readAllBytes(Paths.get(file.filePath)))
      val parserConfiguration = new com.github.javaparser.ParserConfiguration()
        .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
      val javaParser = new com.github.javaparser.JavaParser(parserConfiguration)
      val compilationUnit = javaParser.parse(fileContent).getResult.orElseThrow()
      
      // Use FileCounter for Java files
      val counter = new FileCounter()
      val counts = counter.count(file.filePath)
      
      val loc = counts.asScala.headOption
        .map(row => row._2.getOrDefault(Language.Java, Counts.ZERO))
        .map(_.getCodeLines())
        .getOrElse(0)
        
      val packageName = compilationUnit.getPackageDeclaration
        .map(_.getNameAsString)
        .orElse("")

      val publicMethods = compilationUnit.findAll(classOf[MethodDeclaration]).asScala
        .filter(m => m.isPublic && !isGetterOrSetter(m))
        .map(_.getSignature.asString())
        .toList

      logger.info(s"Imported Java file ${file.filePath} for package ${file.parentPackageId} with package name ${packageName} has $loc lines of code and ${publicMethods.size} public methods")
      
      SourceFile(
        filePath = file.filePath,
        fileType = "java",
        moduleId = file.moduleId,
        projectId = file.projectId,
        packageId = file.parentPackageId,
        packageName = packageName,
        linesOfCode = loc,
        publicMethods = publicMethods
      )
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to process Java file: ${file.filePath}", e)
        processNonJavaFile(file, "java")
    }
  }
  
  private def processNonJavaFile(file: ScanModuleFileCommand, fileType: String): SourceFile = {
    // Simple line counting for non-Java files
    val lines = Files.readAllLines(Paths.get(file.filePath))
    val loc = lines.size()
    
    // Use directory name as package for non-Java files
    val packageName = FileTypeSupport.getDirectoryAsPackage(file.filePath)
    
    logger.info(s"Imported ${fileType} file ${file.filePath} for package ${file.parentPackageId} with package name ${packageName} has $loc lines of code")
    
    SourceFile(
      filePath = file.filePath,
      fileType = fileType,
      moduleId = file.moduleId,
      projectId = file.projectId,
      packageId = file.parentPackageId,
      packageName = packageName,
      linesOfCode = loc,
      publicMethods = List.empty // Empty methods list for non-Java files
    )
  }

  private def isGetterOrSetter(method: MethodDeclaration): Boolean = {
    val name = method.getNameAsString
    (name.startsWith("get") && method.getParameters.isEmpty) ||
    (name.startsWith("set") && method.getParameters.size == 1 && method.getType().isVoidType())
  }

  private def toInsert(file: SourceFile): InsertOneModel[Document] = 
    InsertOneModel(
      Document(
        "_id" -> file.fileId,
        "projectId" -> file.projectId,
        "moduleId" -> file.moduleId,
        "filePath" -> file.filePath,
        "fileType" -> file.fileType,
        "linesOfCode" -> file.linesOfCode,
        "packageName" -> file.packageName,
        "packageId" -> file.packageId,
        "publicMethods" -> file.publicMethods
      )
    )
}

case class SourceFile(
  fileId: ObjectId = new ObjectId(),
  projectId: String,
  moduleId: String,
  filePath: String,
  fileType: String,
  linesOfCode: Int,
  packageName: String,
  packageId: Option[String],
  publicMethods: List[String] = List.empty
) {
  def toModuleFileScannedEvent(): ModuleFileScannedEvent =
    ModuleFileScannedEvent(
      fileId = fileId.toString,
      moduleId = moduleId,
      projectId = projectId,
      filePath = filePath,
      parentPackageId = packageId,
      fileType = Some(fileType),
      timestamp = System.currentTimeMillis()
    )
}
