package com.distributed.jobscheduler.common.enums;

public enum JobStatus {
    PENDING,        // Job created but not yet dispatched
    QUEUED,         // Job dispatched to queue
    RUNNING,        // Job currently executing
    COMPLETED,      // Job completed successfully
    FAILED,         // Job failed
    CANCELLED,      // Job cancelled by user
    RETRYING,       // Job is being retried after failure
    BLOCKED         // Job waiting for dependencies
}
