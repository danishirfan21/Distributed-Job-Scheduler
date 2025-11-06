package com.distributed.jobscheduler.worker.executor;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;

import java.util.Map;

public interface JobExecutor {

    /**
     * Execute the job and return the result
     */
    Map<String, Object> execute(JobExecutionDTO execution) throws Exception;

    /**
     * Check if this executor supports the given job type
     */
    boolean supports(String jobType);
}
