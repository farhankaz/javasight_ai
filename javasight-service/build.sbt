name := "javasight-service"

version := "0.1"

scalaVersion := "2.13.15"

fork in run := true
javaOptions in run += "-Dkanela.agent.libs.net.bytebuddy.experimental=true"

mainClass in (Compile, run) := Some("com.farhankaz.javasight.services.ServiceApp")
assembly / mainClass := Some("com.farhankaz.javasight.services.ServiceApp")
val testcontainersVersion = "0.41.0"
  
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.8.0",
  
  // Test dependencies
  "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-mongodb" % testcontainersVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-kafka" % testcontainersVersion % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.8.0" % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % "10.5.2" % Test,
  "com.typesafe.akka" %% "akka-stream-kafka" % "4.0.0",
  "org.mongodb.scala" %% "mongo-scala-driver" % "4.9.0",
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.1",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  "net.logstash.logback" % "logstash-logback-encoder" % "7.4",
  "com.typesafe.akka" %% "akka-slf4j" % "2.8.0",
  "software.amazon.awssdk" % "ssm" % "2.20.135",
  "software.amazon.awssdk" % "core" % "2.20.135",
  "com.typesafe" % "config" % "1.4.2",
  "com.github.scopt" %% "scopt" % "4.1.0",
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  
  // Metrics and monitoring
  "io.micrometer" % "micrometer-core" % "1.12.0",
  "io.micrometer" % "micrometer-registry-prometheus" % "1.12.0",
  "com.typesafe.akka" %% "akka-actor-typed" % "2.8.0",
  "io.kontainers" %% "micrometer-akka" % "0.12.3",
  // Health checks
  "com.typesafe.akka" %% "akka-http" % "10.5.2",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.5.2",
  
  // XML processing
  "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
  
  // Java code analysis
  "com.github.javaparser" % "javaparser-core" % "3.25.10",
  "org.cthing" % "locc4j" % "2.0.0",
  
  // Redis clients
  "redis.clients" % "jedis" % "5.1.0",
  "org.redisson" % "redisson" % "3.25.2",

  
  // Add Bedrock Runtime dependency
  "software.amazon.awssdk" % "bedrockruntime" % "2.28.10",
  
  // Add STS for AWS authentication
  "software.amazon.awssdk" % "sts" % "2.28.10",
  
  // JTokkit for token counting
  "com.knuddels" % "jtokkit" % "1.1.0"
)

Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value
)

assemblyMergeStrategy in assembly := {
  case PathList("google", "protobuf", _ @ _*) => MergeStrategy.discard
  case PathList("module-info.class") => MergeStrategy.discard
  case PathList("version.conf") => MergeStrategy.first
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
  case PathList("META-INF", "native-image", _, _, "native-image.properties") => MergeStrategy.discard
  case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
  
  // Handle Kamon dependency conflicts
  case PathList("kamon", "instrumentation", "akka", _ @ _*) => MergeStrategy.first
  case PathList("kamon", "instrumentation", "executor", _ @ _*) => MergeStrategy.first
  case PathList("kamon", "instrumentation", "kafka", _ @ _*) => MergeStrategy.first
  case PathList("kamon", "instrumentation", "http", _ @ _*) => MergeStrategy.first
  case PathList("kamon", "instrumentation", "trace", _ @ _*) => MergeStrategy.first
  
  case PathList("META-INF", "FastDoubleParser-NOTICE") => MergeStrategy.first
  
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
