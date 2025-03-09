# Refactoring Plan: Removal of Package Concept from JavaSight

## 1. Overview

This document outlines the plan for refactoring JavaSight to remove the package concept, establishing a new hierarchy:

```
Project →* Module →* File
```

Where:
- A module is defined as a generic directory that directly contains supported source code files
- Modules can have parent-child relationships (if a directory contains source files and has subdirectories that also contain source files)

## 2. Current Architecture

Currently, JavaSight implements a hierarchy of:

```
Project →* Module →* Package →* File
```

The package layer adds unnecessary complexity and redundancy, as highlighted in the requirements. This refactoring will remove this layer while preserving all essential functionality by establishing a module hierarchy instead.

### 2.1 New Module Hierarchy Design

In the new design:

1. A module is defined as a directory that directly contains supported source files
2. If a directory contains source files and also has subdirectories that contain source files:
   - The parent directory is a module
   - The subdirectories are also modules 
   - Child modules reference their parent via `parentModuleId`

For example, if we have a directory structure:
```
src/
├── main.java       # Source file
├── utils/
│   ├── helper.java # Source file
│   └── ...
└── models/
    ├── user.java   # Source file
    └── ...
```

This would result in three modules:
- `src` (parent module)
- `src/utils` (child module with parentModuleId referencing `src`)
- `src/models` (child module with parentModuleId referencing `src`)

## 3. Database Changes

### 3.1. Collections to Drop

- `packages`: This collection will be dropped entirely
- `packages_metrics`: This collection will be dropped entirely

### 3.2. Document Structure Changes

#### Module Collection

Current structure (implied from code):
```json
{
  "_id": ObjectId("67808829c8086c7190fa1028"),
  "projectId": "67808829c8086c7190fa1027",
  "moduleName": "ca_common",
  "modulePath": "/Users/farhankazmi/projects/its-ui-092024-b/blue2/CA/ca_common",
  "analysis": "The Java module `ca_common` within the CA project is organized into several packages",
  "analysisDate": 1736493947097
}
```

New structure:
```json
{
  "_id": ObjectId("67808829c8086c7190fa1028"),
  "projectId": "67808829c8086c7190fa1027",
  "moduleName": "ca_common",
  "modulePath": "/Users/farhankazmi/projects/its-ui-092024-b/blue2/CA/ca_common",
  "parentModuleId": "67808829c8086c7190fa1025", // New field for parent-child module relationship
  "analysis": "The Java module `ca_common` within the CA project...",
  "analysisDate": 1736493947097
}
```

#### Files Collection

Current structure:
```json
{
  "_id": ObjectId("67ccc14b4047b6106778cb7e"),
  "projectId": "67ccc0dde1200d6446156472",
  "moduleId": "67ccc1494047b6106778c699",
  "filePath": "/tmp/javasight-repos/67ccc0dde1200d6446156472/modules/openapi-generator-cli/src/main/java/org/openapitools/codegen/Constants.java",
  "fileType": "java",
  "linesOfCode": 8,
  "packageName": "org.openapitools.codegen",
  "packageId": "67ccc14a4047b6106778c8ae",
  "publicMethods": [],
  "analysisDate": 1741472148161,
  "analysisTokenCount": 2,
  "codeTokenCount": 71,
  "shortAnalysis": "dummy response"
}
```

New structure:
```json
{
  "_id": ObjectId("67ccc14b4047b6106778cb7e"),
  "projectId": "67ccc0dde1200d6446156472",
  "moduleId": "67ccc1494047b6106778c699",
  "filePath": "/tmp/javasight-repos/67ccc0dde1200d6446156472/modules/openapi-generator-cli/src/main/java/org/openapitools/codegen/Constants.java",
  "fileType": "java",
  "linesOfCode": 8,
  "directoryPath": "org/openapitools/codegen",  // Replace packageName with directoryPath
  "publicMethods": [],
  "analysisDate": 1741472148161,
  "analysisTokenCount": 2,
  "codeTokenCount": 71,
  "shortAnalysis": "dummy response"
}
```

The `packageId` field will be removed, and `packageName` will be replaced with `directoryPath` which represents the relative path within the module.

#### Module Metrics Collection

Update `module_metrics` to include totals that were previously in `package_metrics`:
```json
{
  "_id": ObjectId("67ccc1514047b6106778cf4d"),
  "moduleId": "67ccc1494047b6106778c689",
  "projectId": "67ccc0dde1200d6446156472",
  "parentModuleId": "67ccc1494047b6106778c123", // New field for parent module reference
  "fileCount": 6,
  "linesOfCode": 82,
  "combinedAnalysisTokenCount": 18,
  "combinedCodeTokenCount": 536,
  "timestamp": 1741472081423,
  "childModuleCount": 2, // New field to track number of child modules
  "directoryCount": 2  // New field to track number of directories
}
```

## 4. Kafka Message Changes

### 4.1. Messages to Remove

- `PackageAnalyzedEvent`
- `PackageDiscoveryEvent`
- `AnalyzePackageCommand`

### 4.2. Messages to Modify

Update the following messages to remove package references and add module hierarchy:

- `ImportModuleCommand`: Add `parentModuleId` field (optional)
- `ModuleImportedEvent`: Add `parentModuleId` field (optional)
- `ScanModuleDirectoryCommand`: Replace `parent_package_id` with `parentModuleId` (optional)
- `ScanModuleFileCommand`: Replace `parent_package_id` with `directoryPath` (string representation of directory structure)
- `ModuleFileScannedEvent`: Remove `parent_package_id` field
- `FileAnalyzedEvent`: Remove `package_id` field
- `ModuleAnalyzedEvent`: Add `parentModuleId` field (optional)

## 5. Service Modifications

### 5.1. Services to Remove

- `PackageAnalysisService.scala`: This service will be removed entirely
- `PackageMetricsService.scala`: This service will be removed entirely

### 5.2. Services to Modify

#### ModuleDirectoryScanService.scala

- Remove package document creation logic
- Implement module hierarchy detection
- Modify to identify source-containing directories as modules
- Track parent-child module relationships

```scala
def scanModuleDirectory(msg: ScanModuleDirectoryCommand): Seq[ProducerRecord[Array[Byte],Array[Byte]]] = {
  // First check if this directory contains source files directly
  val hasDirectSourceFiles = findSourceFiles(msg.path).nonEmpty
  
  // Find all child directories that contain source files
  val childSourceDirs = findChildSourceDirectories(msg.path)
  
  // If this directory has source files, it's a module
  val currentModuleMessages = if (hasDirectSourceFiles) {
    // Process files in this directory
    val fileMessages = findSourceFiles(msg.path).map { filePath =>
      val fileType = FileTypeSupport.getFileType(filePath)
      val relativePath = getRelativePath(msg.path, filePath)
      new ProducerRecord[Array[Byte], Array[Byte]](
        KafkaTopics.ScanModuleFileCommands.toString(),
        ScanModuleFileCommand(
          filePath = filePath,
          moduleId = msg.moduleId,
          projectId = msg.projectId,
          directoryPath = Some(relativePath),
          fileType = Some(fileType)
        ).toByteArray
      )
    }
    
    // Return file messages and this is a valid module
    fileMessages
  } else Seq.empty
  
  // Process child directories as potential modules
  val childModuleMessages = childSourceDirs.flatMap { dir =>
    // For each child directory with source files, create a new module
    val childModuleId = new ObjectId().toString
    
    // Create a module record for this child directory
    val moduleImportMsg = new ProducerRecord[Array[Byte], Array[Byte]](
      KafkaTopics.ImportModuleCommands.toString(),
      ImportModuleCommand(
        projectId = msg.projectId,
        name = dir.getName,
        location = dir.getAbsolutePath,
        parentModuleId = Some(msg.moduleId), // Set parent module relationship
        timestamp = System.currentTimeMillis()
      ).toByteArray
    )
    
    // Recursively scan this child module
    val childScanMsg = ScanModuleDirectoryCommand(
      path = dir.getAbsolutePath,
      moduleId = childModuleId,
      projectId = msg.projectId,
      parentModuleId = Some(msg.moduleId), // Set parent module relationship
      timestamp = System.currentTimeMillis()
    )
    
    Seq(moduleImportMsg) ++ scanModuleDirectory(childScanMsg)
  }
  
  // Combine messages from this module and child modules
  currentModuleMessages ++ childModuleMessages
}
```

#### ModuleImportService.scala

- Add support for the parent-child module relationship

```scala
def createModuleDocument(msg: ImportModuleCommand): Future[ModuleImportedEvent] = {
  val moduleId = new ObjectId()
  logger.info(s"Creating module document for ${msg.name} with ID ${moduleId}")
  
  val moduleDoc = Document(
    "_id" -> moduleId,
    "projectId" -> msg.projectId,
    "moduleName" -> msg.name,
    "modulePath" -> msg.location,
    "parentModuleId" -> msg.parentModuleId.orNull, // Add parent module relationship
    "analysisDate" -> System.currentTimeMillis()
  )
  
  modulesCollection.insertOne(moduleDoc)
    .toFuture()
    .map { _ =>
      logger.info(s"Successfully created module document for ${msg.name}")
      ModuleImportedEvent(
        moduleId = moduleId.toString,
        projectId = msg.projectId,
        parentModuleId = msg.parentModuleId, // Pass parent module relationship
        timestamp = System.currentTimeMillis()
      )
    }
}
```

#### ModuleFileScanService.scala

- Remove package references
- Update `SourceFile` case class to use `directoryPath` instead of `packageName`/`packageId`
- Update document creation logic

```scala
case class SourceFile(
  fileId: ObjectId = new ObjectId(),
  projectId: String,
  moduleId: String,
  filePath: String,
  fileType: String,
  linesOfCode: Int,
  directoryPath: String,  // Was packageName
  publicMethods: List[String] = List.empty
) {
  def toModuleFileScannedEvent(): ModuleFileScannedEvent =
    ModuleFileScannedEvent(
      fileId = fileId.toString,
      moduleId = moduleId,
      projectId = projectId,
      filePath = filePath,
      fileType = Some(fileType),
      timestamp = System.currentTimeMillis()
    )
}
```

#### ModuleAnalysisService.scala

- Remove package-specific analysis logic
- Support module hierarchy in analysis process
- Group files by directory path for analysis

```scala
private def analyzeModule(moduleId: String, projectId: String): Future[Option[ModuleAnalyzedEvent]] = {
  getModuleDocument(moduleId).flatMap { moduleDocOpt =>
    moduleDocOpt match {
      case Some(moduleDoc) if Option(moduleDoc.getString("analysis")).exists(_.nonEmpty) && 
        Option(moduleDoc.getLong("analysisDate")).exists(_ > (System.currentTimeMillis() - 3600000)) =>
        logger.debug(s"Skipping analysis for module $moduleId as it has recent analysis")
        Future.successful(None)
      case Some(moduleDoc) =>
        for {
          projectName <- getProjectName(moduleDoc.getString("projectId"))
          files <- getModuleFiles(moduleId)
          // Group files by directory path for analysis
          filesByDirectory = files.groupBy(_.getString("directoryPath"))
          // Get parent module info if it exists
          parentModuleOpt <- Option(moduleDoc.getString("parentModuleId"))
            .map(id => getModuleDocument(id).map(_.map(_.getString("moduleName"))))
            .getOrElse(Future.successful(None))
          // Get child modules if any exist
          childModules <- getChildModules(moduleId)
          projectContext <- getProjectContext(projectId)
          
          // Include hierarchy information in analysis
          analysisContext = Map(
            "parentModule" -> parentModuleOpt.getOrElse(""),
            "childModules" -> childModules.map(_.getString("moduleName")).mkString(", "),
            "hierarchyLevel" -> (if (parentModuleOpt.isEmpty) "Root" else "Child")
          )
          
          analysis <- generateModuleAnalysis(
            moduleId, 
            moduleDoc.getString("moduleName"), 
            filesByDirectory, 
            projectName, 
            projectContext,
            analysisContext
          )
          _ <- updateModuleAnalysis(moduleId, analysis)
        } yield {
          modulesAnalyzed.increment()
          logger.info(s"Successfully analyzed module ${moduleId}")
          Some(ModuleAnalyzedEvent(
            moduleId = moduleId,
            projectId = projectId,
            parentModuleId = Option(moduleDoc.getString("parentModuleId")),
            analysis = analysis,
            timestamp = System.currentTimeMillis()
          ))
        }
      case None =>
        moduleAnalysisFailures.increment()
        logger.error(s"Failed to analyze module ${moduleId}: no module document found")
        Future.successful(None)
    }
  }
}

// New helper method to get child modules
private def getChildModules(moduleId: String): Future[Seq[Document]] = {
  database.getCollection("modules")
    .find(equal("parentModuleId", moduleId))
    .toFuture()
}
```

#### ModuleMetricsService.scala

- Update to include directory counting logic
- Add hierarchy-aware metrics computation
- Absorb necessary functionality from PackageMetricsService

```scala
private def processModuleMetrics(event: ModuleAnalyzedEvent): Future[Unit] = {
  // Count files and get metrics for this module
  val filesPipeline = Seq(
    MongoAggregates.`match`(equal("moduleId", event.moduleId)),
    MongoAggregates.group("$moduleId",
      sum("fileCount", 1),
      sum("linesOfCode", "$linesOfCode"),
      sum("codeTokenCount", "$codeTokenCount"),
      sum("analysisTokenCount", "$analysisTokenCount"),
      addToSet("directories", "$directoryPath")
    )
  )
  
  // Get child module count
  val childModulesFuture = database.getCollection("modules")
    .countDocuments(equal("parentModuleId", event.moduleId))
    .toFuture()
  
  // Get parent module ID if available
  val parentModuleIdFuture = database.getCollection("modules")
    .find(equal("_id", new ObjectId(event.moduleId)))
    .projection(include("parentModuleId"))
    .first()
    .toFuture()
    .map(doc => Option(doc).flatMap(d => Option(d.getString("parentModuleId"))))
  
  for {
    metricsOpt <- filesCollection.aggregate(filesPipeline).headOption()
    childModuleCount <- childModulesFuture
    parentModuleIdOpt <- parentModuleIdFuture
    _ <- {
      val metricsDoc = metricsOpt match {
        case Some(metrics) => {
          val fileCount = metrics.getInteger("fileCount", 0)
          val linesOfCode = metrics.getInteger("linesOfCode", 0)
          val codeTokenCount = metrics.getInteger("codeTokenCount", 0)
          val analysisTokenCount = metrics.getInteger("analysisTokenCount", 0)
          val directories = metrics.getArray("directories", classOf[String]).asScala.toSet
          
          Document(
            "moduleId" -> event.moduleId,
            "projectId" -> event.projectId,
            "parentModuleId" -> parentModuleIdOpt.orNull,
            "fileCount" -> fileCount,
            "linesOfCode" -> linesOfCode,
            "directoryCount" -> directories.size,
            "childModuleCount" -> childModuleCount,
            "combinedCodeTokenCount" -> codeTokenCount,
            "combinedAnalysisTokenCount" -> analysisTokenCount,
            "timestamp" -> System.currentTimeMillis()
          )
        }
        case None => {
          // No files found
          Document(
            "moduleId" -> event.moduleId,
            "projectId" -> event.projectId,
            "parentModuleId" -> parentModuleIdOpt.orNull,
            "fileCount" -> 0,
            "linesOfCode" -> 0,
            "directoryCount" -> 0,
            "childModuleCount" -> childModuleCount,
            "combinedCodeTokenCount" -> 0,
            "combinedAnalysisTokenCount" -> 0,
            "timestamp" -> System.currentTimeMillis()
          )
        }
      }
      
      moduleMetricsCollection.insertOne(metricsDoc).toFuture()
    }
  } yield ()
}
```

### 5.3. Update FileTypeSupport.scala

- Rename `getDirectoryAsPackage` to `getDirectoryPath`
- Add method to extract relative path

```scala
/**
 * Get the relative directory path from a file path
 * 
 * @param modulePath The base module path
 * @param filePath The complete file path
 * @return The relative directory path
 */
def getRelativePath(modulePath: String, filePath: String): String = {
  val file = new File(filePath)
  val moduleDir = new File(modulePath)
  val relativePath = moduleDir.toPath().relativize(file.getParentFile().toPath()).toString()
  relativePath
}
```

## 6. UI Modifications

Since the UI references packages, several changes are needed:

### 6.1. API Routes to Remove

- `/app/api/projects/[id]/modules/[moduleId]/packages/[packageId]/route.ts`

### 6.2. Components to Remove

- `PackageCard.tsx`

### 6.3. Components to Modify

- Update `ModuleCard.tsx` to list files directly or grouped by directory
- Add module hierarchy visualization to show parent-child relationships
- Remove package references from breadcrumb navigation
- Update any metrics displays to remove package metrics and add module hierarchy metrics

```tsx
// Example update to ModuleCard.tsx to show parent-child relationships
export const ModuleCard = ({ module, projectId }) => {
  const [childModules, setChildModules] = useState([]);
  
  useEffect(() => {
    // Fetch child modules if any
    fetch(`/api/projects/${projectId}/modules/${module._id}/children`)
      .then(res => res.json())
      .then(data => setChildModules(data));
  }, [module._id, projectId]);
  
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <Link href={`/project/${projectId}/module/${module._id}`}>
            {module.moduleName}
          </Link>
          {module.parentModuleId && (
            <Badge variant="outline" className="ml-2">
              Child Module
            </Badge>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="text-sm text-gray-500">
          {module.analysis ? module.analysis.substring(0, 150) + "..." : "No analysis available"}
        </div>
        
        {childModules.length > 0 && (
          <div className="mt-4">
            <h4 className="text-sm font-medium">Child Modules:</h4>
            <div className="grid grid-cols-1 gap-2 mt-2">
              {childModules.map(child => (
                <div key={child._id} className="text-sm border p-2 rounded">
                  <Link href={`/project/${projectId}/module/${child._id}`}>
                    {child.moduleName}
                  </Link>
                </div>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
};
```

### 6.4. Pages to Remove

- `/app/project/[id]/module/[moduleId]/package/[packageId]/page.tsx`

### 6.5. New API Routes to Add

- `/app/api/projects/[id]/modules/[moduleId]/children/route.ts`: To fetch child modules for a given module

## 7. Data Migration Strategy

Since this is a breaking change to the data model, we need a migration plan:

1. Create a backup of all collections
2. Implement new data structures in parallel with old ones
3. Write a migration script to:
   - Extract directory paths from package names
   - Update file documents with directory paths
   - Identify module hierarchy relationships
   - Update module documents with parent-child relationships
   - Calculate new module metrics
   - Remove package references
4. Once migration is verified, drop package collections

## 8. Testing Plan

1. Unit tests for modified services
2. Integration tests for the new workflow
3. Verify metrics calculation with module hierarchy 
4. Test the UI components with the new data structure
5. Comprehensive end-to-end test on a sample project with nested directory structure

## 9. Implementation Steps

1. Update Protobuf definitions
2. Modify core services (ModuleDirectoryScanService, ModuleFileScanService, ModuleImportService)
3. Update analysis services to work without packages but with module hierarchy
4. Create migration scripts
5. Update UI components
6. Test and verify the new implementation
7. Deploy the changes

## 10. Module Hierarchy Detection Algorithm

The key to this refactoring is correctly identifying the module hierarchy. Here's a detailed algorithm for the migration process:

1. Scan the file system structure of each project
2. For each directory:
   - Check if it contains source files directly (not in subdirectories)
   - If yes, mark as a potential module
3. Build the directory tree with module markers
4. Establish parent-child relationships between modules:
   - A module B is a child of module A if:
     - B's directory path is a subdirectory of A's path
     - There is no other module between A and B in the directory hierarchy
5. Assign parentModuleId to each module based on these relationships
6. Update module documents with parentModuleId values

## 11. Estimated Timeline

1. Protobuf and Service modifications: 3-4 days
2. Module hierarchy detection implementation: 2-3 days
3. Database migration scripts: 2-3 days
4. UI modifications: 1-2 days
5. Testing and verification: 3-4 days
6. Deployment and monitoring: 1 day

Total estimated time: 12-17 days