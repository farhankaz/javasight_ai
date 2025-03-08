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
import com.farhankaz.javasight.model.kafka.ScanModuleFileCommand
import com.farhankaz.javasight.model.kafka.ScanModuleDirectoryCommand
import com.github.javaparser.StaticJavaParser
import scala.jdk.OptionConverters._
import org.cthing.locc4j.FileCounter
import org.cthing.locc4j.Language
import org.cthing.locc4j.Counts
import com.github.javaparser.ast.body.MethodDeclaration
import scala.concurrent.duration._
import org.apache.kafka.common.utils.Java
import com.farhankaz.javasight.model.kafka.ModuleFileScannedEvent

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

  private val filesProcessed = metricsRegistry.counter(s"${config.env}_javasight_importmodule_java_files_processed")
  private val javaFilesCollection = database.getCollection[Document]("java_files")

  protected override def startService(): Consumer.DrainingControl[Done] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaTopic))
      .map { msg =>
        ScanModuleFileCommand.parseFrom(msg.record.value())
      }
      .map(processFile)
      .groupedWithin(100, 2.seconds)
      .mapAsync(1) { javaFiles =>
        filesProcessed.increment(javaFiles.size)
        logger.info(s"Processed batch of ${javaFiles.size} files")
        javaFilesCollection.bulkWrite(javaFiles.map(toInsert)).toFuture().map(_ => javaFiles)
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


  private def processFile(file: ScanModuleFileCommand): JavaFile = {
    val fileContent = new String(Files.readAllBytes(Paths.get(file.filePath)))
    val parserConfiguration = new com.github.javaparser.ParserConfiguration()
      .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    val javaParser = new com.github.javaparser.JavaParser(parserConfiguration)
    val compilationUnit = javaParser.parse(fileContent).getResult.orElseThrow()
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

    logger.info(s"Imported File ${file.filePath} for package ${file.parentPackageId} with package name ${packageName} has $loc lines of code and ${publicMethods.size} public methods")
    new JavaFile(
      filePath = file.filePath,
      moduleId = file.moduleId,
      projectId = file.projectId,
      packageId = file.parentPackageId,
      packageName = packageName,
      linesOfCode = loc,
      publicMethods = publicMethods
    )
  }

  private def isGetterOrSetter(method: MethodDeclaration): Boolean = {
    val name = method.getNameAsString
    (name.startsWith("get") && method.getParameters.isEmpty) ||
    (name.startsWith("set") && method.getParameters.size == 1 && method.getType().isVoidType())
  }

  private def toInsert(file: JavaFile): InsertOneModel[Document] = 
    InsertOneModel(
      Document(
        "_id" -> file.fileId,
        "projectId" -> file.projectId,
        "moduleId" -> file.moduleId,
        "filePath" -> file.filePath,
        "linesOfCode" -> file.linesOfCode,
        "packageName" -> file.packageName,
        "packageId" -> file.packageId,
        "publicMethods" -> file.publicMethods
      )
    )
}

case class JavaFile(
  fileId: ObjectId = new ObjectId(),
  projectId: String,
  moduleId: String,
  filePath: String,
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
      timestamp = System.currentTimeMillis()
    )
}
