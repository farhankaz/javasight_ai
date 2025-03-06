package com.farhankaz.javasight.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import spray.json._
import DefaultJsonProtocol._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Using
import java.util.concurrent.TimeUnit

// JSON models
case class PromptPair(userPrompt: String, systemPrompt: String)
case class Prompts(
  file: PromptPair,
  fileShort: PromptPair,
  `package`: PromptPair,
  module: PromptPair,
  project: PromptPair
)

// JSON protocol
object PromptsProtocol extends DefaultJsonProtocol {
  implicit val promptPairFormat: RootJsonFormat[PromptPair] = jsonFormat2(PromptPair)
  implicit val promptsFormat: RootJsonFormat[Prompts] = jsonFormat5(Prompts)
}

class Ollama(config: ConfigurationLoader, metricsRegistry: MeterRegistry)
    (implicit system: ActorSystem, ec: ExecutionContext) extends CodeAnalyzer {
  import PromptsProtocol._
  
  private def loadPrompts(): Prompts = {
    val jsonContent = Option(getClass.getResourceAsStream("/prompts.json"))
      .map(resource => Using(Source.fromInputStream(resource))(_.mkString))
      .getOrElse(throw new RuntimeException("Failed to load prompts.json from resources"))
      .getOrElse(throw new RuntimeException("Failed to read prompts.json content"))
    
    jsonContent.parseJson.convertTo[Prompts]
  }
  
  private val prompts = loadPrompts()
  private val http = Http()
  
  private val contextLength = 32768 // 32k context length
  private val smallModel = "llama3.2:1b"
  private val largeModel = "phi4:latest"
  
  private val ollamaHost = Option(config.getString("ollama.host")).getOrElse("localhost")
  private val ollamaPort = Option(config.getInt("ollama.port")).getOrElse(11434)
  private val baseUri = s"http://$ollamaHost:$ollamaPort/api/chat"
  
  private val defaultTimeout = Option(config.getDuration("ollama.timeout"))
    .getOrElse(Duration(30, TimeUnit.MINUTES))
  private val defaultRetries = Option(config.getInt("ollama.retries")).getOrElse(3)
  private val defaultKeepAlive = Option(config.getString("ollama.keep-alive")).getOrElse("10m")
  
  private val timeoutSettings = ConnectionPoolSettings(system)
    .withIdleTimeout(defaultTimeout)
    .withKeepAliveTimeout(defaultTimeout.minus(5.minutes))
    .withMaxRetries(defaultRetries)
  
  private val analysisRequests = metricsRegistry.counter(
    "javasight_ollama_requests_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )
  
  private val analysisFailures = metricsRegistry.counter(
    "javasight_ollama_failures_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )

  override def analyzeFile(fileContent: String, projectContext: Option[String] = None, modelName: String = smallModel): Future[String] = {
    val prompt = prompts.file.userPrompt
      .replace("{fileContent}", fileContent)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    makeRequest(
      modelName = modelName,
      systemPrompt = prompts.file.systemPrompt,
      userPrompt = prompt,
      contextSize = contextLength
    )
  }

  override def analyzeFileShort(fileContent: String, projectContext: Option[String] = None, modelName: String = smallModel): Future[String] = {
    val prompt = prompts.fileShort.userPrompt
      .replace("{fileContent}", fileContent)
      .replace("{projectContext}", projectContext.getOrElse(""))
    makeRequest(
      modelName = modelName,
      systemPrompt = prompts.fileShort.systemPrompt,
      userPrompt = prompt,
      contextSize = contextLength
    )
  }

  override def analyzePackage(
    packageName: String, 
    fileAnalyses: Seq[String], 
    projectContext: Option[String] = None, 
    modelName: String = smallModel
  ): Future[String] = {
    val analyses = fileAnalyses.mkString("\n\n")
    val prompt = prompts.`package`.userPrompt
      .replace("{packageName}", s"$packageName")
      .replace("{fileAnalyses}", analyses)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    makeRequest(
      modelName = modelName,
      systemPrompt = prompts.`package`.systemPrompt,
      userPrompt = prompt,
      contextSize = contextLength
    )
  }

  override def analyzeModule(
    moduleName: String,
    projectName: String,
    packageAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = smallModel
  ): Future[String] = {
    val analyses = packageAnalyses.mkString("\n\n")
    val prompt = prompts.`module`.userPrompt
      .replace("{moduleName}", s"$moduleName")
      .replace("{projectName}", s"$projectName")
      .replace("{packageAnalyses}", analyses)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    val systemPrompt = prompts.module.systemPrompt
      .replace("{moduleName}", s"$moduleName")
      .replace("{projectName}", s"$projectName")
    
    makeRequest(
      modelName = modelName,
      systemPrompt = systemPrompt,
      userPrompt = prompt,
      contextSize = contextLength
    )
  }

  override def analyzeProject(
    projectName: String,
    moduleAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = smallModel
  ): Future[String] = {
    val analyses = moduleAnalyses.mkString("\n\n")
    val prompt = prompts.`project`.userPrompt
      .replace("{projectName}", s"$projectName")
      .replace("{moduleAnalyses}", analyses)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    val systemPrompt = prompts.project.systemPrompt
      .replace("{projectName}", s"$projectName")
    
    makeRequest(
      modelName = modelName,
      systemPrompt = systemPrompt,
      userPrompt = prompt,
      contextSize = contextLength
    )
  }

  private def makeRequest(
    modelName: String,
    systemPrompt: String,
    userPrompt: String,
    contextSize: Int,
    retries: Int = defaultRetries
  ): Future[String] = {
    if (retries <= 0) {
      Future.failed(new RuntimeException("Max retries exceeded for Ollama request"))
    } else {
      analysisRequests.increment()
      
      val request = JsObject(
        "model" -> JsString(modelName),
        "stream" -> JsBoolean(false),
        "keep_alive" -> JsString(defaultKeepAlive),
        "options" -> JsObject("num_ctx" -> JsNumber(contextSize)),
        "messages" -> JsArray(
          JsObject(
            "role" -> JsString("system"),
            "content" -> JsString(systemPrompt)
          ),
          JsObject(
            "role" -> JsString("user"),
            "content" -> JsString(userPrompt)
          )
        )
      ).toString()

      val httpRequest = HttpRequest(
        method = HttpMethods.POST,
        uri = baseUri,
        entity = HttpEntity(ContentTypes.`application/json`, request)
      )

      http.singleRequest(httpRequest, settings = timeoutSettings).flatMap { response =>
        response.status match {
          case StatusCodes.OK =>
            response.entity.toStrict(10.minutes).map { entity =>
              val analysis = entity.data.utf8String
              val analysisJson = analysis.parseJson.asJsObject
              analysisJson.fields("message").asJsObject.fields("content").convertTo[String]
            }.recoverWith {
              case ex: Exception =>
                analysisFailures.increment()
                makeRequest(modelName, systemPrompt, userPrompt, contextSize, retries - 1)
            }
          case _ =>
            analysisFailures.increment()
            response.entity.discardBytes()
            makeRequest(modelName, systemPrompt, userPrompt, contextSize, retries - 1)
        }
      }.recoverWith {
        case ex: Exception =>
          analysisFailures.increment()
          makeRequest(modelName, systemPrompt, userPrompt, contextSize, retries - 1)
      }
    }
  }
}
