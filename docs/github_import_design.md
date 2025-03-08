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
- Validate GitHub URLs for correct format using regex pattern
- Repository accessibility is checked during the clone operation
- Project type is detected and validated after successful cloning (Maven, Gradle, Ant, SBT, or plain Java)

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
- Note: The import_id is not part of the proto definition but is extracted from the JSON message in the service implementation

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
  - Detect and validate project type (Maven, Gradle, Ant, SBT, or plain Java)
  - Send progress updates via status collection with detailed progress percentages
  - Handle the project import process
  - Report detailed errors for invalid repositories
  - Extract fields from JSON messages using regex patterns
  - Read README.md content from cloned repository to use as project context if available
  - Parse project modules based on project type:
    - Maven: Parse modules from pom.xml
    - Gradle: Parse modules from settings.gradle or settings.gradle.kts
    - Other project types: Treat as single module

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

- Validate GitHub URL format before submission using regex pattern in frontend
- Check repository accessibility during the clone operation
- Detect and validate project type after successful cloning
- Clean up partial clones on failure
- Update import status with detailed error messages
- Provide clear error messages for each failure scenario:
  - "Invalid GitHub URL format"
  - "Git clone failed with exit code X" (when repository is not found or not public)
  - "Invalid pom.xml file: [specific XML parsing error]" (for Maven projects)
  - "Unable to determine project type. No recognized build files found and no Java files detected."
  - Project-type specific validation errors

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
    participant ImportCmd as Kafka: import_github_project_commands
    participant GIS as GitHub Import Service
    participant Git as Git CLI
    participant MongoDB as MongoDB
    participant ImportedEvt as Kafka: project_imported_events
    participant Analysis as Analysis Services
    participant SSE as SSE Connection

    User->>UI: Enter GitHub URL & submit
    activate UI
    UI->>UI: Validate URL format with regex
    UI->>API: POST /api/projects with GitHub URL
    activate API
    API->>MongoDB: Create initial import status record with _id
    activate MongoDB
    MongoDB-->>API: Return import ID
    deactivate MongoDB
    API->>ImportCmd: Send JSON message with import_id
    activate ImportCmd
    API-->>UI: Return import ID and initial status
    deactivate API
    UI->>SSE: Subscribe to status updates
    activate SSE
    
    ImportCmd->>GIS: Consume JSON message
    activate GIS
    deactivate ImportCmd
    GIS->>GIS: Extract fields using regex patterns
    GIS->>MongoDB: Update status (Started cloning, 10%)
    activate MongoDB
    MongoDB->>SSE: Push status update
    deactivate MongoDB
    SSE-->>UI: Status: "Initiating GitHub repository clone"
    
    GIS->>Git: Clone repository
    activate Git
    Git-->>GIS: Repository cloned
    deactivate Git
    GIS->>MongoDB: Update status (Cloned, 40%)
    activate MongoDB
    MongoDB->>SSE: Push status update
    deactivate MongoDB
    SSE-->>UI: Status: "Repository cloned successfully"
    
    GIS->>GIS: Detect project type (Maven, Gradle, etc.)
    GIS->>GIS: Validate project structure
    GIS->>MongoDB: Update status (Validating project, 60%)
    activate MongoDB
    MongoDB->>SSE: Push status update
    deactivate MongoDB
    SSE-->>UI: Status: "Project detected as X type"
    
    GIS->>GIS: Read README.md for project context
    GIS->>GIS: Parse project modules based on project type
    GIS->>MongoDB: Store project info and context
    activate MongoDB
    MongoDB-->>GIS: Confirm storage
    GIS->>MongoDB: Update status (Importing, 70%)
    MongoDB->>SSE: Push status update
    deactivate MongoDB
    SSE-->>UI: Status: "Importing project..."
    
    GIS->>ImportedEvt: Send ProjectImportedEvent with modules
    activate ImportedEvt
    GIS->>MongoDB: Update status (Processing started, 80%)
    activate MongoDB
    MongoDB->>SSE: Push status update
    deactivate MongoDB
    deactivate GIS
    SSE-->>UI: Status: "Project imported, starting analysis..."
    
    ImportedEvt->>Analysis: Trigger analysis services
    activate Analysis
    deactivate ImportedEvt
    
    Note over Analysis: Multiple analysis services process the project
    
    Analysis->>ProjectAnalysis: ModuleAnalyzedEvent (for all modules)
    activate ProjectAnalysis
    Note right of ProjectAnalysis: ProjectAnalysisService
    ProjectAnalysis->>MongoDB: Update project with analysis
    activate MongoDB
    ProjectAnalysis->>MongoDB: Update status (Complete, 100%)
    MongoDB->>SSE: Push final status update
    deactivate MongoDB
    deactivate ProjectAnalysis
    deactivate Analysis
    SSE-->>UI: Status: "Import complete"
    deactivate SSE
    
    UI-->>User: Show success notification
    UI->>UI: Add new project to projects list
    deactivate UI
```

This diagram illustrates the end-to-end flow of the GitHub import process, from user input to final project analysis.
