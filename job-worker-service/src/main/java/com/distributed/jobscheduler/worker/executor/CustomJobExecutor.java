package com.distributed.jobscheduler.worker.executor;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class CustomJobExecutor implements JobExecutor {

    @Override
    public Map<String, Object> execute(JobExecutionDTO execution) throws Exception {
        Map<String, Object> params = execution.getParameters();

        log.info("Executing custom job with parameters: {}", params);

        // Custom job logic here
        Thread.sleep(2000);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "completed");
        result.put("message", "Custom job executed successfully");
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    @Override
    public boolean supports(String jobType) {
        return JobType.CUSTOM.name().equals(jobType);
    }
}
