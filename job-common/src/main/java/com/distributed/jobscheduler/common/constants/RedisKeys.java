package com.distributed.jobscheduler.common.constants;

public class RedisKeys {
    public static final String JOB_LOCK_PREFIX = "job:lock:";
    public static final String JOB_STATE_PREFIX = "job:state:";
    public static final String WORKER_HEARTBEAT_PREFIX = "worker:heartbeat:";
    public static final String JOB_EXECUTION_PREFIX = "job:execution:";

    private RedisKeys() {
        // Utility class
    }

    public static String jobLock(String jobId) {
        return JOB_LOCK_PREFIX + jobId;
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
