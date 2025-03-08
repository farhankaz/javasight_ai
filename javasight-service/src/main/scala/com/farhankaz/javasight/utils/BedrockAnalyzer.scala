package com.farhankaz.javasight.utils

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model._
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._
import spray.json._
import DefaultJsonProtocol._

class BedrockAnalyzer(config: ConfigurationLoader, metricsRegistry: MeterRegistry)
                     (implicit ec: ExecutionContext) extends CodeAnalyzer {
  
  import PromptsProtocol._
  
  private val prompts = loadPrompts()
  private val modelId = "anthropic.claude-3-5-sonnet-20241022-v2:0"
  
  private val client = BedrockRuntimeClient.builder()
    .credentialsProvider(DefaultCredentialsProvider.create())
    .region(Region.of(config.getString("aws.region")))
    .build()

  private val analysisRequests = metricsRegistry.counter(
    "javasight_bedrock_requests_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )

  private val analysisFailures = metricsRegistry.counter(
    "javasight_bedrock_failures_total",
    Tags.of("service_name", getClass.getSimpleName.stripSuffix("$"), "env", config.env)
  )

  private def loadPrompts(): Prompts = {
    val jsonContent = Option(getClass.getResourceAsStream("/prompts.json"))
      .map(resource => scala.io.Source.fromInputStream(resource).mkString)
      .getOrElse(throw new RuntimeException("Failed to load prompts.json"))
    
    jsonContent.parseJson.convertTo[Prompts]
  }

  private def makeRequest(
    systemPrompt: String,
    userPrompt: String,
    maxTokens: Int = 4096,
    temperature: Float = 0.7f
  ): Future[String] = {
    try {
      analysisRequests.increment()

      val message = Message.builder()
        .content(ContentBlock.fromText(userPrompt))
        .role(ConversationRole.USER)
        .build()

      val systemMessage = Message.builder()
        .content(ContentBlock.fromText(systemPrompt))
        .role(ConversationRole.USER)
        .build()

      val response = client.converse { request =>
        request
          .modelId(modelId)
          .messages(systemMessage, message)
          .inferenceConfig { config =>
            config
              .maxTokens(maxTokens)
              .temperature(temperature)
              .topP(0.9F)
          }
      }
      
      Future.successful(response.output().message().content().get(0).text())
      // Future.successful("dummy response")
    } catch {
      case e: Exception =>
        analysisFailures.increment()
        Future.failed(new RuntimeException(s"Bedrock request failed: ${e.getMessage}"))
    }
  }

  override def analyzeFile(
    fileContent: String,
    projectContext: Option[String] = None,
    modelName: String = modelId
  ): Future[String] = {
    val prompt = prompts.file.userPrompt
      .replace("{fileContent}", fileContent)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    makeRequest(prompts.file.systemPrompt, prompt)
  }

  override def analyzeFileShort(
    fileContent: String,
    projectContext: Option[String] = None,
    modelName: String = modelId
  ): Future[String] = {
    val prompt = prompts.fileShort.userPrompt
      .replace("{fileContent}", fileContent)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    makeRequest(prompts.fileShort.systemPrompt, prompt)
  }

  override def analyzePackage(
    packageName: String,
    fileAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = modelId
  ): Future[String] = {
    val analyses = fileAnalyses.mkString("\n\n")
    val prompt = prompts.`package`.userPrompt
      .replace("{packageName}", packageName)
      .replace("{fileAnalyses}", analyses)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    makeRequest(prompts.`package`.systemPrompt, prompt)
  }

  override def analyzeModule(
    moduleName: String,
    projectName: String,
    packageAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = modelId
  ): Future[String] = {
    val analyses = packageAnalyses.mkString("\n\n")
    val prompt = prompts.module.userPrompt
      .replace("{moduleName}", moduleName)
      .replace("{projectName}", projectName)
      .replace("{packageAnalyses}", analyses)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    val systemPrompt = prompts.module.systemPrompt
      .replace("{moduleName}", moduleName)
      .replace("{projectName}", projectName)
    
    makeRequest(systemPrompt, prompt)
  }

  override def analyzeProject(
    projectName: String,
    moduleAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = modelId
  ): Future[String] = {
    val analyses = moduleAnalyses.mkString("\n\n")
    val prompt = prompts.project.userPrompt
      .replace("{projectName}", projectName)
      .replace("{moduleAnalyses}", analyses)
      .replace("{projectContext}", projectContext.getOrElse(""))
    
    val systemPrompt = prompts.project.systemPrompt
      .replace("{projectName}", projectName)
    
    makeRequest(systemPrompt, prompt)
  }
} 