# System Architecture

## Overview

The Distributed Job Scheduler is a highly scalable, fault-tolerant system for orchestrating and executing distributed jobs with dependency management (DAG support).

## System Components

### 1. Job Scheduler Service
**Responsibilities:**
- Exposes REST APIs for job management
- Validates job definitions and DAG dependencies
- Dispatches jobs to Kafka topics
- Manages job state in PostgreSQL
- Coordinates distributed locking via Redis
- Monitors system health and metrics

**Key Features:**
- OAuth2 authentication for secure access
- Cron-based job scheduling
- DAG cycle detection
- Job versioning and history tracking

### 2. Job Worker Service
**Responsibilities:**
- Consumes jobs from Kafka topics
- Executes jobs using multi-threaded ExecutorService
- Reports job status back to scheduler
- Handles job retries with exponential backoff
- Sends heartbeat signals to Redis

**Key Features:**
- Pluggable job executor architecture
- Timeout handling
- Graceful shutdown support
- Worker health monitoring

### 3. Infrastructure Components

#### PostgreSQL
- **Purpose:** Persistent storage for job definitions and execution history
- **Tables:**
  - `jobs`: Job definitions with metadata
  - `job_executions`: Execution records with status tracking
  - `job_dependencies`: DAG relationship mapping
  - `job_tags`: Job categorization

#### Redis
- **Purpose:** Distributed coordination and caching
- **Use Cases:**
  - Distributed locking (prevent duplicate job execution)
  - Job state caching
  - Worker heartbeat tracking
  - Real-time execution state

#### Kafka
- **Purpose:** Asynchronous job dispatching and status updates
- **Topics:**
  - `job-dispatch`: New job executions
  - `job-status-update`: Status updates from workers
  - `job-retry`: Failed jobs requiring retry

#### Prometheus + Grafana
- **Purpose:** Metrics collection and visualization
- **Metrics:**
  - Job creation rate
  - Job completion/failure rates
  - Execution time percentiles
  - Worker health status
  - System throughput

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client/Users                             │
└────────────────────────┬────────────────────────────────────────┘
                         │ REST API (OAuth2)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Job Scheduler Service                           │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐    │
│  │ REST API     │  │ DAG          │  │ Cron Scheduler    │    │
│  │ Controller   │  │ Validation   │  │ Service           │    │
│  └──────────────┘  └──────────────┘  └───────────────────┘    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Job Service (Core Business Logic)            │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────┬────────────────────┬────────────────────┬────────────────┘
      │                    │                    │
      │ Persist            │ Lock/State         │ Dispatch
      ▼                    ▼                    ▼
┌────────────┐      ┌────────────┐      ┌────────────┐
│ PostgreSQL │      │   Redis    │      │   Kafka    │
│            │      │            │      │            │
│ - Jobs     │      │ - Locks    │      │ - Dispatch │
│ - Execs    │      │ - State    │      │ - Status   │
│ - History  │      │ - Heartbeat│      │ - Retry    │
└────────────┘      └────────────┘      └─────┬──────┘
                                               │
                                               │ Consume
                                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Job Worker Services (N instances)             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │ Kafka        │→ │ Execution    │→ │ Job          │         │
│  │ Consumer     │  │ Service      │  │ Executors    │         │
│  └──────────────┘  └──────────────┘  └──────────────┘         │
│                           │                                      │
│                           │ Report Status                        │
│                           ▼                                      │
│                     Kafka Producer                               │
└─────────────────────────────────────────────────────────────────┘
                           │
                           │ Metrics
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              Prometheus → Grafana (Monitoring)                   │
└─────────────────────────────────────────────────────────────────┘
```

## Sequence Diagram: Job Execution Flow

```
Client          Scheduler       PostgreSQL   Redis    Kafka      Worker
  │                 │                │          │        │          │
  ├─POST /jobs─────►│                │          │        │          │
  │                 ├─Validate DAG───┤          │        │          │
  │                 ├─Save Job───────►          │        │          │
  │                 └─Return 201◄────┘          │        │          │
  │◄────────────────┘                           │        │          │
  │                                              │        │          │
  ├─POST /jobs/{id}/execute──────────────────────────────┤          │
  │                 ├─Acquire Lock──────────────►        │          │
  │                 ├─Create Execution─►         │       │          │
  │                 ├─Save State─────────────────►       │          │
  │                 ├─Dispatch Job──────────────────────►│          │
  │                 ├─Release Lock──────────────►        │          │
  │◄────────────────┘                            │       │          │
  │                                               │       │          │
  │                                               │       ├─Consume─►│
  │                                               │       │          ├─Report RUNNING
  │                                               │       │◄─────────┤
  │                 ◄─Update Status◄─────────────────────┘          │
  │                 ├─Save Execution─►            │                 │
  │                 ├─Update State────────────────►                 │
  │                                                                  │
  │                                               ... Job Executes ...
  │                                                                  │
  │                                               │       ├─Report COMPLETED
  │                                               │       │◄─────────┤
  │                 ◄─Update Status◄─────────────────────┘          │
  │                 ├─Save Execution─►            │                 │
  │                 ├─Update State────────────────►                 │
  │                                                                  │
```

## Data Flow

### 1. Job Creation
1. Client sends job definition via REST API
2. Scheduler validates job (DAG check if dependencies exist)
3. Job saved to PostgreSQL
4. Job metadata cached in Redis (optional)

### 2. Job Dispatching
1. Manual execution OR Cron trigger
2. Acquire distributed lock in Redis
3. Create execution record in PostgreSQL
4. Publish job to Kafka `job-dispatch` topic
5. Release lock

### 3. Job Execution
1. Worker consumes job from Kafka
2. Update status to RUNNING
3. Execute job using appropriate executor
4. Report status (COMPLETED/FAILED) to Kafka
5. Send heartbeat to Redis

### 4. Job Retry (on Failure)
1. Worker detects failure
2. Check retry count vs max retries
3. If retries remaining: publish to `job-retry` topic with backoff
4. Else: mark as FAILED

## DAG Execution Strategy

### Dependency Resolution
```
Job A (no dependencies)
  ├─► Job B (depends on A)
  └─► Job C (depends on A)
        └─► Job D (depends on C)
```

**Execution Order:** A → (B, C) → D

### Implementation
1. **Cycle Detection:** DFS-based cycle detection during job creation
2. **Topological Sort:** Kahn's algorithm for execution order
3. **Dependency Tracking:** Check completed jobs before dispatching dependent jobs

## Scaling Strategy

### Horizontal Scaling

#### Scheduler Service
- **Strategy:** Active-Passive with Redis-based leader election
- **Reason:** Single scheduler prevents duplicate job dispatching
- **Alternative:** Active-Active with distributed locking per job

#### Worker Services
- **Strategy:** Active-Active with Kafka consumer groups
- **Scaling:** Add more worker instances to increase throughput
- **Load Balancing:** Kafka automatically distributes jobs across workers

### Vertical Scaling

#### Database
- **PostgreSQL:** Increase connection pool, read replicas for queries
- **Redis:** Cluster mode for high availability

#### Kafka
- **Partitions:** Increase topic partitions for parallelism
- **Replication:** Increase replication factor for fault tolerance

## Fault Tolerance

### Component Failures

#### Scheduler Service Failure
- **Impact:** No new jobs dispatched, existing jobs continue
- **Recovery:** Automatic restart via Docker/Kubernetes
- **State:** Cron jobs resume on restart

#### Worker Service Failure
- **Impact:** In-flight jobs may timeout
- **Recovery:** Kafka rebalances partitions to other workers
- **Retry:** Failed jobs automatically retried

#### Database Failure
- **Impact:** Cannot persist new jobs or updates
- **Recovery:** PostgreSQL HA with replication
- **Caching:** Redis provides temporary state

#### Redis Failure
- **Impact:** No distributed locking, state caching unavailable
- **Recovery:** Redis Sentinel/Cluster for HA
- **Fallback:** System continues with potential duplicate execution

#### Kafka Failure
- **Impact:** Cannot dispatch/update jobs
- **Recovery:** Kafka cluster with replication
- **Buffer:** Scheduler buffers jobs in PostgreSQL

## Security

### Authentication
- **OAuth2/JWT:** Resource server configuration
- **Token Validation:** JWK-based validation
- **User Context:** Extract user from JWT claims

### Authorization
- **Role-Based:** Configure role-based access to API endpoints
- **Job Ownership:** Track job creator for audit

### Network Security
- **TLS:** Enable SSL for all external communications
- **Internal:** Service mesh or private network for inter-service communication

## Monitoring & Observability

### Metrics (Prometheus)
- Job creation rate by type
- Job completion rate by type and worker
- Job failure rate with error types
- Execution time percentiles (p50, p95, p99)
- Worker count and health status
- Kafka consumer lag

### Dashboards (Grafana)
- System overview with job throughput
- Worker performance comparison
- Error rate trending
- Execution time analysis

### Logging
- Structured logging with correlation IDs
- Centralized log aggregation (ELK/Loki)
- Error tracking with stack traces

### Alerting
- Job failure rate threshold
- Worker unavailability
- Database connection pool exhaustion
- Kafka consumer lag

## Performance Optimization

### Database
- Index on frequently queried columns
- Partition large tables by date
- Archive old execution records
- Connection pooling (HikariCP)

### Caching
- Redis for hot job definitions
- In-memory cache for configuration
- TTL-based cache invalidation

### Kafka
- Batch consumption for throughput
- Compression (gzip/snappy)
- Async production with callbacks

### Worker Execution
- Thread pool tuning based on job characteristics
- Timeout enforcement to prevent hanging jobs
- Graceful shutdown for in-flight jobs

## Deployment Architecture

### Docker Compose (Development)
- Single-host deployment
- All services in one network
- Volume mounts for persistence

### Kubernetes (Production)
- StatefulSets for databases
- Deployments for services
- HPA for worker auto-scaling
- PersistentVolumes for data
- Ingress for external access
- ConfigMaps/Secrets for configuration

## Future Enhancements

1. **Job Prioritization:** Priority queues in Kafka
2. **Job Cancellation:** In-flight job interruption
3. **Job Chaining:** Parent-child job relationships
4. **Workflow Engine:** Complex workflow definitions (YAML/JSON)
5. **Multi-tenancy:** Tenant isolation and resource quotas
6. **Job Templates:** Reusable job definitions
7. **Webhook Support:** HTTP callbacks on job completion
8. **Job Artifacts:** Store job outputs (S3/MinIO)
