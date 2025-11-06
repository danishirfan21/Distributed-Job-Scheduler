# Quick Start Guide

Get the Distributed Job Scheduler up and running in 5 minutes!

## Prerequisites

- Docker and Docker Compose installed
- 8GB RAM available
- Ports available: 5432, 6379, 9092, 8081-8083, 9090, 3000

## Step 1: Start the System

```bash
# Clone the repository
git clone <repository-url>
cd distributed-job-scheduler

# Start all services
docker-compose up -d
```

Wait for all services to start (about 60 seconds).

## Step 2: Verify Services

```bash
# Check all services are running
docker-compose ps

# Should see:
# - postgres (healthy)
# - redis (healthy)
# - kafka (healthy)
# - job-scheduler-service (running)
# - job-worker-1 (running)
# - job-worker-2 (running)
# - prometheus (running)
# - grafana (running)
```

Check health endpoints:
```bash
curl http://localhost:8081/actuator/health  # Scheduler
curl http://localhost:8082/actuator/health  # Worker 1
curl http://localhost:8083/actuator/health  # Worker 2
```

## Step 3: Create Your First Job

```bash
curl -X POST http://localhost:8081/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My First Job",
    "description": "Send a welcome email",
    "type": "EMAIL_NOTIFICATION",
    "priority": "HIGH",
    "parameters": {
      "to": "user@example.com",
      "subject": "Hello!",
      "body": "Welcome to Job Scheduler!"
    },
    "maxRetries": 3
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Job created successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "My First Job",
    "type": "EMAIL_NOTIFICATION",
    "enabled": true
  }
}
```

Save the job `id` for the next step!

## Step 4: Execute the Job

```bash
# Replace {job-id} with the ID from previous step
curl -X POST http://localhost:8081/api/v1/jobs/{job-id}/execute
```

**Response:**
```json
{
  "success": true,
  "message": "Job dispatched successfully",
  "data": {
    "executionId": "exec-123",
    "jobId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "QUEUED"
  }
}
```

## Step 5: Check Execution Status

```bash
# Check execution status
curl http://localhost:8081/api/v1/jobs/executions/{execution-id}

# Or check job history
curl http://localhost:8081/api/v1/jobs/{job-id}/history
```

## Step 6: View Monitoring Dashboard

1. Open Grafana: http://localhost:3000
2. Login: `admin` / `admin`
3. Go to Dashboards → Job Scheduler Dashboard
4. See real-time metrics!

## Step 7: Try More Features

### Create a Cron Job (Daily at 2 AM)

```bash
curl -X POST http://localhost:8081/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Daily Backup",
    "description": "Backup database every day",
    "type": "DATA_BACKUP",
    "cronExpression": "0 0 2 * * ?",
    "parameters": {
      "source": "/data/db",
      "destination": "/backups/daily"
    }
  }'
```

### Create Jobs with Dependencies (DAG)

```bash
# Job 1: Extract Data
JOB1=$(curl -s -X POST http://localhost:8081/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Extract Data",
    "type": "DATA_PROCESSING",
    "parameters": {"operation": "EXTRACT"}
  }' | jq -r '.data.id')

# Job 2: Transform Data (depends on Job 1)
JOB2=$(curl -s -X POST http://localhost:8081/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Transform Data\",
    \"type\": \"DATA_PROCESSING\",
    \"dependencies\": [\"$JOB1\"],
    \"parameters\": {\"operation\": \"TRANSFORM\"}
  }" | jq -r '.data.id')

# Job 3: Load Data (depends on Job 2)
curl -X POST http://localhost:8081/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Load Data\",
    \"type\": \"DATA_PROCESSING\",
    \"dependencies\": [\"$JOB2\"],
    \"parameters\": {\"operation\": \"LOAD\"}
  }"

# Execute the first job (others will follow automatically based on dependencies)
curl -X POST http://localhost:8081/api/v1/jobs/$JOB1/execute
```

## Exploring the System

### View All Jobs
```bash
curl http://localhost:8081/api/v1/jobs?page=0&size=20 | jq '.'
```

### View Prometheus Metrics
```bash
curl http://localhost:8081/actuator/prometheus
```

### View Logs
```bash
# Scheduler logs
docker logs job-scheduler-service -f

# Worker logs
docker logs job-worker-1 -f
docker logs job-worker-2 -f
```

## Scaling Workers

Need more throughput? Add more workers!

```bash
# Scale to 5 workers
docker-compose up -d --scale job-worker-service=5
```

## Common Operations

### List Running Containers
```bash
docker-compose ps
```

### Stop the System
```bash
docker-compose stop
```

### Start the System
```bash
docker-compose start
```

### View Service Logs
```bash
docker-compose logs -f [service-name]
```

### Restart a Service
```bash
docker-compose restart [service-name]
```

### Clean Up Everything
```bash
docker-compose down -v  # Warning: This deletes all data!
```

## Troubleshooting

### Services won't start
```bash
# Check if ports are in use
netstat -an | grep LISTEN | grep -E '(5432|6379|9092|8081|8082|8083|9090|3000)'

# Check Docker resources
docker system df
```

### Jobs not executing
```bash
# Check worker logs
docker logs job-worker-1 -f

# Check Kafka
docker logs job-scheduler-kafka -f

# Verify connectivity
docker exec job-worker-1 ping job-scheduler-service
```

### Can't connect to API
```bash
# Verify scheduler is running
curl http://localhost:8081/actuator/health

# Check logs
docker logs job-scheduler-service --tail 100
```

## Next Steps

- Read the full [README.md](README.md)
- Explore [ARCHITECTURE.md](ARCHITECTURE.md)
- Try example scripts in `examples/`
- Import Postman collection from `examples/postman-collection.json`

## Need Help?

- Check the logs: `docker-compose logs -f`
- View health status: `/actuator/health`
- Monitor metrics: http://localhost:9090 (Prometheus)
- View dashboard: http://localhost:3000 (Grafana)

Happy scheduling! 🚀
