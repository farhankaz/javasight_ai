package com.farhankaz.javasight.services

sealed trait KafkaTopic

object KafkaTopics {
    // commands
  case object ImportProjectCommands extends KafkaTopic { override def toString = "import_project_commands" }
  case object ImportMavenProjectCommands extends KafkaTopic { override def toString = "import_maven_project_commands" }
  case object ImportModuleCommands extends KafkaTopic { override def toString = "import_module_commands" }
  case object ScanModuleDirectoryCommands extends KafkaTopic { override def toString = "scan_module_directory_commands" }
  case object ScanModuleFileCommands extends KafkaTopic { override def toString = "scan_module_file_commands" }
  case object AnalyzeFileCommands extends KafkaTopic { override def toString = "analyze_file_commands" }
  case object AnalyzePackageCommands extends KafkaTopic { override def toString = "analyze_package_commands" }


  // events
  case object ProjectImportedEvents extends KafkaTopic { override def toString = "project_imported_events" }
  case object MavenProjectImportedEvents extends KafkaTopic { override def toString = "maven_project_imported_events" }
  case object ModuleImportedEvents extends KafkaTopic { override def toString = "module_imported_events" }
  case object ModuleDirectoryScannedEvents extends KafkaTopic { override def toString = "module_directory_scanned_events" }
  case object ModuleFileScannedEvents extends KafkaTopic { override def toString = "module_file_scanned_events" }
  case object FileAnalyzedEvents extends KafkaTopic { override def toString = "file_analyzed_events" }
  case object PackageAnalyzedEvents extends KafkaTopic { override def toString = "package_analyzed_events" }
  case object PackageDiscoveryEvents extends KafkaTopic { override def toString = "package_discovery_events" }
  case object ModuleAnalyzedEvents extends KafkaTopic { override def toString = "module_analyzed_events" }
  case object ProjectAnalyzedEvents extends KafkaTopic { override def toString = "project_analyzed_events" }

  case object ModuleMetricsEvent extends KafkaTopic { override def toString = "project_analyzed_events" }
}
