package com.distributed.jobscheduler.scheduler.controller;

import com.distributed.jobscheduler.common.dto.ApiResponse;
import com.distributed.jobscheduler.common.dto.JobDefinitionDTO;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import com.distributed.jobscheduler.scheduler.entity.JobExecutionEntity;
import com.distributed.jobscheduler.scheduler.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping
    public ResponseEntity<ApiResponse<JobEntity>> createJob(
            @Valid @RequestBody JobDefinitionDTO dto,
            Authentication authentication) {

        String userId = authentication != null ? authentication.getName() : "system";
        JobEntity job = jobService.createJob(dto, userId);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Job created successfully", job));
    }

    @PostMapping("/{jobId}/execute")
    public ResponseEntity<ApiResponse<JobExecutionDTO>> executeJob(@PathVariable String jobId) {
        JobExecutionDTO execution = jobService.executeJob(jobId);

        return ResponseEntity.ok(ApiResponse.success("Job dispatched successfully", execution));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<JobEntity>>> listJobs(Pageable pageable) {
        Page<JobEntity> jobs = jobService.listJobs(pageable);

        return ResponseEntity.ok(ApiResponse.success(jobs));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<JobEntity>> getJob(@PathVariable String jobId) {
        JobEntity job = jobService.getJob(jobId);

        return ResponseEntity.ok(ApiResponse.success(job));
    }

    @GetMapping("/{jobId}/history")
    public ResponseEntity<ApiResponse<Page<JobExecutionEntity>>> getJobHistory(
            @PathVariable String jobId,
            Pageable pageable) {

        Page<JobExecutionEntity> history = jobService.getJobHistory(jobId, pageable);

        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<ApiResponse<JobExecutionEntity>> getExecution(@PathVariable String executionId) {
        JobExecutionEntity execution = jobService.getExecution(executionId);

        return ResponseEntity.ok(ApiResponse.success(execution));
    }

    @PostMapping("/executions/{executionId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelExecution(@PathVariable String executionId) {
        jobService.cancelExecution(executionId);

        return ResponseEntity.ok(ApiResponse.success("Execution cancelled successfully", null));
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<ApiResponse<Void>> deleteJob(@PathVariable String jobId) {
        jobService.deleteJob(jobId);

        return ResponseEntity.ok(ApiResponse.success("Job deleted successfully", null));
    }
}
