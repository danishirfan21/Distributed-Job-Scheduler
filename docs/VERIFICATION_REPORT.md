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

The Docker-dependent paths (`docker compose up --build`, `mvn verify`'s Testcontainers
tests, `scripts/verify.sh`) could not be exercised in the sandboxed environment this audit
was performed in (no Docker daemon, and a GitHub Codespace attempted as a workaround
turned out to run in a restricted container that couldn't grant Docker the network
capabilities it needs either). They were instead verified via a GitHub Actions workflow
([`.github/workflows/verify.yml`](../.github/workflows/verify.yml)) added specifically for
this purpose, since GitHub-hosted runners have an unrestricted Docker daemon. See Section 4.

## 4. What Was Verified

### Verified locally in this environment
- Maven reactor build (all 3 modules) compiles cleanly on Java 17.
- All 23 unit/H2-backed tests pass (`mvn clean test`).
- The Testcontainers `*IT.java` classes compile against the real Testcontainers/Kafka
  client APIs and are correctly excluded from `mvn test` by Failsafe naming convention.
- Each Dockerfile's exact multi-stage `COPY` set was reproduced by hand in a clean temp
  directory and built with the same `mvn -pl <module> -am package` command Docker runs,
  confirming the Maven reactor resolves correctly for both images (this is how the
  Dockerfile bug described below was first caught and fixed, ahead of the first CI run).

### Verified on GitHub Actions (real Docker daemon, `.github/workflows/verify.yml`)
Seven CI runs total, five of them failures - every failure was a real bug this review had
not caught statically, not an infrastructure fluke, including one (run 6) surfaced by a
completely unrelated docs-only push. In order:

1. **Run 1** ([`4cac114`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/4cac114)-era workflow): `docker compose up --build` failed immediately for both
   images with `Child module .../pom.xml does not exist`. Both Dockerfiles only `COPY`ed
   their own module's `pom.xml` plus `job-common`, but the root `pom.xml` lists all 3
   modules - Maven's reactor needs every listed module's `pom.xml` present just to parse,
   regardless of which modules are actually being built. **Fixed** in
   [`a6e780a`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/a6e780a)
   by copying the third module's `pom.xml` (not its `src`) into each image.
2. Same run: the Testcontainers IT tests failed - `SchedulerEndToEndIT` and
   `WorkerConsumeAndExecuteIT` published raw JSON via a plain `StringSerializer` producer
   to simulate the real services, but the real consumers use Spring's `JsonDeserializer`,
   which needs a `__TypeId__` header (normally added automatically by Spring's
   `JsonSerializer`) to know what class to deserialize into. Every message was a poison
   pill the consumer retried forever. **Fixed** in the same commit by adding the header
   manually in both tests.
3. **Run 2**: Maven/Docker issues resolved; unit tests and integration tests (Testcontainers)
   now passed. But `end-to-end` still failed: `job-worker-1`/`job-worker-2` both timed out
   waiting for `/actuator/health` (180s each) despite starting in ~9s and later processing
   the dispatched job correctly (RUNNING → COMPLETED in 10s, persisted in real Postgres -
   the actual distributed flow already worked at this point). Hypothesized cause: the
   auto-configured Kafka health indicator blocking on broker admin calls during cluster
   startup. **Attempted fix** in
   [`6046e19`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/6046e19):
   `management.health.kafka.enabled=false`.
4. **Run 3**: identical failure, proving the Kafka hypothesis wrong. Checked the raw worker
   container logs directly and found the real cause: `job-worker-service/pom.xml` never
   had `spring-boot-starter-web` - only `spring-boot-starter` + `spring-boot-starter-actuator`.
   Without a web starter there is no embedded servlet container at all, so Actuator's HTTP
   endpoints had no web layer to attach to; `/actuator/health` and `/actuator/prometheus`
   were not slow, they were completely nonexistent. **Fixed** in
   [`22a0fee`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/22a0fee)
   by adding `spring-boot-starter-web`.
5. **Run 4**: `/actuator/prometheus` now responded correctly, but `/actuator/health` still
   failed for the full 180s - this time *not* transiently. The raw logs showed
   `jakarta.mail.AuthenticationFailedException` repeating continuously: with
   `spring-boot-starter-web` now present, Actuator's mail health indicator started making a
   real SMTP handshake against `smtp.gmail.com` (the configured default host, no
   credentials) on every single health check, and since `EmailNotificationExecutor`
   deliberately never sends real email (see "Known Limitations" in the README), this
   indicator was permanently broken, not flaky. **Fixed** in
   [`308d01a`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/308d01a):
   `management.health.mail.enabled=false`.
6. **Run 5** ([`32519006790`](https://github.com/danishirfan21/Distributed-Job-Scheduler/actions/runs/32519006790)):
   all three jobs (`unit-tests`, `integration-tests`, `end-to-end`) passed for the first
   time - genuinely proving, on a real Docker daemon, that `docker compose up --build`
   brings up all 8 services; `POST /api/v1/jobs` creates a job; `POST .../execute`
   dispatches it to a real Kafka topic; a real worker consumes it, executes it, and reports
   status back over Kafka; the scheduler persists the final state as `COMPLETED` in real
   PostgreSQL; and both services' `/actuator/prometheus` endpoints return real metrics.
7. **Run 6** (triggered by [`0a7b3c3`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/0a7b3c3),
   a documentation-only commit with zero code changes): `end-to-end` failed anyway. A
   created/dispatched job stayed `QUEUED` forever - never consumed - despite the scheduler
   successfully publishing it to `partition=5`. The raw scheduler log showed
   `Topic 'job-dispatch' exists but has a different partition count: 1 not 10, increasing
   if the broker supports it`, logged *after* both workers had already formed their
   consumer group. Root cause: with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`, a worker's
   consumer reached Kafka before the scheduler's `KafkaAdmin` explicitly created the topic
   with 10 partitions, so the broker auto-created it with its default of 1 partition
   instead. The scheduler's `KafkaAdmin` later detected the mismatch and expanded the topic
   to 10 partitions (Spring Kafka's documented behavior for an existing topic with fewer
   partitions than a declared `NewTopic` bean requests) - but the worker's consumer group
   had already rebalanced against the 1-partition version and doesn't get reassigned until
   its next metadata refresh (5 minutes by default), so anything published to the newly
   added partitions sat unconsumed indefinitely. This is a genuine, deterministic race tied
   to container startup ordering, not flakiness - it happened to succeed in run 5 only
   because the scheduler happened to win that race that time. **Fixed** in
   [`944e786`](https://github.com/danishirfan21/Distributed-Job-Scheduler/commit/944e786)
   by disabling `KAFKA_AUTO_CREATE_TOPICS_ENABLE` entirely (topics can now only ever be
   created once, explicitly, by the scheduler) plus `allow.auto.create.topics=false` on
   both services' consumers as defense-in-depth.
8. **Run 7** ([`32521524124`](https://github.com/danishirfan21/Distributed-Job-Scheduler/actions/runs/32521524124)):
   all three jobs passed again, this time in 3m18s total for `end-to-end` (down from ~9
   minutes in run 5) - the fastest run yet, consistent with removing a source of wasted
   retry time rather than just papering over a symptom.

### Still not independently reproduced outside CI
- Grafana actually rendering the dashboard against live data (no step in `verify.sh`
  checks Grafana specifically - Prometheus scraping both services was confirmed instead).
- Kafka rebalance behavior under *sustained* load (many concurrent jobs, workers scaling
  up/down mid-flight). Run 6 proved that a one-off topic-partition race at startup is a
  real risk class in this codebase and fixed the specific instance found; it did not
  stress-test rebalancing under load, so similar-shaped bugs elsewhere remain plausible.
  The Redis dedup logic is unit-tested with mocks and exercised once (a single job) by
  `WorkerConsumeAndExecuteIT`, not under a real rebalance storm.

**To reproduce locally** on a machine with a working Docker daemon:
```bash
mvn verify
docker compose up --build -d
./scripts/verify.sh --skip-build
```
Or push to a fork/branch and let `.github/workflows/verify.yml` run on GitHub's own
Docker-enabled runners.

## 5. Files Changed

- `job-scheduler-service/src/main/java/.../config/SecurityConfig.java`
- `job-scheduler-service/src/main/java/.../controller/GlobalExceptionHandler.java` (new)
- `job-scheduler-service/src/main/java/.../service/RedisLockService.java`
- `job-scheduler-service/pom.xml`
- `job-scheduler-service/src/main/resources/application.yml`
- `job-scheduler-service/src/test/resources/application-test.yml`
- `job-scheduler-service/src/test/java/.../SchedulerEndToEndIT.java` (new)
- `job-scheduler-service/Dockerfile` (CI-discovered fix: missing sibling module pom.xml)
- `job-worker-service/src/main/java/.../service/JobExecutionService.java`
- `job-worker-service/src/main/java/.../consumer/JobRetryConsumer.java`
- `job-worker-service/src/main/resources/application.yml` (CI-discovered fixes: mail/kafka
  health indicators, `allow.auto.create.topics=false`)
- `job-worker-service/pom.xml` (CI-discovered fix: missing spring-boot-starter-web)
- `job-worker-service/Dockerfile` (CI-discovered fix: missing sibling module pom.xml)
- `job-worker-service/src/test/java/.../JobExecutionServiceTest.java` (new)
- `job-worker-service/src/test/java/.../JobRetryConsumerTest.java` (new)
- `job-worker-service/src/test/java/.../WorkerConsumeAndExecuteIT.java` (new; CI-discovered
  fix: missing `__TypeId__` Kafka header)
- `job-common/src/main/java/.../constants/RedisKeys.java`
- `pom.xml` (also: removed unused `mapstruct`/`spring-cloud-dependencies` declarations and
  dead `kafka.version`/`redis.version`/`micrometer.version` properties during the portfolio
  cleanup pass - none were ever referenced by any module)
- `docker-compose.yml` (CI-discovered fix: `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`)
- `job-scheduler-service/src/main/resources/application.yml` (`allow.auto.create.topics=false`)
- `LICENSE` (new - repo had none despite README claiming MIT)
- `scripts/verify.sh` (new; CI-discovered fix: worker Prometheus check assumed worker-1
  specifically would process the job)
- `.github/workflows/verify.yml` (new - runs unit tests, Testcontainers integration tests,
  and the full docker-compose end-to-end flow on every push/PR, using GitHub's Docker-
  enabled runners since this environment has none)
- `README.md`, `QUICKSTART.md`, `ARCHITECTURE.md`, `PROJECT_SUMMARY.md`
- `docs/VERIFICATION_REPORT.md` (this file, new)
