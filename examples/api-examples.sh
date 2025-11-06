#!/bin/bash

# Distributed Job Scheduler - API Examples
# This script demonstrates various API calls to the Job Scheduler Service

BASE_URL="http://localhost:8081/api/v1"

echo "========================================="
echo "Distributed Job Scheduler - API Examples"
echo "========================================="
echo ""

# 1. Create a simple email notification job
echo "1. Creating Email Notification Job..."
EMAIL_JOB=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Welcome Email",
    "description": "Send welcome email to new user",
    "type": "EMAIL_NOTIFICATION",
    "priority": "HIGH",
    "parameters": {
      "to": "newuser@example.com",
      "subject": "Welcome to Our Platform!",
      "body": "Thank you for joining us!"
    },
    "maxRetries": 3,
    "retryDelaySeconds": 60,
    "timeoutSeconds": 300
  }')

EMAIL_JOB_ID=$(echo $EMAIL_JOB | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "Created Email Job: $EMAIL_JOB_ID"
echo ""

# 2. Create a report generation job
echo "2. Creating Report Generation Job..."
REPORT_JOB=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Monthly Sales Report",
    "description": "Generate monthly sales report",
    "type": "REPORT_GENERATION",
    "priority": "NORMAL",
    "parameters": {
      "reportType": "SALES_MONTHLY",
      "format": "PDF",
      "month": "2025-01"
    },
    "maxRetries": 2,
    "timeoutSeconds": 600
  }')

REPORT_JOB_ID=$(echo $REPORT_JOB | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "Created Report Job: $REPORT_JOB_ID"
echo ""

# 3. Create a data backup job
echo "3. Creating Data Backup Job..."
BACKUP_JOB=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Database Backup",
    "description": "Backup production database",
    "type": "DATA_BACKUP",
    "priority": "CRITICAL",
    "parameters": {
      "source": "/data/production/db",
      "destination": "/backups/db/2025-01"
    },
    "maxRetries": 1,
    "timeoutSeconds": 1800
  }')

BACKUP_JOB_ID=$(echo $BACKUP_JOB | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "Created Backup Job: $BACKUP_JOB_ID"
echo ""

# 4. Create a cron-scheduled job
echo "4. Creating Cron-Scheduled Job (Daily at 2 AM)..."
CRON_JOB=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Daily Cleanup",
    "description": "Clean up temporary files daily",
    "type": "DATA_PROCESSING",
    "cronExpression": "0 0 2 * * ?",
    "priority": "LOW",
    "parameters": {
      "dataSource": "/temp",
      "operation": "CLEANUP"
    }
  }')

CRON_JOB_ID=$(echo $CRON_JOB | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "Created Cron Job: $CRON_JOB_ID"
echo ""

# 5. Create jobs with dependencies (DAG)
echo "5. Creating Job DAG (Job1 -> Job2 -> Job3)..."

# First job in DAG
DAG_JOB1=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Data Processing Stage 1",
    "description": "Initial data processing",
    "type": "DATA_PROCESSING",
    "priority": "HIGH",
    "parameters": {
      "dataSource": "/raw-data",
      "operation": "EXTRACT"
    }
  }')

DAG_JOB1_ID=$(echo $DAG_JOB1 | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "  Created DAG Job 1: $DAG_JOB1_ID"

# Second job depends on first
DAG_JOB2=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Data Processing Stage 2\",
    \"description\": \"Transform processed data\",
    \"type\": \"DATA_PROCESSING\",
    \"priority\": \"HIGH\",
    \"dependencies\": [\"$DAG_JOB1_ID\"],
    \"parameters\": {
      \"dataSource\": \"/processed-data\",
      \"operation\": \"TRANSFORM\"
    }
  }")

DAG_JOB2_ID=$(echo $DAG_JOB2 | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "  Created DAG Job 2: $DAG_JOB2_ID (depends on Job 1)"

# Third job depends on second
DAG_JOB3=$(curl -s -X POST "${BASE_URL}/jobs" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Data Processing Stage 3\",
    \"description\": \"Load transformed data\",
    \"type\": \"DATA_PROCESSING\",
    \"priority\": \"HIGH\",
    \"dependencies\": [\"$DAG_JOB2_ID\"],
    \"parameters\": {
      \"dataSource\": \"/transformed-data\",
      \"operation\": \"LOAD\"
    }
  }")

DAG_JOB3_ID=$(echo $DAG_JOB3 | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "  Created DAG Job 3: $DAG_JOB3_ID (depends on Job 2)"
echo ""

# 6. Execute jobs
echo "6. Executing Jobs..."

echo "  Executing Email Job..."
curl -s -X POST "${BASE_URL}/jobs/${EMAIL_JOB_ID}/execute" | jq '.'

sleep 2

echo "  Executing Report Job..."
curl -s -X POST "${BASE_URL}/jobs/${REPORT_JOB_ID}/execute" | jq '.'

sleep 2

echo "  Executing Backup Job..."
curl -s -X POST "${BASE_URL}/jobs/${BACKUP_JOB_ID}/execute" | jq '.'

echo ""

# 7. List all jobs
echo "7. Listing All Jobs..."
curl -s "${BASE_URL}/jobs?page=0&size=10" | jq '.data.content[] | {id: .id, name: .name, type: .type}'
echo ""

# 8. Get job details
echo "8. Getting Job Details..."
curl -s "${BASE_URL}/jobs/${EMAIL_JOB_ID}" | jq '.data'
echo ""

# 9. Get job execution history
sleep 5
echo "9. Getting Job Execution History..."
curl -s "${BASE_URL}/jobs/${EMAIL_JOB_ID}/history?page=0&size=10" | jq '.data.content[] | {id: .id, status: .status, startedAt: .startedAt, completedAt: .completedAt}'
echo ""

# 10. Test job cancellation
echo "10. Testing Job Cancellation..."
CANCEL_TEST=$(curl -s -X POST "${BASE_URL}/jobs/${BACKUP_JOB_ID}/execute")
EXECUTION_ID=$(echo $CANCEL_TEST | grep -o '"executionId":"[^"]*' | cut -d'"' -f4)

if [ ! -z "$EXECUTION_ID" ]; then
  echo "  Created execution: $EXECUTION_ID"
  sleep 1
  echo "  Cancelling execution..."
  curl -s -X POST "${BASE_URL}/jobs/executions/${EXECUTION_ID}/cancel" | jq '.'
else
  echo "  Could not create execution for cancellation test"
fi
echo ""

echo "========================================="
echo "API Examples Completed!"
echo "========================================="
echo ""
echo "You can now:"
echo "  - View jobs at: ${BASE_URL}/jobs"
echo "  - Monitor metrics at: http://localhost:8081/actuator/prometheus"
echo "  - View Grafana dashboard at: http://localhost:3000"
echo ""
