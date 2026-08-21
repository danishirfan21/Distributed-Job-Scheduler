package com.distributed.jobscheduler.common.constants;

public class RedisKeys {
    public static final String JOB_LOCK_PREFIX = "job:lock:";
    public static final String JOB_STATE_PREFIX = "job:state:";
    public static final String WORKER_HEARTBEAT_PREFIX = "worker:heartbeat:";
    public static final String JOB_EXECUTION_PREFIX = "job:execution:";
    public static final String JOB_ATTEMPT_PREFIX = "job:attempt:";

    private RedisKeys() {
        // Utility class
    }

    public static String jobLock(String jobId) {
        return JOB_LOCK_PREFIX + jobId;
    }

    /**
     * Dedup key for a single (executionId, retry) attempt, used by workers to guard
     * against processing the same Kafka delivery twice (e.g. after a consumer rebalance
     * redelivers a message whose offset was never committed).
     */
    public static String jobAttempt(String executionId, int currentRetry) {
        return JOB_ATTEMPT_PREFIX + executionId + ":" + currentRetry;
    }

    public static String jobState(String executionId) {
        return JOB_STATE_PREFIX + executionId;
    }

    public static String workerHeartbeat(String workerId) {
        return WORKER_HEARTBEAT_PREFIX + workerId;
    }

    public static String jobExecution(String executionId) {
        return JOB_EXECUTION_PREFIX + executionId;
    }
}
