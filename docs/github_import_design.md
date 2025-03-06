# Implementation Plan: GitHub URL Project Import for JavaSight

## 1. Frontend Components

### 1.1. "Add Project" Button
- Add a prominent "+ Add Project" button to the homepage header
- Position it near the "Java Projects" heading for visibility

### 1.2. GitHub Import Modal
- Create a new `ImportGithubProjectModal.tsx` component with:
  - Simple, elegant form layout with clear instructions
  - GitHub URL input field with validation
  - Project name field (auto-populated from repository name)
  - Import button with loading state
  - Visual progress indicator for import stages

### 1.3. Progress Tracking Component
- Create a `ProjectImportProgress.tsx` component that shows:
  - Step-by-step progress indicators (Cloning, Analyzing, Processing)
  - Real-time status updates from the backend
  - Success confirmation or error details

## 2. Backend API Additions

### 2.1. Project Import API Endpoint
- Update `/api/projects/route.ts` to add POST method that accepts:
  - GitHub repository URL
  - Optional custom project name

### 2.2. GitHub URL Validation
- Validate GitHub URLs for correct format
- Check repository is public and accessible
- Verify the repository contains a pom.xml file at root level

## 3. Backend Service Modifications

### 3.1. Kafka Protocol Extension
- Add new message type in `kafka.proto`:
  ```proto
  message ImportGithubProjectCommand {
    string project_name = 1;
    string github_url = 2;
    string project_context = 3;
    int64 timestamp = 4;
  }
  ```

### 3.2. Add New Kafka Topic
- Add in `KafkaTopic.scala`:
  ```scala
  case object ImportGithubProjectCommands extends KafkaTopic { 
    override def toString = "import_github_project_commands" 
  }
  ```

### 3.3. GitHub Project Import Service
- Create `GithubProjectImportService.scala` to:
  - Clone public GitHub repositories
  - Validate Maven project structure
  - Send progress updates via status collection
  - Handle the project import process
  - Report detailed errors for invalid repositories

## 4. Progress Tracking System

### 4.1. Status Collection in MongoDB
- Add a new `project_import_status` collection to track:
  - Current import stage
  - Progress percentage
  - Status messages
  - Error details (if any)

### 4.2. Server-Sent Events for Progress Updates
- Implement SSE endpoint for real-time progress:
  ```
  /api/projects/import/status/:importId
  ```

## 5. Implementation Steps (Frontend)

1. Add modal trigger button to homepage
2. Create modal component with form
3. Implement GitHub URL validation with regex pattern
4. Add form submission handler to call API
5. Create progress tracking UI with stages visualization
6. Implement SSE client for real-time updates
7. Handle success and error states with appropriate UI feedback

## 6. Implementation Steps (Backend)

1. Update Kafka protocol definition
2. Create new Kafka topic for GitHub imports
3. Implement GitHub project import service with:
   - Git command execution for cloning
   - Repository validation
   - Progress tracking and reporting
   - Error handling
4. Integrate with existing project processing pipeline
5. Add status tracking collection and API endpoints

## 7. Error Handling

- Validate GitHub URL format before submission
- Check repository accessibility before cloning
- Verify pom.xml existence after cloning
- Provide clear error messages for each failure scenario:
  - "Invalid GitHub URL format"
  - "Repository not found or not public"
  - "Not a valid Maven project (no pom.xml found)"
  - "Failed to clone repository"

## 8. Testing Plan

- Test public GitHub repositories with various structures
- Verify handling of non-Maven repositories
- Test progress updates for large repositories
- Ensure UI gracefully handles all error cases

## 9. Process Flow Diagram

```mermaid
sequenceDiagram
    participant User as User
    participant UI as JavaSight UI
    participant API as Next.js API
    participant Kafka as Kafka Broker
    participant GIS as GitHub Import Service
    participant Git as Git CLI
    participant MongoDB as MongoDB
    participant PIS as Project Import Service
    participant SSE as SSE Connection

    User->>UI: Enter GitHub URL & submit
    UI->>UI: Validate URL format
    UI->>API: POST /api/projects with GitHub URL
    API->>MongoDB: Create initial project record
    MongoDB-->>API: Return temporary project ID
    API->>Kafka: Send ImportGithubProjectCommand
    API-->>UI: Return import ID and initial status
    UI->>SSE: Subscribe to status updates
    
    Kafka->>GIS: Consume command
    GIS->>MongoDB: Update status (Started cloning)
    MongoDB->>SSE: Push status update
    SSE-->>UI: Status: "Cloning repository..."
    
    GIS->>Git: Clone repository
    Git-->>GIS: Repository cloned
    GIS->>GIS: Validate pom.xml existence
    GIS->>MongoDB: Update status (Validating project)
    MongoDB->>SSE: Push status update
    SSE-->>UI: Status: "Validating Maven project..."
    
    GIS->>MongoDB: Store project info
    GIS->>Kafka: Send ProjectImportedEvent
    GIS->>MongoDB: Update status (Processing started)
    MongoDB->>SSE: Push status update
    SSE-->>UI: Status: "Project imported, starting analysis..."
    
    Kafka->>PIS: Trigger regular project processing
    PIS->>MongoDB: Update project with analysis
    PIS->>MongoDB: Update status (Complete)
    MongoDB->>SSE: Push final status update
    SSE-->>UI: Status: "Import complete"
    
    UI-->>User: Show success notification
    UI->>UI: Add new project to projects list
```

This diagram illustrates the end-to-end flow of the GitHub import process, from user input to final project analysis.