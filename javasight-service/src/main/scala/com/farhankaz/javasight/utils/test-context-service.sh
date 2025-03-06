#!/bin/bash

# Script to test the ProjectLlmContextService by sending a test event
# This script simplifies the testing process by wrapping the SBT command

if [ $# -eq 0 ]; then
  echo "Error: No project ID provided"
  echo "Usage: ./test-context-service.sh <projectId>"
  echo "Example: ./test-context-service.sh 6780882ac8086c7190fa1027"
  exit 1
fi

PROJECT_ID=$1

# Change to the project root directory
cd "$(dirname "$0")/../../../../../.." || exit

echo "Running ProjectLlmContextServiceTester with project ID: $PROJECT_ID"
echo "This will send a ProjectAnalyzedEvent to trigger the ProjectLlmContextService"

# Run the tester through SBT
sbt "runMain com.farhankaz.javasight.utils.ProjectLlmContextServiceTester $PROJECT_ID"

echo ""
echo "To check the results in MongoDB, run the following command:"
echo "db.project_llm_contexts.find({projectId: \"$PROJECT_ID\"})"
echo ""