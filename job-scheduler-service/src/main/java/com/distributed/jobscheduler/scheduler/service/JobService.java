package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.dto.JobDefinitionDTO;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobStatus;
import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import com.distributed.jobscheduler.scheduler.entity.JobExecutionEntity;
import com.distributed.jobscheduler.scheduler.repository.JobExecutionRepository;
import com.distributed.jobscheduler.scheduler.repository.JobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobService {

    private final JobRepository jobRepository;
    private final JobExecutionRepository executionRepository;
    private final DAGValidationService dagValidationService;
    private final KafkaProducerService kafkaProducerService;
    private final RedisLockService redisLockService;
    private final RedisStateService redisStateService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Transactional
    public JobEntity createJob(JobDefinitionDTO dto, String userId) {
        // Validate DAG if dependencies exist
        if (dto.getDependencies() != null && !dto.getDependencies().isEmpty()) {
            Map<String, JobEntity> allJobs = jobRepository.findAll().stream()
                .collect(Collectors.toMap(JobEntity::getId, j -> j));

            JobEntity tempJob = convertToEntity(dto, userId);
            tempJob.setId("temp-validation-id");
            allJobs.put(tempJob.getId(), tempJob);

            if (!dagValidationService.validateDAG(tempJob, allJobs)) {
                throw new IllegalArgumentException("Invalid DAG: Circular dependency detected");
            }
        }

        JobEntity entity = convertToEntity(dto, userId);
        JobEntity saved = jobRepository.save(entity);

        // Increment counter
        Counter.builder("jobs.created")
            .tag("type", dto.getType().name())
            .register(meterRegistry)
            .increment();

        log.info("Job created: id={}, name={}, type={}", saved.getId(), saved.getName(), saved.getType());
        return saved;
    }

    @Transactional
    public JobExecutionDTO executeJob(String jobId) {
        JobEntity job = jobRepository.findByIdAndEnabledTrue(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found or disabled: " + jobId));

        // Try to acquire lock
        String lockValue = redisLockService.acquireLock(jobId, Duration.ofMinutes(10));
        if (lockValue == null) {
            throw new IllegalStateException("Job is already running or locked: " + jobId);
        }

        try {
            // Create execution record
            JobExecutionEntity execution = JobExecutionEntity.builder()
                .jobId(jobId)
                .status(JobStatus.QUEUED)
                .parameters(job.getParameters())
                .currentRetry(0)
                .build();

            execution = executionRepository.save(execution);

            // Convert to DTO and dispatch
            JobExecutionDTO dto = convertToExecutionDTO(execution, job);
            redisStateService.saveExecutionState(dto.getExecutionId(), dto);
            kafkaProducerService.dispatchJob(dto);

            // Increment counter
            Counter.builder("jobs.dispatched")
                .tag("type", job.getType().name())
                .register(meterRegistry)
                .increment();

            log.info("Job dispatched: jobId={}, executionId={}", jobId, execution.getId());
            return dto;

        } finally {
            // Release lock after dispatching
            redisLockService.releaseLock(jobId, lockValue);
        }
    }

    @Transactional(readOnly = true)
    public Page<JobEntity> listJobs(Pageable pageable) {
        return jobRepository.findAllByEnabledTrue(pageable);
    }

    @Transactional(readOnly = true)
    public JobEntity getJob(String jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    }

    @Transactional(readOnly = true)
    public Page<JobExecutionEntity> getJobHistory(String jobId, Pageable pageable) {
        return executionRepository.findByJobId(jobId, pageable);
    }

    @Transactional(readOnly = true)
    public JobExecutionEntity getExecution(String executionId) {
        return executionRepository.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
    }

    @Transactional
    public void cancelExecution(String executionId) {
        JobExecutionEntity execution = executionRepository.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));

        if (execution.getStatus() == JobStatus.COMPLETED ||
            execution.getStatus() == JobStatus.FAILED ||
            execution.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel execution in status: " + execution.getStatus());
        }

        execution.setStatus(JobStatus.CANCELLED);
        execution.setCompletedAt(LocalDateTime.now());
        executionRepository.save(execution);

        // Update Redis state
        JobExecutionDTO dto = convertToExecutionDTO(execution, null);
        redisStateService.saveExecutionState(executionId, dto);

        log.info("Execution cancelled: executionId={}", executionId);
    }

    @Transactional
    public void updateExecutionStatus(String executionId, JobStatus status, String workerId,
                                     Map<String, Object> result, String errorMessage,
                                     String stackTrace, Long executionTimeMs) {
        JobExecutionEntity execution = executionRepository.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));

        execution.setStatus(status);
        execution.setWorkerId(workerId);

        if (status == JobStatus.RUNNING && execution.getStartedAt() == null) {
            execution.setStartedAt(LocalDateTime.now());
        }

        if (status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED) {
            execution.setCompletedAt(LocalDateTime.now());
            execution.setExecutionTimeMs(executionTimeMs);
        }

        if (result != null) {
            try {
                execution.setResult(objectMapper.writeValueAsString(result));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize result", e);
            }
        }

        if (errorMessage != null) {
            execution.setErrorMessage(errorMessage);
            execution.setStackTrace(stackTrace);
        }

        executionRepository.save(execution);

        // Update Redis state
        JobEntity job = jobRepository.findById(execution.getJobId()).orElse(null);
        JobExecutionDTO dto = convertToExecutionDTO(execution, job);
        redisStateService.saveExecutionState(executionId, dto);

        log.info("Execution status updated: executionId={}, status={}", executionId, status);
    }

    @Transactional
    public void deleteJob(String jobId) {
        JobEntity job = jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.setEnabled(false);
        jobRepository.save(job);

        log.info("Job soft deleted: jobId={}", jobId);
    }

    private JobEntity convertToEntity(JobDefinitionDTO dto, String userId) {
        try {
            return JobEntity.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .type(dto.getType())
                .priority(dto.getPriority())
                .cronExpression(dto.getCronExpression())
                .scheduledAt(dto.getScheduledAt())
                .dependencies(dto.getDependencies())
                .parameters(dto.getParameters() != null ?
                    objectMapper.writeValueAsString(dto.getParameters()) : null)
                .maxRetries(dto.getMaxRetries())
                .retryDelaySeconds(dto.getRetryDelaySeconds())
                .timeoutSeconds(dto.getTimeoutSeconds())
                .tags(dto.getTags())
                .enabled(true)
                .createdBy(userId)
                .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize job parameters", e);
        }
    }

    private JobExecutionDTO convertToExecutionDTO(JobExecutionEntity execution, JobEntity job) {
        try {
            Map<String, Object> params = execution.getParameters() != null ?
                objectMapper.readValue(execution.getParameters(), Map.class) : new HashMap<>();

            Map<String, Object> result = execution.getResult() != null ?
                objectMapper.readValue(execution.getResult(), Map.class) : null;

            return JobExecutionDTO.builder()
                .jobId(execution.getJobId())
                .executionId(execution.getId())
                .name(job != null ? job.getName() : null)
                .type(job != null ? job.getType() : null)
                .priority(job != null ? job.getPriority() : null)
                .status(execution.getStatus())
                .createdAt(execution.getCreatedAt())
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .parameters(params)
                .result(result)
                .errorMessage(execution.getErrorMessage())
                .stackTrace(execution.getStackTrace())
                .currentRetry(execution.getCurrentRetry())
                .maxRetries(job != null ? job.getMaxRetries() : 3)
                .workerId(execution.getWorkerId())
                .executionTimeMs(execution.getExecutionTimeMs())
                .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize execution data", e);
        }
    }
}
