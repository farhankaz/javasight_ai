package com.farhankaz.javasight.utils

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import scala.jdk.CollectionConverters._
import scala.util.{Try, Success, Failure}

class ConfigurationLoader(env: String) {
  private val baseConfig = ConfigFactory.load()
  
  // Use constructor env parameter
  private val effectiveEnv: String = sys.env.getOrElse("ENV", "local")
  
  private val awsConfig = if (effectiveEnv != "local") {
    loadAwsParameters().getOrElse(ConfigFactory.empty())
  } else {
    ConfigFactory.empty()
  }
  
  private val config = awsConfig.withFallback(baseConfig)

  private def createSsmClient(): SsmClient = {
    SsmClient.builder()
      .region(software.amazon.awssdk.regions.Region.of(baseConfig.getString("aws.parameter-store.region")))
      .build()
  }

  private def loadAwsParameters(): Try[Config] = Try {
    val ssmClient = createSsmClient()
    val prefix = s"/${effectiveEnv}/javasight/"
    
    val parameters = Map(
      "kafka.bootstrap-servers" -> ssmClient.getParameter(
        GetParameterRequest.builder()
          .name(s"${prefix}kafka.bootstrap-servers")
          .withDecryption(true)
          .build()
      ).parameter().value(),
      
      "kafka.client-id" -> ssmClient.getParameter(
        GetParameterRequest.builder()
          .name(s"${prefix}kafka.client-id")
          .withDecryption(true)
          .build()
      ).parameter().value(),
      
      "kafka.group-id" -> ssmClient.getParameter(
        GetParameterRequest.builder()
          .name(s"${prefix}kafka.group-id")
          .withDecryption(true)
          .build()
      ).parameter().value(),
      
      "mongodb.uri" -> ssmClient.getParameter(
        GetParameterRequest.builder()
          .name(s"${prefix}mongodb.uri")
          .withDecryption(true)
          .build()
      ).parameter().value(),
      
      "health.port" -> ssmClient.getParameter(
        GetParameterRequest.builder()
          .name(s"${prefix}health.port")
          .withDecryption(true)
          .build()
      ).parameter().value()
    )
    
    ssmClient.close()
    
    ConfigFactory.parseMap(parameters.asJava)
  }

  def getKafkaBootstrapServers: String = config.getString("kafka.bootstrap-servers")
  def getKafkaClientId: String = config.getString("kafka.client-id")
  def getKafkaGroupId: String = config.getString("kafka.group-id")
  
  def getMongoUri: String = config.getString("mongodb.uri")
  
  def isAwsParameterStoreEnabled: Boolean = config.getBoolean("aws.parameter-store.enabled")
  def getAwsRegion: String = config.getString("aws.parameter-store.region")
  def getAwsPrefix: String = s"/${effectiveEnv}/javasight/"
  
  def getHealthPort: Int = config.getInt("health.port")
  
  def getInt(path: String): Int = config.getInt(path)
  
  def getString(path: String): String = config.getString(path)
  
  def getDuration(path: String): scala.concurrent.duration.Duration = 
    scala.concurrent.duration.Duration.fromNanos(config.getDuration(path).toNanos)

  // Redis configuration
  def redisHost: String = config.getString("redis.host")
  def redisPort: Int = config.getInt("redis.port")
  def redisTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(config.getInt("redis.timeout"), "millis")
  def redisMaxConnections: Int = config.getInt("redis.pool.max-total")
  def redisMaxIdle: Int = config.getInt("redis.pool.max-idle")
  def redisMinIdle: Int = config.getInt("redis.pool.min-idle")

  def env: String = effectiveEnv
}

object ConfigurationLoader {
  def apply(env: String = "local"): ConfigurationLoader = new ConfigurationLoader(env)
}
