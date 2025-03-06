package com.farhankaz.javasight.services

import akka.actor.{ActorSystem, Scheduler}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions, ConsumerMessage}
import io.micrometer.core.instrument.Tags
import java.util.function.ToDoubleFunction
import akka.stream.Materializer
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.collection.immutable.Document
import org.slf4j.Logger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import com.farhankaz.javasight.utils.ConfigurationLoader
import io.micrometer.core.instrument.MeterRegistry
import akka.Done
import akka.stream.Supervision
import org.apache.kafka.common.serialization.ByteArraySerializer
import akka.kafka.ProducerSettings
import org.mongodb.scala.MongoDatabase

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

abstract class BaseKafkaService(
  val topic: KafkaTopic,
  protected val config: ConfigurationLoader,
  protected val metricsRegistry: MeterRegistry,
  protected val database: MongoDatabase
)(implicit val system: ActorSystem, val mat: Materializer, val ec: ExecutionContext) {

  protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val serviceName = getClass.getSimpleName.stripSuffix("$")
  
  // Health check metrics
  private val isHealthy = new AtomicBoolean(true)
  private val healthGauge = metricsRegistry.gauge(
    "javasight_service_health",
    Tags.of("service_name", serviceName, "topic", kafkaTopic, "env", config.env),
    isHealthy,
    new ToDoubleFunction[AtomicBoolean] {
      override def applyAsDouble(value: AtomicBoolean): Double = if (value.get) 1.0 else 0.0
    }
  )
  
  // Consumer lag metrics
  private val lagValue = new java.util.concurrent.atomic.AtomicLong(0)
  private val lagGauge = metricsRegistry.gauge(
    "javasight_consumer_lag",
    Tags.of("service_name", serviceName, "topic", kafkaTopic, "env", config.env),
    lagValue,
    new ToDoubleFunction[java.util.concurrent.atomic.AtomicLong] {
      override def applyAsDouble(value: java.util.concurrent.atomic.AtomicLong): Double = value.get.toDouble
    }
  )
  
  // Message processing metrics
  private val messageCounter = metricsRegistry.counter(
    "javasight_messages_processed_total",
    Tags.of("service_name", serviceName, "topic", kafkaTopic, "env", config.env)
  )
  
  private val processingErrorCounter = metricsRegistry.counter(
    "javasight_processing_errors_total",
    Tags.of("service_name", serviceName, "topic", kafkaTopic, "env", config.env)
  )
  
  def kafkaTopic: String = topic.toString
  
  protected def updateHealth(healthy: Boolean): Unit = {
    val changed = isHealthy.compareAndSet(!healthy, healthy)
    if (changed) {
      healthGauge.set(healthy)
      logger.info(s"Health status changed to: $healthy for $serviceName")
    }
  }
  
  protected def updateConsumerLag(record: ConsumerRecord[_, _]): Unit = {
    try {
      val tp = new TopicPartition(record.topic(), record.partition())
      val consumer = consumerSettings.createKafkaConsumer()
      try {
        val endOffset = consumer.endOffsets(java.util.Collections.singleton(tp)).get(tp)
        val currentLag = endOffset - record.offset() - 1
        lagValue.set(currentLag)
        
        // Log warning if lag exceeds threshold
        if (currentLag > 1000) {
          logger.warn(s"High consumer lag detected: $currentLag messages for $serviceName on topic ${record.topic()}")
        }
      } finally {
        consumer.close()
      }
    } catch {
      case ex: Exception =>
        logger.error("Failed to update consumer lag", ex)
    }
  }
  
  protected def recordMessageProcessed(): Unit = {
    messageCounter.increment()
  }
  
  protected def recordProcessingError(): Unit = {
    processingErrorCounter.increment()
  }
  // Common Kafka consumer settings
  protected val consumerSettings: ConsumerSettings[Array[Byte], Array[Byte]] = {

    val clientId = s"${config.getKafkaClientId}-${getClass.getSimpleName}"
    ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(config.getKafkaBootstrapServers)
      .withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}")
      .withGroupId(s"${config.getKafkaGroupId}-${getClass.getSimpleName}")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
      .withProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "5000")
      .withPollInterval(50.milliseconds)
      .withPollTimeout(50.milliseconds)
  }
  protected val decider: Supervision.Decider = {
    case ex: Throwable =>
      logger.error("Unhandled exception in stream. Continuing processing stream and ignore this failed element.", ex)
      Supervision.Resume
  }

  protected val producerSettings =
    ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
      .withBootstrapServers(config.getKafkaBootstrapServers)
      .withClientId(s"${config.getKafkaClientId}-${getClass.getSimpleName}")

  // Template method that handles standard logging and health checks
  final def start(): Consumer.DrainingControl[Done] = {
    val control: Consumer.DrainingControl[Done] = startService()
    logger.info(s"$serviceName is running and listening for messages")
    
    // Schedule periodic health check
    implicit val scheduler: Scheduler = system.scheduler
    system.scheduler.scheduleWithFixedDelay(30.seconds, 30.seconds) { () =>
      // Check if the consumer control is still running
        control.streamCompletion.value match {
          case Some(scala.util.Success(_)) =>
            logger.error("Stream has completed successfully.")
            updateHealth(false)
          case Some(scala.util.Failure(exception)) =>
            logger.error(s"Stream failed with exception: ${exception.getMessage}", exception) 
            updateHealth(false)
            // Take corrective action, e.g., restart the service
          case None =>
            updateHealth(true)
        }
    }
    control
  }

  // Abstract method to be implemented by concrete processors
  protected def startService(): Consumer.DrainingControl[Done]

}
