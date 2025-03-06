package com.farhankaz.javasight.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.config.Config
import org.mongodb.scala.MongoClient
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import com.farhankaz.javasight.utils.ConfigurationLoader

case class SystemHealth(
  memory: Map[String, Long],
  processors: Int
)

case class HealthCheckResponse(
  kafka: Boolean,
  mongodb: Boolean,
  system: SystemHealth
)

case class ErrorResponse(error: String)

trait HealthCheckJsonProtocol extends DefaultJsonProtocol {
  implicit val systemHealthFormat: RootJsonFormat[SystemHealth] = jsonFormat2(SystemHealth)
  implicit val healthCheckResponseFormat: RootJsonFormat[HealthCheckResponse] = jsonFormat3(HealthCheckResponse)
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse)
}

object HealthCheckJsonProtocol extends HealthCheckJsonProtocol

class HealthCheckService(mongoClient: MongoClient, configLoader: ConfigurationLoader)(implicit system: ActorSystem, ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val kafkaBootstrapServers = configLoader.getKafkaBootstrapServers
  // private val mongoClient: MongoClient = MongoClient(configLoader.getMongoUri)
  private val healthPort = configLoader.getHealthPort

  private def checkKafka(): Future[Boolean] = {
    // TODO: Implement actual Kafka connectivity check
    Future.successful(true)
  }

  private def checkMongoDB(): Future[Boolean] = {
    mongoClient.listDatabaseNames().toFuture()
      .map(_ => true)
      .recover {
        case ex =>
          logger.error("MongoDB health check failed", ex)
          false
      }
  }

  private def checkSystem(): Map[String, Any] = {
    val runtime = Runtime.getRuntime
    Map(
      "memory" -> Map(
        "free" -> runtime.freeMemory(),
        "total" -> runtime.totalMemory(),
        "max" -> runtime.maxMemory()
      ),
      "processors" -> runtime.availableProcessors()
    )
  }

  val routes: Route = pathPrefix("health") {
    concat(
      path("live") {
        get {
          complete(StatusCodes.OK)
        }
      },
      path("ready") {
        get {
          onComplete(checkKafka().zip(checkMongoDB())) {
            case Success((kafkaOk, mongoOk)) if kafkaOk && mongoOk =>
              complete(StatusCodes.OK)
            case Success(_) =>
              complete(StatusCodes.ServiceUnavailable)
            case Failure(ex) =>
              logger.error("Health check failed", ex)
              complete(StatusCodes.InternalServerError)
          }
        }
      },
      path("check") {
        get {
          onComplete(checkKafka().zip(checkMongoDB())) {
            case Success((kafkaOk, mongoOk)) =>
              import HealthCheckJsonProtocol._
              complete(StatusCodes.OK -> HealthCheckResponse(
                kafkaOk,
                mongoOk,
                SystemHealth(
                  checkSystem()("memory").asInstanceOf[Map[String, Long]],
                  checkSystem()("processors").asInstanceOf[Int]
                )
              ))
            case Failure(ex) =>
              logger.error("Health check failed", ex)
              import HealthCheckJsonProtocol._
              complete(StatusCodes.InternalServerError -> ErrorResponse(ex.getMessage))
          }
        }
      }
    )
  }
}