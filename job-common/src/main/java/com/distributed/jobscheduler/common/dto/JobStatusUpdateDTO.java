package com.distributed.jobscheduler.common.dto;

import com.distributed.jobscheduler.common.enums.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobStatusUpdateDTO {

    private String executionId;
    private JobStatus status;
    private String workerId;
    private Map<String, Object> result;
    private String errorMessage;
    private String stackTrace;
    private Long executionTimeMs;
}
