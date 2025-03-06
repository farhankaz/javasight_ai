package com.farhankaz.javasight.utils

import scala.concurrent.Future

trait CodeAnalyzer {
  def analyzeFile(fileContent: String, projectContext: Option[String] = None, modelName: String = ""): Future[String]
  
  def analyzeFileShort(fileContent: String, projectContext: Option[String] = None, modelName: String = ""): Future[String]
  
  def analyzePackage(
    packageName: String, 
    fileAnalyses: Seq[String], 
    projectContext: Option[String] = None, 
    modelName: String = ""
  ): Future[String]
  
  def analyzeModule(
    moduleName: String,
    projectName: String,
    packageAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = ""
  ): Future[String]
  
  def analyzeProject(
    projectName: String,
    moduleAnalyses: Seq[String],
    projectContext: Option[String] = None,
    modelName: String = ""
  ): Future[String]
} 