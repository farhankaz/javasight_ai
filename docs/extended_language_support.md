# Extended Language Support for JavaSight

This document outlines the implementation plan for extending JavaSight to support multiple programming languages beyond Java, including Scala, JavaScript, TypeScript, TSX, Python, C++, and Haskell.

## 1. Overview

Currently, JavaSight only analyzes Java files. This enhancement will extend the system to scan and analyze files of multiple languages, providing a more comprehensive view of codebases that use multiple languages.

## 2. Simplified Approach

Based on the requirements:

1. Every directory containing source files will be considered a code package
2. No AST libraries for non-Java files
3. Empty method arrays for non-Java files
4. Simple line counting for non-Java files

## 3. Implementation Plan

### 3.1. Refactor ModuleDirectoryScanService

#### A. Create a FileTypeSupport object
```scala
object FileTypeSupport {
  // List of supported file extensions
  val supportedExtensions = Set(
    ".java", ".scala", ".js", ".ts", ".tsx", ".py", ".cpp", ".hs"
  )
  
  // Map file extension to language type
  def getFileType(fileName: String): String = {
    val extension = fileName.substring(fileName.lastIndexOf("."))
    extension match {
      case ".java" => "java"
      case ".scala" => "scala"
      case ".js" => "javascript"
      case ".ts" => "typescript"
      case ".tsx" => "typescript-react"
      case ".py" => "python"
      case ".cpp" => "cpp"
      case ".hs" => "haskell"
      case _ => "unknown"
    }
  }
  
  // Check if a file is a supported source file
  def isSourceFile(fileName: String): Boolean = {
    supportedExtensions.exists(ext => fileName.endsWith(ext))
  }
}
```

#### B. Update file finding methods
```scala
def findSourceFiles(directory: String): Seq[String] = {
  val dir = new File(directory)
  if (dir.exists() && dir.isDirectory) {
    dir.listFiles()
      .filter(f => f.isFile && FileTypeSupport.isSourceFile(f.getName))
      .filterNot(f => f.getName.toLowerCase.contains("test"))
      .map(_.getAbsolutePath)
      .toSeq
  } else {
    Seq.empty
  }
}
```

#### C. Update directory scanning methods
```scala
def hasSourceFiles(d: File): Boolean = {
  if (d.isDirectory) {
    d.listFiles().exists(f =>
      (f.isFile && FileTypeSupport.isSourceFile(f.getName) &&
        !f.getName.toLowerCase.contains("test") && 
        !f.getPath.contains("test") && 
        !f.getPath().contains("target")) ||
      (f.isDirectory && hasSourceFiles(f))
    )
  } else false
}

def findChildSourceDirectories(directory: String): Seq[File] = {
  val dir = new File(directory)
  if (dir.exists() && dir.isDirectory) {
    dir.listFiles()
      .filter(_.isDirectory)
      .filter(hasSourceFiles)
      .toSeq
  } else {
    Seq.empty
  }
}
```

#### D. Keep package extraction for Java files only
```scala
def extractPackageName(filePath: String): Option[String] = {
  if (filePath.endsWith(".java")) {
    // Existing Java package extraction logic
    val fileContent = new String(Files.readAllBytes(Paths.get(filePath)))
    val parserConfiguration = new com.github.javaparser.ParserConfiguration()
      .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    val javaParser = new com.github.javaparser.JavaParser(parserConfiguration)
    
    try {
      val compilationUnit = javaParser.parse(fileContent).getResult.orElseThrow()
      compilationUnit.getPackageDeclaration.map(_.getNameAsString).toScala
    } catch {
      case e: Exception => 
        logger.warn(s"Failed to extract package from Java file: $filePath", e)
        None
    }
  } else {
    // For non-Java files, use directory name as package
    val file = new File(filePath)
    val parentDir = file.getParentFile
    Some(parentDir.getName)
  }
}
```

#### E. Update source file messages
```scala
def sourceFileMessages(msg: ScanModuleDirectoryCommand, packageId: String): Seq[ProducerRecord[Array[Byte],Array[Byte]]] = {
  findSourceFiles(msg.path).map { filePath =>
    val fileType = FileTypeSupport.getFileType(filePath)
    new ProducerRecord[Array[Byte], Array[Byte]](
      KafkaTopics.ScanModuleFileCommands.toString(),
      ScanModuleFileCommand(
        filePath = filePath,
        moduleId = msg.moduleId,
        projectId = msg.projectId,
        parentPackageId = Some(packageId),
        fileType = Some(fileType)  // Add fileType to ScanModuleFileCommand
      ).toByteArray
    )
  }
}
```

### 3.2. Refactor ModuleFileScanService

#### A. Update the JavaFile case class to handle all file types
```scala
case class SourceFile(
  fileId: ObjectId = new ObjectId(),
  projectId: String,
  moduleId: String,
  filePath: String,
  fileType: String,  // java, scala, js, etc.
  linesOfCode: Int,
  packageName: String,
  packageId: Option[String],
  publicMethods: List[String] = List.empty
) {
  def toModuleFileScannedEvent(): ModuleFileScannedEvent =
    ModuleFileScannedEvent(
      fileId = fileId.toString,
      moduleId = moduleId,
      projectId = projectId,
      filePath = filePath,
      fileType = Some(fileType),
      parentPackageId = packageId,
      timestamp = System.currentTimeMillis()
    )
}
```

#### B. Simplify the processFile method
```scala
private def processFile(file: ScanModuleFileCommand): SourceFile = {
  val fileType = file.fileType.getOrElse(FileTypeSupport.getFileType(file.filePath))
  
  // Count lines of code (simple approach for all file types)
  val lines = Files.readAllLines(Paths.get(file.filePath))
  val loc = lines.size()
  
  // Extract package name and methods only for Java files
  val (packageName, publicMethods) = if (fileType == "java") {
    try {
      val fileContent = new String(Files.readAllBytes(Paths.get(file.filePath)))
      val parserConfiguration = new com.github.javaparser.ParserConfiguration()
        .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
      val javaParser = new com.github.javaparser.JavaParser(parserConfiguration)
      val compilationUnit = javaParser.parse(fileContent).getResult.orElseThrow()
      
      val pkgName = compilationUnit.getPackageDeclaration
        .map(_.getNameAsString)
        .orElse("")
        
      val methods = compilationUnit.findAll(classOf[MethodDeclaration]).asScala
        .filter(m => m.isPublic && !isGetterOrSetter(m))
        .map(_.getSignature.asString())
        .toList
        
      (pkgName, methods)
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to process Java file: ${file.filePath}", e)
        (new File(file.filePath).getParentFile.getName, List.empty)
    }
  } else {
    // For non-Java files, use directory name as package and empty methods list
    (new File(file.filePath).getParentFile.getName, List.empty)
  }

  logger.info(s"Processed file ${file.filePath} of type ${fileType} with $loc lines of code")
  
  SourceFile(
    filePath = file.filePath,
    fileType = fileType,
    moduleId = file.moduleId,
    projectId = file.projectId,
    packageId = file.parentPackageId,
    packageName = packageName,
    linesOfCode = loc,
    publicMethods = publicMethods
  )
}
```

### 3.3. Update Protobuf Messages

We need to update the Kafka protobuf messages to include file type information:

```protobuf
message ScanModuleFileCommand {
  string module_id = 2;
  string project_id = 3;
  optional string parent_package_id = 4; 
  string file_path = 5;
  optional string file_type = 7;  // Add this field
  int64 timestamp = 6;
}

message ModuleFileScannedEvent {
  string module_id = 1;
  string project_id = 2;
  optional string parent_package_id = 3; 
  string file_path = 4;
  string file_id = 5;
  optional string file_type = 7;  // Add this field
  int64 timestamp = 6;
}
```

### 3.4. Database Changes

We have two options:

1. **Keep existing collection names** (`java_files`, `java_packages`, etc.) but add a `fileType` field to distinguish between different languages.

2. **Rename collections** to be more generic:
   - `java_files` → `source_files`
   - `java_packages` → `code_packages`

For simplicity, we recommend option 1 - keeping the existing collection names but adding the `fileType` field. This minimizes changes to other parts of the system that might be querying these collections.

## 4. Implementation Steps

1. Update the protobuf definitions to include file type information
2. Modify ModuleDirectoryScanService:
   - Create the FileTypeSupport object
   - Update file finding methods to support all file types
   - Update directory scanning methods
   - Update package extraction logic
   - Update source file message creation

3. Modify ModuleFileScanService:
   - Update the JavaFile case class to SourceFile
   - Simplify the processFile method to handle all file types
   - Update the MongoDB document structure

4. Test the changes with different file types

## 5. Advantages of This Approach

1. **Simplicity**: No complex AST parsing for non-Java files
2. **Minimal Changes**: Keeps the existing structure while adding support for new file types
3. **Extensibility**: Easy to add support for more file types in the future
4. **Backward Compatibility**: Existing Java file processing remains unchanged
