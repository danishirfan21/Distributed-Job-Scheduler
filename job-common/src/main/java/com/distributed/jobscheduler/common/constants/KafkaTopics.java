package com.distributed.jobscheduler.common.constants;

public class KafkaTopics {
    public static final String JOB_DISPATCH = "job-dispatch";
    public static final String JOB_STATUS_UPDATE = "job-status-update";
    public static final String JOB_RETRY = "job-retry";

    private KafkaTopics() {
        // Utility class
    }
}
