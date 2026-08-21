# Verification Report

This document records the audit findings, the fixes applied, and exactly what was and
was not runtime-verified in the environment this audit was performed in (Windows,
Maven 3.9.12, Java 21 JDK targeting a Java 17 build, **no Docker daemon available**).

## 1. Original Issues Discovered

| # | Issue | Severity | Where |
|---|-------|----------|-------|
| 1 | `SecurityConfig` required OAuth2 JWT auth on `/api/**`, but no identity provider exists anywhere in `docker-compose.yml` and no `issuer-uri`/`jwk-set-uri` points at anything real. Every documented `curl` example in README/QUICKSTART would 401. This blocked the entire primary goal (create a job through the REST API). | **Critical** | `job-scheduler-service/.../config/SecurityConfig.java` |
| 2 | No global exception handler. `IllegalArgumentException`/`IllegalStateException` thrown by `JobService` (job not found, lock already held, etc.) fell through to a generic Spring 500, not the documented `ApiResponse` shape or a sensible status code. | High | `job-scheduler-service` controller layer |
| 3 | `RedisLockService.releaseLock` did a non-atomic get-then-delete - a classic distributed-lock race (task explicitly calls this class of bug out). | Medium | `RedisLockService.java` |
| 4 | Worker had **no duplicate-execution protection** at the point that matters (consuming/executing). `RedisLockService` on the scheduler side only guards the moment of *dispatching* to Kafka and is released immediately after - it does nothing once the message is on the topic. A Kafka rebalance can redeliver an uncommitted offset to a second worker, and nothing stopped it from executing twice. Worker-side Redis usage was previously limited to heartbeats. | High | `job-worker-service/.../service/JobExecutionService.java` |
| 5 | Grafana's "Job Execution Time (p95)" panel queried `histogram_quantile(..., job_execution_time_seconds_bucket...)`, but the `Timer` backing that metric was never configured to publish a percentile histogram, so no `_bucket` series existed - the panel would silently render nothing forever. "Fake monitoring configuration." | Medium | `job-worker-service` metrics config / Grafana dashboard |
| 6 | Kafka `KafkaAdmin` topic-creation retried against `localhost:9092` for ~55s per Spring Boot test run because no timeout was configured for the test profile, since no broker is present in unit tests. Tests passed but were needlessly slow and noisy. | Low | `application-test.yml` |
| 7 | README/QUICKSTART claimed `docker compose up -d --scale job-worker-service=5` scales workers. There is no service named `job-worker-service` in `docker-compose.yml` (the scheduler has that name); the two worker services are fixed (`job-worker-1`, `job-worker-2`) with static `container_name` and host port mappings, which also blocks `--scale` from working even if the name were right. | Medium (misleading doc) | README.md, QUICKSTART.md |
| 8 | README claimed setting `SPRING_PROFILES_ACTIVE=dev` disables security. No such profile or conditional existed anywhere in the codebase - the claim was aspirational, not implemented. | Medium (misleading doc) | README.md |
| 9 | PROJECT_SUMMARY.md described the repo as "production-ready, enterprise-grade." Given issues #1 and #4 alone, that claim was not justified. | Low (misleading doc) | PROJECT_SUMMARY.md |
| 10 | No integration test proved the actual distributed flow (create → dispatch → consume → execute → persist COMPLETED). The only tests were unit tests with every piece of infrastructure mocked, plus one `@SpringBootTest` MockMvc test against an in-memory H2 database - none touched real Kafka. | High | `job-scheduler-service`/`job-worker-service` test suites |
| 11 | No script proved the system works end-to-end beyond `curl /actuator/health`. | Medium | (missing) |
| 12 | `docker-compose.yml`'s Prometheus/Grafana `depends_on` omitted `job-worker-2`. | Low | `docker-compose.yml` |

### What was *not* broken (contrary to the assumption that this repo might be non-functional boilerplate)

The audit found the core Spring Boot wiring, JPA entities, Flyway migration, Kafka
producer/consumer configuration, topic names, DTOs, DAG cycle-detection algorithm, and
REST controller were all internally consistent and already compiled/tested cleanly. The
`REPORT_GENERATION` and `DATA_PROCESSING` executors were already implemented as
deterministic, non-external-dependency job types satisfying Phase 4's requirement. This
was a partially-broken, mostly-real project, not throwaway generated scaffolding - the
fixes below are targeted, not a rewrite.

## 2. Fixes Applied

1. **Removed the non-functional OAuth2 requirement.** `SecurityConfig` now permits all
   requests; `spring-boot-starter-oauth2-resource-server` and the dead `issuer-uri`/
   `jwk-set-uri` config were removed from `job-scheduler-service`. This is documented as a
   deliberate decision (not a silent regression) in README "Known Limitations" and
   "Security", with concrete steps to re-enable real OAuth2 if this is ever deployed
   beyond a local machine.
2. **Added `GlobalExceptionHandler`** (`@RestControllerAdvice`) mapping
   `IllegalArgumentException` → 404, `IllegalStateException` → 409,
   `MethodArgumentNotValidException` → 400, everything else → 500, all wrapped in the
   existing `ApiResponse` envelope.
3. **Made `RedisLockService.releaseLock` atomic** via a Lua script (`GET` + conditional
   `DEL` in one round-trip) instead of separate `GET` then `DEL` calls.
4. **Added genuine worker-side duplicate-execution protection.** Before executing,
   `JobExecutionService` now does a Redis `SETNX` on a key scoped to
   `(executionId, currentRetry)` with a TTL covering the job timeout. A second delivery
   of the same attempt loses the race and is skipped with a log warning instead of
   executing twice. Legitimate retries (a new `currentRetry`) still get a fresh key and
   proceed normally.
5. **Enabled the percentile histogram** for `job.execution.time` via
   `management.metrics.distribution.percentiles-histogram` in
   `job-worker-service/application.yml`, so the Grafana p95 panel now has real
   `_bucket` series to query.
6. **Sped up and quieted the Kafka admin retries in tests** via
   `spring.kafka.admin.properties.request.timeout.ms=3000` in `application-test.yml`
   (integration test time dropped from ~56s to ~28s for that test class).
7. **Corrected the `--scale` worker-scaling instructions** in README/QUICKSTART to
   reflect what `docker-compose.yml` actually defines, with a working alternative.
8. **Replaced the fictitious `SPRING_PROFILES_ACTIVE=dev` security toggle** in README
   with an accurate description of the current (permit-all) state and concrete steps to
   add real OAuth2 later.
9. **Rewrote PROJECT_SUMMARY.md's opening claim** to stop calling this
   production-ready/enterprise-grade and link to the limitations that make it not so.
10. **Added Testcontainers-based integration tests** (Failsafe-bound `*IT.java`, run only
    via `mvn verify`, never `mvn test`):
    - `SchedulerEndToEndIT` - real Postgres + Kafka + Redis containers; drives the actual
      REST API to create and execute a job, asserts the dispatch really reaches the real
      `job-dispatch` Kafka topic, simulates the worker's status reports over real Kafka,
      and polls the REST API until the execution is persisted `COMPLETED`.
    - `WorkerConsumeAndExecuteIT` - real Kafka + Redis containers; publishes a dispatch
      message the way the scheduler would, and asserts job-worker-service's own
      `@KafkaListener` really consumes it, executes it through the real `JobExecutor`,
      and reports `RUNNING` then `COMPLETED` back over real Kafka.
    (The two services are separate deployable Spring Boot apps, so a single test can't
    boot both in one JVM without pulling worker code onto the scheduler's classpath or
    vice versa - each IT test exercises its own service against real infra, and together
    they cover the full path described in Phase 5.)
11. **Added unit tests** for previously-untested worker logic: `JobExecutionServiceTest`
    (duplicate-delivery skip, successful execution reporting RUNNING→COMPLETED, failure
    triggering a retry request) and `JobRetryConsumerTest` (exponential backoff
    calculation: 60s/120s/240s/480s).
12. **`docker-compose.yml`**: added `job-worker-2` to Prometheus/Grafana's `depends_on`.
13. **Added `scripts/verify.sh`** - see below.
14. Normalized all runnable command examples in README/QUICKSTART from the legacy
    `docker-compose` (hyphenated v1 CLI) to `docker compose` (v2 plugin syntax), matching
    what was actually asked for and what the compose file's `version: '3.8'` targets.
15. Documented (rather than silently leaving undiscoverable) that
    `EmailNotificationExecutor`'s `mailSender.send(message)` call is commented out by
    design - no real SMTP is required for the demo - and that `DATA_BACKUP`/`CUSTOM`
    executors are likewise simulated. `DATA_PROCESSING`/`REPORT_GENERATION` are the
    executors intended to demonstrate the real flow.

## 3. Commands Executed and Their Results

All commands below were actually run in this environment (Windows, Maven 3.9.12,
Java 21 JDK compiling to `--release 17`, network access available for Maven Central).

```bash
mvn clean compile          # PASS - all 3 modules compile
mvn clean test             # PASS - 23 tests, 0 failures, 0 errors (see breakdown below)
mvn clean test-compile     # PASS - confirms the new Testcontainers IT classes compile
```

Final unit test breakdown (`mvn clean test`, ~30s total):

| Test class | Tests | Result |
|---|---|---|
| `JobControllerIntegrationTest` (H2, MockMvc) | 4 | PASS |
| `DAGValidationServiceTest` | 6 | PASS |
| `JobServiceTest` | 6 | PASS |
| `JobRetryConsumerTest` | 1 | PASS |
| `EmailNotificationExecutorTest` | 3 | PASS |
| `JobExecutionServiceTest` | 3 | PASS |
| **Total** | **23** | **PASS** |

`docker compose config`, `docker compose up --build`, `mvn verify` (the Testcontainers
IT tests), and `scripts/verify.sh`'s Docker-dependent steps were **not** runtime-verified
in this environment - see Section 4.

## 4. What Was Verified vs. What Remains Unverified

### Verified in this environment
- Maven reactor build (all 3 modules) compiles cleanly on Java 17.
- All 23 unit/H2-backed tests pass.
- The new Testcontainers `*IT.java` classes compile against the real Testcontainers/Kafka
  client APIs (`mvn test-compile`), and are correctly excluded from `mvn test` by Failsafe
  naming convention (`*IT.java`), confirmed by re-running `mvn test` after adding them and
  observing the same 23 tests, not more.
- Static review of `docker-compose.yml`, both `Dockerfile`s, `application.yml`s,
  `prometheus.yml`, and the Grafana dashboard/datasource/provisioning JSON/YAML for
  hostname consistency (service names match Spring `*_HOST` env vars and Prometheus
  scrape targets), port consistency, and Kafka `KAFKA_ADVERTISED_LISTENERS` correctness
  for container-to-container vs. host access.

### NOT verified (no Docker daemon available in this environment)
- `docker compose up --build` actually starting all 8 services and reaching healthy.
- The full container-networked flow: REST API → Postgres persistence → Kafka dispatch →
  worker consumption → job execution → status update → Postgres persistence as
  `COMPLETED`, running across real containers.
- `mvn verify` (the Testcontainers integration tests) actually passing against live
  containers - they are known to *compile and be structured correctly against the real
  Testcontainers/Kafka APIs*, but starting containers requires a Docker daemon this
  environment does not have.
- `scripts/verify.sh` end-to-end (its build/test steps were smoke-tested with
  `--skip-docker`; the Docker-dependent steps were not).
- Prometheus actually scraping both services and Grafana rendering the dashboard against
  live data.
- Kafka partition rebalancing / duplicate-delivery behavior under real broker conditions
  (the Redis dedup logic added in fix #4 is unit-tested with mocks, but its real-world
  effectiveness against an actual rebalance is only exercised by `WorkerConsumeAndExecuteIT`,
  which itself is unverified for the reason above).

**Recommendation:** before relying on this as "known-good," run, on a machine with Docker:
```bash
mvn verify
docker compose up --build -d
./scripts/verify.sh --skip-build
```

## 5. Files Changed

- `job-scheduler-service/src/main/java/.../config/SecurityConfig.java`
- `job-scheduler-service/src/main/java/.../controller/GlobalExceptionHandler.java` (new)
- `job-scheduler-service/src/main/java/.../service/RedisLockService.java`
- `job-scheduler-service/pom.xml`
- `job-scheduler-service/src/main/resources/application.yml`
- `job-scheduler-service/src/test/resources/application-test.yml`
- `job-scheduler-service/src/test/java/.../SchedulerEndToEndIT.java` (new)
- `job-worker-service/src/main/java/.../service/JobExecutionService.java`
- `job-worker-service/src/main/java/.../consumer/JobRetryConsumer.java`
- `job-worker-service/src/main/resources/application.yml`
- `job-worker-service/pom.xml`
- `job-worker-service/src/test/java/.../JobExecutionServiceTest.java` (new)
- `job-worker-service/src/test/java/.../JobRetryConsumerTest.java` (new)
- `job-worker-service/src/test/java/.../WorkerConsumeAndExecuteIT.java` (new)
- `job-common/src/main/java/.../constants/RedisKeys.java`
- `pom.xml`
- `docker-compose.yml`
- `scripts/verify.sh` (new)
- `README.md`, `QUICKSTART.md`, `ARCHITECTURE.md`, `PROJECT_SUMMARY.md`
- `docs/VERIFICATION_REPORT.md` (this file, new)
