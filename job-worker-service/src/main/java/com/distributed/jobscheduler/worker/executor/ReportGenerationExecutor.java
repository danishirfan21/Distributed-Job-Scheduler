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
public class ReportGenerationExecutor implements JobExecutor {

    private final Random random = new Random();

    @Override
    public Map<String, Object> execute(JobExecutionDTO execution) throws Exception {
        Map<String, Object> params = execution.getParameters();

        String reportType = (String) params.get("reportType");
        String format = (String) params.getOrDefault("format", "PDF");

        log.info("Generating {} report in {} format", reportType, format);

        // Simulate report generation
        Thread.sleep(random.nextInt(3000) + 2000);

        log.info("Report generated successfully");

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", reportType);
        result.put("format", format);
        result.put("fileSize", random.nextInt(10000) + 1000);
        result.put("filePath", "/reports/" + reportType + "_" + System.currentTimeMillis() + "." + format.toLowerCase());
        result.put("generatedAt", System.currentTimeMillis());

        return result;
    }

    @Override
    public boolean supports(String jobType) {
        return JobType.REPORT_GENERATION.name().equals(jobType);
    }
}
