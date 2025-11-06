# Distributed Job Scheduler

A highly scalable, fault-tolerant distributed job scheduling system built with Spring Boot, similar to Apache Airflow, featuring DAG-based job dependencies, multi-threaded execution, and comprehensive monitoring.

## Features

- **Distributed Job Execution:** Multi-worker architecture with horizontal scalability
- **DAG Support:** Define job dependencies with automatic cycle detection
- **Cron Scheduling:** Schedule jobs using cron expressions
- **Retry Logic:** Automatic retry with exponential backoff
- **Distributed Locking:** Redis-based locking to prevent duplicate execution
- **Multi-threaded Execution:** Parallel job execution using Java ExecutorService
- **REST API:** Comprehensive APIs for job management
- **OAuth2 Authentication:** Secure access with JWT tokens
- **Monitoring:** Prometheus metrics with Grafana dashboards
- **Fault Tolerance:** Graceful handling of component failures

## Architecture

The system consists of three main components:

1. **Job Scheduler Service:** Manages job definitions, validates DAGs, and dispatches jobs
2. **Job Worker Service:** Executes jobs in parallel and reports status
3. **Infrastructure:** PostgreSQL, Redis, Kafka, Prometheus, Grafana

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed architecture documentation.

## Technology Stack

- **Backend:** Spring Boot 3.2, Java 17
- **Database:** PostgreSQL 15
- **Cache/Lock:** Redis 7
- **Message Queue:** Apache Kafka 3.6
- **Monitoring:** Prometheus + Grafana
- **Containerization:** Docker, Docker Compose
- **Testing:** JUnit 5, Mockito, Spring Boot Test

## Quick Start

### Prerequisites

- Docker and Docker Compose
- Java 17 (for local development)
- Maven 3.9+ (for local development)

### Running with Docker Compose

1. **Clone the repository:**
```bash
git clone <repository-url>
cd distributed-job-scheduler
```

2. **Start all services:**
```bash
docker-compose up -d
```

This will start:
- PostgreSQL (port 5432)
- Redis (port 6379)
- Kafka (port 9092)
- Job Scheduler Service (port 8081)
- Job Worker Service - Instance 1 (port 8082)
- Job Worker Service - Instance 2 (port 8083)
- Prometheus (port 9090)
- Grafana (port 3000)

3. **Check service health:**
```bash
# Scheduler health
curl http://localhost:8081/actuator/health

# Worker 1 health
curl http://localhost:8082/actuator/health

# Worker 2 health
curl http://localhost:8083/actuator/health
```

4. **Access Grafana:**
- URL: http://localhost:3000
- Username: `admin`
- Password: `admin`

### Local Development

1. **Build the project:**
```bash
mvn clean install
```

2. **Start infrastructure:**
```bash
docker-compose up -d postgres redis kafka zookeeper
```

3. **Run Scheduler Service:**
```bash
cd job-scheduler-service
mvn spring-boot:run
```

4. **Run Worker Service:**
```bash
cd job-worker-service
mvn spring-boot:run
```

## API Documentation

### Create a Job

**POST** `/api/v1/jobs`

```json
{
  "name": "Send Welcome Email",
  "description": "Send welcome email to new users",
  "type": "EMAIL_NOTIFICATION",
  "priority": "HIGH",
  "parameters": {
    "to": "user@example.com",
    "subject": "Welcome!",
    "body": "Welcome to our platform!"
  },
  "maxRetries": 3,
  "retryDelaySeconds": 60,
  "timeoutSeconds": 300
}
```

**Response:**
```json
{
  "success": true,
  "message": "Job created successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Send Welcome Email",
    "type": "EMAIL_NOTIFICATION",
    "enabled": true,
    "createdAt": "2025-01-15 10:30:00"
  }
}
```

### Create a Job with Dependencies (DAG)

```json
{
  "name": "Generate and Email Report",
  "description": "Generate report then email it",
  "type": "EMAIL_NOTIFICATION",
  "dependencies": ["report-generation-job-id"],
  "parameters": {
    "to": "manager@example.com",
    "subject": "Daily Report"
  }
}
```

### Create a Cron Job

```json
{
  "name": "Daily Backup",
  "description": "Backup database daily at 2 AM",
  "type": "DATA_BACKUP",
  "cronExpression": "0 0 2 * * ?",
  "parameters": {
    "source": "/data/database",
    "destination": "/backup/daily"
  }
}
```

### Execute a Job

**POST** `/api/v1/jobs/{jobId}/execute`

**Response:**
```json
{
  "success": true,
  "message": "Job dispatched successfully",
  "data": {
    "executionId": "exec-123",
    "jobId": "job-456",
    "status": "QUEUED",
    "createdAt": "2025-01-15 10:35:00"
  }
}
```

### Get Job History

**GET** `/api/v1/jobs/{jobId}/history?page=0&size=20`

### Get Execution Status

**GET** `/api/v1/jobs/executions/{executionId}`

### Cancel Execution

**POST** `/api/v1/jobs/executions/{executionId}/cancel`

### Delete Job

**DELETE** `/api/v1/jobs/{jobId}`

## Job Types

The system supports the following job types out of the box:

1. **EMAIL_NOTIFICATION:** Send email notifications
2. **REPORT_GENERATION:** Generate reports (PDF, CSV, etc.)
3. **DATA_BACKUP:** Backup data to specified destination
4. **DATA_PROCESSING:** Process data with custom operations
5. **FILE_UPLOAD:** Upload files to storage
6. **CUSTOM:** Custom job implementation

### Extending with Custom Job Types

Implement the `JobExecutor` interface:

```java
@Component
public class MyCustomExecutor implements JobExecutor {

    @Override
    public Map<String, Object> execute(JobExecutionDTO execution) throws Exception {
        // Your custom logic here
        return Map.of("status", "success");
    }

    @Override
    public boolean supports(String jobType) {
        return "MY_CUSTOM_TYPE".equals(jobType);
    }
}
```

## Running Tests

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn verify
```

### Test Coverage
```bash
mvn jacoco:report
```

## Monitoring

### Prometheus Metrics

Available at: `http://localhost:8081/actuator/prometheus`

Key metrics:
- `jobs_created_total` - Total jobs created
- `jobs_dispatched_total` - Total jobs dispatched
- `jobs_completed_total` - Total jobs completed
- `jobs_failed_total` - Total jobs failed
- `job_execution_time_seconds` - Job execution time histogram

### Grafana Dashboard

1. Access Grafana at http://localhost:3000
2. Login with admin/admin
3. Navigate to "Job Scheduler Dashboard"
4. View real-time metrics:
   - Job creation rate
   - Job completion/failure rates
   - Execution time percentiles
   - Active workers
   - Success rate

## Scaling Strategy

### Horizontal Scaling

#### Add More Workers

```bash
docker-compose up -d --scale job-worker-service=5
```

This adds more worker instances to increase job processing throughput.

#### Increase Kafka Partitions

```bash
kafka-topics.sh --alter --topic job-dispatch \
  --partitions 20 \
  --bootstrap-server localhost:9092
```

More partitions allow better distribution across workers.

### Vertical Scaling

#### Increase Worker Thread Pool

Update `application.yml`:
```yaml
worker:
  max-concurrent-jobs: 20  # Default: 10
```

#### Optimize Database Connections

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20  # Default: 10
```

### Auto-Scaling (Kubernetes)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: job-worker-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: job-worker-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

## Performance Tuning

### Database Optimization

1. **Enable Connection Pooling:**
```yaml
spring.datasource.hikari.maximum-pool-size: 20
spring.datasource.hikari.minimum-idle: 5
```

2. **Add Indexes:**
```sql
CREATE INDEX idx_job_executions_status ON job_executions(status);
CREATE INDEX idx_job_executions_created_at ON job_executions(created_at);
```

3. **Archive Old Records:**
```sql
DELETE FROM job_executions
WHERE status IN ('COMPLETED', 'FAILED')
AND created_at < NOW() - INTERVAL '30 days';
```

### Kafka Optimization

```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 50  # Batch size
    producer:
      compression-type: gzip  # Compress messages
      batch-size: 16384
```

### Redis Optimization

```yaml
spring:
  data:
    redis:
      jedis:
        pool:
          max-active: 20
          max-idle: 10
```

## Troubleshooting

### Jobs Not Being Executed

1. **Check Kafka connection:**
```bash
docker logs job-worker-1
```

2. **Verify worker health:**
```bash
curl http://localhost:8082/actuator/health
```

3. **Check Redis locks:**
```bash
redis-cli
> KEYS job:lock:*
```

### Database Connection Issues

```bash
docker logs job-scheduler-service
```

Look for connection errors and verify PostgreSQL is running:
```bash
docker ps | grep postgres
```

### High Failure Rate

1. Check Grafana dashboard for error patterns
2. Review worker logs for exceptions
3. Increase job timeout if jobs are timing out
4. Check resource constraints (CPU, Memory)

## Security

### OAuth2 Configuration

Update `application.yml`:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-auth-server.com
          jwk-set-uri: https://your-auth-server.com/.well-known/jwks.json
```

### Disable Security (Development Only)

Set environment variable:
```bash
SPRING_PROFILES_ACTIVE=dev
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

## License

MIT License - see LICENSE file for details

## Support

For issues and questions:
- GitHub Issues: [Link to issues]
- Documentation: [ARCHITECTURE.md](ARCHITECTURE.md)
- Email: support@example.com
