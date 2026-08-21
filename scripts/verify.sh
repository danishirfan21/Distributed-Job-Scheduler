#!/bin/bash
# End-to-end verification for the Distributed Job Scheduler.
#
# This script does NOT just poke /actuator/health and call it a day. It builds the
# project, brings up the full docker-compose stack, creates a real job through the
# REST API, executes it, and polls the execution record until the worker has actually
# consumed it from Kafka, run it, and reported it COMPLETED in PostgreSQL. It exits
# non-zero on any failure, so it is safe to use as a CI gate.
#
# Usage: ./scripts/verify.sh [--skip-build] [--skip-docker] [--keep-up]

set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SCHEDULER_URL="http://localhost:8081"
WORKER1_URL="http://localhost:8082"
WORKER2_URL="http://localhost:8083"

SKIP_BUILD=false
SKIP_DOCKER=false
KEEP_UP=false
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --skip-docker) SKIP_DOCKER=true ;;
    --keep-up) KEEP_UP=true ;;
  esac
done

PASS=0
FAIL=0

pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
step() { echo ""; echo "=== $1 ==="; }

cleanup() {
  if [ "$KEEP_UP" = false ] && [ "$SKIP_DOCKER" = false ]; then
    step "Tearing down docker compose stack"
    docker compose down -v >/dev/null 2>&1
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
step "1. Build the project"
if [ "$SKIP_BUILD" = true ]; then
  echo "  Skipped (--skip-build)"
else
  if mvn -q clean package -DskipTests; then
    pass "mvn clean package"
  else
    fail "mvn clean package"
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
step "2. Run unit tests (mvn test)"
if mvn -q test; then
  pass "unit tests"
else
  fail "unit tests"
fi

# ---------------------------------------------------------------------------
step "3. Start docker compose stack"
if [ "$SKIP_DOCKER" = true ]; then
  echo "  Skipped (--skip-docker)"
else
  if ! command -v docker >/dev/null 2>&1; then
    fail "docker is not installed/available in this environment"
    echo ""
    echo "Cannot continue without Docker. Unit test results above are still valid."
    exit 1
  fi

  if docker compose up --build -d; then
    pass "docker compose up --build -d"
  else
    fail "docker compose up --build -d"
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
step "4. Wait for services to become healthy"
wait_for_health() {
  local name="$1" url="$2" timeout="${3:-180}"
  local waited=0
  while [ "$waited" -lt "$timeout" ]; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health" 2>/dev/null)
    if [ "$status" = "200" ]; then
      pass "$name is healthy ($url/actuator/health)"
      return 0
    fi
    sleep 3
    waited=$((waited+3))
  done
  fail "$name did not become healthy within ${timeout}s ($url/actuator/health)"
  return 1
}

wait_for_health "job-scheduler-service" "$SCHEDULER_URL" 180
wait_for_health "job-worker-1" "$WORKER1_URL" 180
wait_for_health "job-worker-2" "$WORKER2_URL" 180

# ---------------------------------------------------------------------------
step "5. Create a job via REST API (POST /api/v1/jobs)"
CREATE_BODY='{
  "name": "verify.sh Data Processing Job",
  "description": "Created by scripts/verify.sh",
  "type": "DATA_PROCESSING",
  "priority": "HIGH",
  "parameters": {"dataSource": "verify-script", "operation": "TRANSFORM"},
  "maxRetries": 2,
  "timeoutSeconds": 120
}'

CREATE_RESPONSE=$(curl -s -X POST "$SCHEDULER_URL/api/v1/jobs" \
  -H "Content-Type: application/json" \
  -d "$CREATE_BODY")

JOB_ID=$(echo "$CREATE_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -n "$JOB_ID" ]; then
  pass "job created: id=$JOB_ID"
else
  fail "job creation failed. Response: $CREATE_RESPONSE"
  echo ""
  echo "$PASS passed, $FAIL failed."
  exit 1
fi

# ---------------------------------------------------------------------------
step "6. Execute the job (POST /api/v1/jobs/{id}/execute)"
EXECUTE_RESPONSE=$(curl -s -X POST "$SCHEDULER_URL/api/v1/jobs/$JOB_ID/execute")
EXECUTION_ID=$(echo "$EXECUTE_RESPONSE" | grep -o '"executionId":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -n "$EXECUTION_ID" ]; then
  pass "job dispatched: executionId=$EXECUTION_ID"
else
  fail "job execution/dispatch failed. Response: $EXECUTE_RESPONSE"
  echo ""
  echo "$PASS passed, $FAIL failed."
  exit 1
fi

# ---------------------------------------------------------------------------
step "7. Poll execution status until worker reports COMPLETED"
TIMEOUT=90
WAITED=0
STATUS="UNKNOWN"
while [ "$WAITED" -lt "$TIMEOUT" ]; do
  EXEC_RESPONSE=$(curl -s "$SCHEDULER_URL/api/v1/jobs/executions/$EXECUTION_ID")
  STATUS=$(echo "$EXEC_RESPONSE" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
  echo "  ...executionId=$EXECUTION_ID status=$STATUS (${WAITED}s elapsed)"

  if [ "$STATUS" = "COMPLETED" ]; then
    pass "execution reached COMPLETED"
    break
  fi
  if [ "$STATUS" = "FAILED" ]; then
    fail "execution reached FAILED. Response: $EXEC_RESPONSE"
    break
  fi

  sleep 5
  WAITED=$((WAITED+5))
done

if [ "$STATUS" != "COMPLETED" ] && [ "$STATUS" != "FAILED" ]; then
  fail "execution did not reach a terminal state within ${TIMEOUT}s (last status=$STATUS)"
fi

# ---------------------------------------------------------------------------
step "8. Verify persistence in PostgreSQL directly"
if [ "$SKIP_DOCKER" = false ] && command -v docker >/dev/null 2>&1; then
  DB_STATUS=$(docker exec job-scheduler-postgres psql -U postgres -d job_scheduler -tAc \
    "SELECT status FROM job_executions WHERE id='$EXECUTION_ID';" 2>/dev/null | tr -d '[:space:]')
  if [ "$DB_STATUS" = "COMPLETED" ]; then
    pass "job_executions row in Postgres shows status=COMPLETED"
  else
    fail "job_executions row in Postgres shows status='$DB_STATUS' (expected COMPLETED)"
  fi
fi

# ---------------------------------------------------------------------------
step "9. Prometheus metrics reachable"
if curl -s "$SCHEDULER_URL/actuator/prometheus" | grep -q "jobs_created_total"; then
  pass "scheduler exposes jobs_created_total on /actuator/prometheus"
else
  fail "scheduler /actuator/prometheus missing jobs_created_total"
fi

# Either worker instance may have processed the job above - job-dispatch's Kafka
# consumer group load-balances across whichever worker happens to own that partition.
if curl -s "$WORKER1_URL/actuator/prometheus" | grep -q "jobs_completed_total" \
    || curl -s "$WORKER2_URL/actuator/prometheus" | grep -q "jobs_completed_total"; then
  pass "a worker exposes jobs_completed_total on /actuator/prometheus"
else
  fail "neither worker's /actuator/prometheus shows jobs_completed_total"
fi

# ---------------------------------------------------------------------------
step "Summary"
echo "  Passed: $PASS"
echo "  Failed: $FAIL"

if [ "$FAIL" -gt 0 ]; then
  echo ""
  echo "VERIFICATION FAILED"
  exit 1
else
  echo ""
  echo "VERIFICATION PASSED - the distributed job scheduler works end-to-end."
  exit 0
fi
