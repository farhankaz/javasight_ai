package com.farhankaz.javasight.services

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}
import scala.concurrent.duration._
import com.farhankaz.javasight.utils.ConfigurationLoader
import com.farhankaz.javasight.utils.HealthCheckService
import com.farhankaz.javasight.utils.CodeAnalyzer

import io.micrometer.prometheus.{PrometheusConfig, PrometheusMeterRegistry}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.kontainers.micrometer.akka.AkkaMetricRegistry
import io.prometheus.client.CollectorRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import akka.kafka.scaladsl.Consumer
import org.slf4j.LoggerFactory
import akka.Done
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import org.mongodb.scala.MongoClient
import org.mongodb.scala.MongoDatabase
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.ServerBuilder
import redis.clients.jedis.Jedis
import com.farhankaz.javasight.utils

object ServiceApp extends App {
  
  implicit val system: ActorSystem = ActorSystem("javasight-processor-system")
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
    
  val logger = LoggerFactory.getLogger(getClass)
  val configLoader = ConfigurationLoader(args.headOption.getOrElse("local"))
  val mongoClient: MongoClient = MongoClient(configLoader.getMongoUri)
  val database: MongoDatabase = mongoClient.getDatabase("javasight")
  val redis = new utils.RedissonLock(configLoader)
  
  val healthCheckService = new HealthCheckService(mongoClient, configLoader)

  val prometheusRegistry: PrometheusMeterRegistry = startHealthCheckAndMetricsServer()
  val llmAnalyzer: CodeAnalyzer = new utils.BedrockAnalyzer(configLoader, prometheusRegistry)
  val controls: Seq[Consumer.DrainingControl[Done]] = startServices(llmAnalyzer)
  registerCoordinatorShutdownTask(prometheusRegistry, controls)

  def registerCoordinatorShutdownTask(prometheusRegistry: PrometheusMeterRegistry, controls: Seq[Consumer.DrainingControl[Done]]): Unit = {
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseServiceUnbind,  
      "shutdown-kafka-streams"
    ) { () =>
      prometheusRegistry.close()
      redis.shutdown()
      logger.info("Shutting down services...waiting for 20 seconds for processors to finish draining")
      val futures: Seq[Future[Done]] = controls.map(_.drainAndShutdown())
      Future.sequence(futures).map(_ => Done)
    }
  }

  def startServices(codeAnalyzer: CodeAnalyzer): Seq[Consumer.DrainingControl[Done]] = 
    Seq(
      new ProjectImportService(configLoader, prometheusRegistry, database),
      new ModuleImportService(configLoader, prometheusRegistry, database),
      new ModuleDirectoryScanService(configLoader, prometheusRegistry, database),
      new ModuleFileScanService(configLoader, prometheusRegistry, database),
      new FileAnalysisService(configLoader, prometheusRegistry, database, codeAnalyzer),
      new PackageAnalysisService(configLoader, prometheusRegistry, database, redis, codeAnalyzer),
      new ModuleAnalysisService(configLoader, prometheusRegistry, database, redis, codeAnalyzer),
      new ProjectAnalysisService(configLoader, prometheusRegistry, database, redis, codeAnalyzer),
      new PackageMetricsService(configLoader, prometheusRegistry, database),
      new ModuleMetricsService(configLoader, prometheusRegistry, database),
      new ProjectMetricsService(configLoader, prometheusRegistry, database)
    ).map(_.start())

  def startHealthCheckAndMetricsServer(): PrometheusMeterRegistry = {
    val prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    AkkaMetricRegistry.setRegistry(prometheusRegistry)
    val healthPort = configLoader.getHealthPort
    val combinedRoutes: Route = concat(
      path("metrics") { complete(prometheusRegistry.scrape()) },
      healthCheckService.routes
    )
    Http().newServerAt("0.0.0.0", healthPort).bind(combinedRoutes)
    prometheusRegistry
  }
}
