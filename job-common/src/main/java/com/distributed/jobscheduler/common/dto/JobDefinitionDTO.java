package com.distributed.jobscheduler.common.dto;

import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.common.enums.Priority;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobDefinitionDTO {

    @NotBlank(message = "Job name is required")
    private String name;

    private String description;

    @NotNull(message = "Job type is required")
    private JobType type;

    @Builder.Default
    private Priority priority = Priority.NORMAL;

    // Cron expression for scheduled jobs (optional)
    private String cronExpression;

    // One-time scheduled execution (optional)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledAt;

    // List of job IDs this job depends on (for DAG)
    private List<String> dependencies;

    // Job-specific parameters
    private Map<String, Object> parameters;

    // Retry configuration
    @Builder.Default
    private Integer maxRetries = 3;

    @Builder.Default
    private Integer retryDelaySeconds = 60;

    // Timeout in seconds
    @Builder.Default
    private Integer timeoutSeconds = 300;

    // Tags for categorization
    private List<String> tags;
}
