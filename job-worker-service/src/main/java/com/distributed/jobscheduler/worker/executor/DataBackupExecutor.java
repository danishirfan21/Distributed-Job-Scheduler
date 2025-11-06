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
public class DataBackupExecutor implements JobExecutor {

    private final Random random = new Random();

    @Override
    public Map<String, Object> execute(JobExecutionDTO execution) throws Exception {
        Map<String, Object> params = execution.getParameters();

        String source = (String) params.get("source");
        String destination = (String) params.get("destination");

        log.info("Starting backup from {} to {}", source, destination);

        // Simulate backup process
        int totalFiles = random.nextInt(1000) + 100;
        for (int i = 0; i < 5; i++) {
            Thread.sleep(1000);
            log.info("Backup progress: {}%", (i + 1) * 20);
        }

        log.info("Backup completed successfully");

        Map<String, Object> result = new HashMap<>();
        result.put("source", source);
        result.put("destination", destination);
        result.put("filesBackedUp", totalFiles);
        result.put("totalSize", random.nextInt(1000000) + 100000);
        result.put("completedAt", System.currentTimeMillis());

        return result;
    }

    @Override
    public boolean supports(String jobType) {
        return JobType.DATA_BACKUP.name().equals(jobType);
    }
}
