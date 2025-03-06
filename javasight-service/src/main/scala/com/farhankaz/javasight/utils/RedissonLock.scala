package com.farhankaz.javasight.utils

import org.redisson.Redisson
import org.redisson.api.{RSemaphore, RedissonClient}
import org.redisson.config.Config

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.jdk.DurationConverters._

class RedissonLock(config: ConfigurationLoader)(implicit ec: ExecutionContext) {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  
  private val redissonConfig = {
    val cfg = new Config()
    cfg.useSingleServer()
      .setAddress(s"redis://${config.redisHost}:${config.redisPort}")
      .setConnectionPoolSize(64)
      .setConnectionMinimumIdleSize(24)
      .setRetryAttempts(5)
      .setRetryInterval(1000)
      .setDnsMonitoringInterval(5000)
    cfg
  }
  
  private val redisson: RedissonClient = Redisson.create(redissonConfig)
  
  // Default timeout for permit acquisition
  private val acquireTimeout = 30.seconds
  // Expiry time for semaphore keys
  private val keyExpiry = 5.minutes

  def withLock[T](key: String)(block: => Future[T]): Future[T] = {
    val semaphoreKey = s"semaphore:$key"
    val semaphore = redisson.getSemaphore(semaphoreKey)
    
    // Initialize semaphore if it doesn't exist
    initializeSemaphore(semaphore)
    
    // Try to acquire a permit
    val acquireFuture = Future.fromTry(Try {
      val acquired = semaphore.tryAcquire(
        acquireTimeout.toJava.toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )
      
      if (acquired) {
        logger.debug(s"Acquired permit for key: $key")
        // Set expiry on the semaphore key
        redisson.getKeys().expire(
          semaphoreKey,
          keyExpiry.toJava.toSeconds,
          java.util.concurrent.TimeUnit.SECONDS
        )
      } else {
        logger.warn(s"Could not acquire permit for key: $key")
      }
      
      acquired
    })
    
    acquireFuture.flatMap { acquired =>
      if (acquired) {
        // Execute the block and ensure permit is released
        block.transformWith { result =>
          // Always release the permit
          Try(semaphore.release()) match {
            case Success(_) =>
              logger.debug(s"Released permit for key: $key")
            case Failure(releaseEx) =>
              logger.error(s"Error releasing permit for key: $key", releaseEx)
          }
          
          Future.fromTry(result)
        }
      } else {
        Future.failed(new RuntimeException(s"Could not acquire permit for key: $key"))
      }
    }.recoverWith { case ex =>
      logger.error(s"Error with semaphore operation for key: $key", ex)
      Future.failed(ex)
    }
  }
  
  private def initializeSemaphore(semaphore: RSemaphore): Unit = {
    try {
      // Set permits to 1 if not already set
      if (semaphore.availablePermits() <= 0) {
        semaphore.trySetPermits(1)
      }
    } catch {
      case ex: Exception =>
        logger.error("Error initializing semaphore", ex)
    }
  }

  def shutdown(): Unit = {
    Try {
      redisson.shutdown()
    } recover {
      case ex => logger.error("Error shutting down Redisson client", ex)
    }
  }
}
