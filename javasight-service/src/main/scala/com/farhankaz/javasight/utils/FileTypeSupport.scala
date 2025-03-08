package com.farhankaz.javasight.utils

import java.io.File

/**
 * Utility object for supporting multiple file types in JavaSight
 */
object FileTypeSupport {
  // List of supported file extensions
  val supportedExtensions = Set(
    ".java", ".scala", ".js", ".ts", ".tsx", ".py", ".cpp", ".hs"
  )
  
  /**
   * Map file extension to language type
   *
   * @param fileName The name of the file
   * @return The language type as a string
   */
  def getFileType(fileName: String): String = {
    if (fileName.contains(".")) {
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
        case ".properties" => "properties"
        case ".yaml" => "yaml"
        case ".yml" => "yaml"
        case ".xml" => "xml"
        case ".kt" => "kotlin"
        case ".groovy" => "groovy"
        case ".sh" => "shell"
        case _ => "unknown"
      }
    } else {
      "unknown"
    }
  }
  
  /**
   * Check if a file is a supported source file (based only on extension)
   *
   * @param fileName The name of the file
   * @return true if the file is a supported source file, false otherwise
   */
  def isSourceFile(fileName: String): Boolean = {
    supportedExtensions.exists(ext => fileName.endsWith(ext))
  }
  
  /**
   * Check if a file should be filtered out (e.g., tests)
   *
   * @param filePath The name or path of the file
   * @return true if the file should be filtered, false if it should be included
   */
  def shouldFilterFile(filePath: String): Boolean = {
    val lowerPath = filePath.toLowerCase
    lowerPath.contains("test") || lowerPath.contains("target")
  }

  /**
   * Check if a file is a valid source file (supported extension and not filtered out)
   *
   * @param filePath The path of the file
   * @return true if the file is a valid source file, false otherwise
   */
  def isValidSourceFile(filePath: String): Boolean = {
    isSourceFile(filePath) && !shouldFilterFile(filePath)
  }
  
  /**
   * Get the directory name as a package name
   *
   * @param filePath The path of the file
   * @return The directory name
   */
  def getDirectoryAsPackage(filePath: String): String = {
    val file = new File(filePath)
    val parentDir = file.getParentFile
    if (parentDir != null) {
      parentDir.getName
    } else {
      ""
    }
  }
}
