package com.distributed.jobscheduler.common.dto;

import com.distributed.jobscheduler.common.enums.JobStatus;
import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.common.enums.Priority;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobExecutionDTO {

    private String jobId;
    private String executionId;
    private String name;
    private JobType type;
    private Priority priority;
    private JobStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;

    private Map<String, Object> parameters;
    private Map<String, Object> result;

    private String errorMessage;
    private String stackTrace;

    private Integer currentRetry;
    private Integer maxRetries;

    private String workerId;
    private Long executionTimeMs;
}
