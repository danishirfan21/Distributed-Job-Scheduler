# Free Deployment Guide: Distributed Job Scheduler

This guide explains how to deploy the Distributed Job Scheduler for **free** using various cloud providers' free tiers.

## Note on Frontend
As of now, this project **does not include a dedicated management web UI**.
- **Management:** Use the [REST API](README.md#api-documentation) or the provided [Postman Collection](examples/postman-collection.json).
- **Monitoring:** Use the **Grafana Dashboard** included in the infrastructure.

## Recommended Free Tier Stack

To run this system for free, we recommend using managed services for infrastructure to save on memory/CPU costs, and hosting the Spring Boot applications on platforms with free tiers.

| Component | Service Provider | Why? |
|-----------|------------------|------|
| **PostgreSQL** | [Neon](https://neon.tech/) or [Supabase](https://supabase.com/) | Generous free tier, easy setup. |
| **Redis** | [Upstash](https://upstash.com/) | Serverless Redis with a free tier that handles low traffic well. |
| **Kafka** | [Upstash](https://upstash.com/) | Serverless Kafka, perfect for distributed messaging without managing a cluster. |
| **App Hosting** | [Render](https://render.com/) or [Railway](https://railway.app/) | Easy Docker deployment. Render has a free tier for web services. |
| **Monitoring** | [Grafana Cloud](https://grafana.com/products/cloud/) | Free tier includes Prometheus and Grafana hosting. |

---

## Step 1: Set Up Infrastructure

### 1. PostgreSQL (Neon)
1. Sign up at [neon.tech](https://neon.tech/).
2. Create a new project and a database named `job_scheduler`.
3. Copy the **Connection String**. It will look like: `postgresql://user:password@ep-host.region.aws.neon.tech/job_scheduler?sslmode=require`.

### 2. Redis (Upstash)
1. Sign up at [upstash.com](https://upstash.com/).
2. Create a Redis database.
3. Note the **Hostname**, **Port**, and **Password**.

### 3. Kafka (Upstash)
1. In Upstash console, create a Kafka cluster.
2. Create topics: `job-dispatch`, `job-status-update`, `job-retry`.
3. Note the **Bootstrap Endpoint**, **Username**, and **Password**.
4. Upstash uses SCRAM-SHA-256 for authentication.

---

## Step 2: Deploy Scheduler Service

Deploy the `job-scheduler-service` using its `Dockerfile`.

### Environment Variables for Scheduler:
| Variable | Value |
|----------|-------|
| `SPRING_DATASOURCE_URL` | Your Neon connection string (replace `postgresql://` with `jdbc:postgresql://`) |
| `DB_USER` | Your Neon database user |
| `DB_PASSWORD` | Your Neon database password |
| `REDIS_HOST` | Your Upstash Redis hostname |
| `REDIS_PORT` | Your Upstash Redis port |
| `REDIS_PASSWORD` | Your Upstash Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | Your Upstash Kafka endpoint |
| `KAFKA_PROPERTIES_SASL_MECHANISM` | `SCRAM-SHA-256` |
| `KAFKA_PROPERTIES_SECURITY_PROTOCOL` | `SASL_SSL` |
| `KAFKA_PROPERTIES_SASL_JAAS_CONFIG` | `org.apache.kafka.common.security.scram.ScramLoginModule required username="YOUR_UPSTASH_USERNAME" password="YOUR_UPSTASH_PASSWORD";` |

---

## Step 3: Deploy Worker Service

Deploy the `job-worker-service` using its `Dockerfile`. On Render, use a **Web Service** type for this as well.

### Environment Variables for Worker:
| Variable | Value |
|----------|-------|
| `REDIS_HOST` | Your Upstash Redis hostname |
| `REDIS_PORT` | Your Upstash Redis port |
| `REDIS_PASSWORD` | Your Upstash Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | Your Upstash Kafka endpoint |
| `KAFKA_PROPERTIES_SASL_MECHANISM` | `SCRAM-SHA-256` |
| `KAFKA_PROPERTIES_SECURITY_PROTOCOL` | `SASL_SSL` |
| `KAFKA_PROPERTIES_SASL_JAAS_CONFIG` | `org.apache.kafka.common.security.scram.ScramLoginModule required username="YOUR_UPSTASH_USERNAME" password="YOUR_UPSTASH_PASSWORD";` |
| `WORKER_ID` | `worker-cloud-1` (Unique ID for each instance) |

---

## Step 4: Monitoring (Optional)

### Using Grafana Cloud
1. Sign up for [Grafana Cloud Free](https://grafana.com/products/cloud/).
2. Follow the "Add Data Source" instructions to connect to your Prometheus endpoint (if you can expose it) or use the Grafana Agent to push metrics from your services.
3. Import the dashboard JSON files located in `infrastructure/grafana/dashboards/`.

---

## Summary of Platform Options

### 1. Render (Easiest)
- Create a **Web Service** for the Scheduler.
- Create another **Web Service** for the Worker.
- Use **Docker** as the runtime.
- Set the `Build Command` to the root and point to the specific `Dockerfile` in the service directory.
- **Note on Cold Starts:** Render's free services spin down after 15 minutes of inactivity. The first request after a period of idleness will take longer while the service boots up.

### 2. Railway (Fastest)
- Connect your GitHub repo.
- Railway will automatically detect the Dockerfiles.
- Add your environment variables in the Railway dashboard.

### 3. Oracle Cloud (Most Powerful)
- If you can get an **Always Free** ARM instance (4 OCPUs, 24GB RAM), you can run the entire `docker-compose.yml` exactly as it is. This is the best way to host everything (including Grafana and Prometheus) for free.

---

## Important Notes
- **SSL:** Managed databases like Neon require SSL. Ensure your connection string includes `sslmode=require`.
- **Kafka Topics:** Ensure you create the Kafka topics in the Upstash console before starting the services, as auto-creation might be disabled in free tiers.
- **Load Balancing:** All workers share the same `job-worker-group` ID to ensure jobs are distributed across instances and not executed multiple times.
