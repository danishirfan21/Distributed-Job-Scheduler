package com.distributed.jobscheduler.worker.executor;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Component
@Slf4j
public class DataProcessingExecutor implements JobExecutor {

    private final Random random = new Random();

    @Override
    public Map<String, Object> execute(JobExecutionDTO execution) throws Exception {
        Map<String, Object> params = execution.getParameters();

        String dataSource = (String) params.get("dataSource");
        String operation = (String) params.get("operation");

        log.info("Processing data from {} with operation {}", dataSource, operation);

        // Simulate data processing
        int recordsProcessed = 0;
        int totalRecords = random.nextInt(10000) + 1000;

        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            recordsProcessed += totalRecords / 10;
            log.info("Processed {}/{} records", recordsProcessed, totalRecords);
        }

        log.info("Data processing completed");

        Map<String, Object> result = new HashMap<>();
        result.put("dataSource", dataSource);
        result.put("operation", operation);
        result.put("recordsProcessed", totalRecords);
        result.put("successRate", 98.5);
        result.put("completedAt", System.currentTimeMillis());

        return result;
    }

    @Override
    public boolean supports(String jobType) {
        return JobType.DATA_PROCESSING.name().equals(jobType);
    }
}
