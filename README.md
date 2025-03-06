# JavaSight

JavaSight is a comprehensive tool for analyzing Java codebases and generating detailed reports. It provides insights into business functionality, library dependencies, technology stacks, and code metrics at various levels of granularity.

## Overview

JavaSight helps development teams and architects understand complex Java codebases by providing:

- **Code Metrics**: Lines of code, file counts, and other metrics at project, module, package, and class levels
- **Dependency Analysis**: Identification and listing of third-party dependencies
- **Business Functionality**: AI-powered analysis of business logic and functionality
- **Visualization**: Web-based UI for exploring analysis results

## Architecture

JavaSight consists of two main components:

### 1. javasight-service

A Scala application that implements all analysis and reporting processes. It uses:

- **Akka Streams**: For reactive processing of analysis tasks
- **Kafka**: For event-driven communication between processes
- **MongoDB**: For storing analysis results and reports
- **Ollama/LLM**: For AI-powered code analysis
- **Prometheus/Grafana**: For monitoring and metrics

Each analysis process subscribes to specific Kafka topics, processes messages, and publishes results to response topics. The application is designed to be scalable and resilient.

### 2. javasight-ui

A Next.js TypeScript application that provides a user-friendly interface for exploring JavaSight analysis results. It features:

- **Project Overview**: Summary of projects with key metrics
- **Hierarchical Navigation**: Drill down from projects to modules to packages to files
- **Analysis Display**: Visualization of code analysis and metrics
- **Responsive Design**: Works on desktop and mobile devices

## Features

- **Hierarchical Analysis**: Analyze code at project, module, package, and class levels
- **Metrics Collection**: Track lines of code, file counts, and other metrics
- **Dependency Tracking**: Identify and list third-party dependencies
- **Business Logic Analysis**: AI-powered analysis of business functionality
- **Real-time Processing**: Event-driven architecture for responsive analysis
- **Monitoring**: Comprehensive metrics and health checks

## Local Development Setup

### Prerequisites

- Java 11+
- Scala 2.13+
- Node.js 18+
- Docker and Docker Compose

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/javasight.git
   cd javasight
   ```

2. Start the required services using Docker Compose:
   ```bash
   docker-compose up -d
   ```

   This will start:
   - Kafka (available at localhost:9092)
   - MongoDB (available at localhost:27017)
   - Redis (available at localhost:6379)
   - Prometheus (available at localhost:9090)
   - Grafana (available at localhost:3000)
   - Kafdrop (available at localhost:9000) - for Kafka monitoring

3. Build and run the javasight-service:
   ```bash
   cd javasight-service
   sbt run
   ```

4. Set up and run the javasight-ui:
   ```bash
   cd javasight-ui
   npm install
   npm run dev
   ```

5. Access the UI at http://localhost:3000

### Environment Variables

For the UI, you'll need to set the following environment variables:

- `NEXT_PUBLIC_API_URL`: The URL of your API (default: http://localhost:3000)
- `NEXT_PUBLIC_JAVASIGHT_API_KEY`: Your API key for authentication

### Accessing Monitoring

- **Grafana**: http://localhost:3000 (login with admin/grafana)
- **Prometheus**: http://localhost:9090
- **Kafdrop**: http://localhost:9000 (for Kafka topic inspection)

## Usage

1. Import a Java project for analysis
2. Wait for the analysis to complete
3. Explore the results through the UI:
   - View project-level metrics and analysis
   - Drill down into modules, packages, and files
   - Review business functionality analysis
   - Examine dependencies

## Configuration

### javasight-service

Configuration is managed through `application.conf` and can be overridden for production environments:

- Kafka connection settings
- MongoDB connection settings
- Redis configuration
- Ollama/LLM settings
- Monitoring parameters

### javasight-ui

The UI can be configured through environment variables and Next.js configuration.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT: https://opensource.org/license/mit
