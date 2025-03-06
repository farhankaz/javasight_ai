# JavaSight Event Generator Utilities

This directory contains utilities for generating and testing events in the JavaSight system.

## EventGenerator

The `EventGenerator` class provides an easy way to generate Kafka events for testing various services in the JavaSight system.

### Usage

```scala
// Create the configuration and event generator
val configLoader = ConfigurationLoader()
val eventGenerator = new EventGenerator(configLoader)

// Send a ProjectAnalyzedEvent
eventGenerator.sendProjectAnalyzedEvent(projectId)
  .onComplete {
    case Success(_) => println("Event sent successfully!")
    case Failure(ex) => println(s"Failed to send event: ${ex.getMessage}")
  }

// Don't forget to close the producer when done
eventGenerator.close()
```

## ProjectLlmContextServiceTester

A command-line utility for testing the ProjectLlmContextService by sending ProjectAnalyzedEvent events.

### Usage

```bash
# Run the tester with sbt
sbt "runMain com.farhankaz.javasight.utils.ProjectLlmContextServiceTester 6780882ac8086c7190fa1027"
```

Replace `6780882ac8086c7190fa1027` with a valid project ID from your MongoDB database.

## Testing Workflow

To test the ProjectLlmContextService:

1. Make sure your local Kafka and MongoDB are running (e.g., via docker-compose)
2. Start the main ServiceApp in one terminal:
   ```bash
   sbt "runMain com.farhankaz.javasight.services.ServiceApp"
   ```
3. In another terminal, run the ProjectLlmContextServiceTester:
   ```bash
   sbt "runMain com.farhankaz.javasight.utils.ProjectLlmContextServiceTester 6780882ac8086c7190fa1027"
   ```
4. Check the logs of the ServiceApp to see the ProjectLlmContextService processing the event
5. Verify the results in MongoDB:
   ```javascript
   // Connect to MongoDB and run:
   db.project_llm_contexts.find({projectId: "6780882ac8086c7190fa1027"})
   ```

## Event Flow

1. `ProjectLlmContextServiceTester` sends a `ProjectAnalyzedEvent` to the `ProjectAnalyzedEvents` topic
2. `ProjectLlmContextService` consumes this event, generates context, and stores it in MongoDB
3. `ProjectLlmContextService` then publishes a `ProjectLlmContextGeneratedEvent` to the `ProjectLlmContextGeneratedEvents` topic
4. Any other services subscribed to `ProjectLlmContextGeneratedEvents` can process this event further