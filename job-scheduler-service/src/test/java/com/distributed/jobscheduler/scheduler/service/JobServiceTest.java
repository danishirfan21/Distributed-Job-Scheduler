package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.dto.JobDefinitionDTO;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobStatus;
import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.common.enums.Priority;
import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import com.distributed.jobscheduler.scheduler.entity.JobExecutionEntity;
import com.distributed.jobscheduler.scheduler.repository.JobExecutionRepository;
import com.distributed.jobscheduler.scheduler.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobExecutionRepository executionRepository;

    @Mock
    private DAGValidationService dagValidationService;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private RedisLockService redisLockService;

    @Mock
    private RedisStateService redisStateService;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();

        jobService = new JobService(
            jobRepository,
            executionRepository,
            dagValidationService,
            kafkaProducerService,
            redisLockService,
            redisStateService,
            objectMapper,
            meterRegistry
        );
    }

    @Test
    void createJob_ShouldCreateJobSuccessfully() {
        // Given
        JobDefinitionDTO dto = JobDefinitionDTO.builder()
            .name("Test Job")
            .description("Test Description")
            .type(JobType.EMAIL_NOTIFICATION)
            .priority(Priority.HIGH)
            .maxRetries(3)
            .build();

        JobEntity savedEntity = new JobEntity();
        savedEntity.setId("job-123");
        savedEntity.setName("Test Job");

        when(jobRepository.save(any(JobEntity.class))).thenReturn(savedEntity);

        // When
        JobEntity result = jobService.createJob(dto, "user-123");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("job-123");
        verify(jobRepository).save(any(JobEntity.class));
    }

    @Test
    void executeJob_ShouldDispatchJobSuccessfully() {
        // Given
        String jobId = "job-123";
        String lockValue = "lock-value";

        JobEntity job = JobEntity.builder()
            .id(jobId)
            .name("Test Job")
            .type(JobType.EMAIL_NOTIFICATION)
            .priority(Priority.NORMAL)
            .maxRetries(3)
            .enabled(true)
            .build();

        JobExecutionEntity execution = JobExecutionEntity.builder()
            .id("exec-123")
            .jobId(jobId)
            .status(JobStatus.QUEUED)
            .build();

        when(jobRepository.findByIdAndEnabledTrue(jobId)).thenReturn(Optional.of(job));
        when(redisLockService.acquireLock(eq(jobId), any(Duration.class))).thenReturn(lockValue);
        when(executionRepository.save(any(JobExecutionEntity.class))).thenReturn(execution);

        // When
        JobExecutionDTO result = jobService.executeJob(jobId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getExecutionId()).isEqualTo("exec-123");
        verify(kafkaProducerService).dispatchJob(any(JobExecutionDTO.class));
        verify(redisStateService).saveExecutionState(eq("exec-123"), any(JobExecutionDTO.class));
        verify(redisLockService).releaseLock(jobId, lockValue);
    }

    @Test
    void executeJob_ShouldThrowExceptionWhenJobNotFound() {
        // Given
        String jobId = "non-existent-job";
        when(jobRepository.findByIdAndEnabledTrue(jobId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> jobService.executeJob(jobId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Job not found or disabled");
    }

    @Test
    void executeJob_ShouldThrowExceptionWhenLockCannotBeAcquired() {
        // Given
        String jobId = "job-123";

        JobEntity job = JobEntity.builder()
            .id(jobId)
            .name("Test Job")
            .type(JobType.EMAIL_NOTIFICATION)
            .enabled(true)
            .build();

        when(jobRepository.findByIdAndEnabledTrue(jobId)).thenReturn(Optional.of(job));
        when(redisLockService.acquireLock(eq(jobId), any(Duration.class))).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> jobService.executeJob(jobId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already running or locked");
    }

    @Test
    void cancelExecution_ShouldCancelSuccessfully() {
        // Given
        String executionId = "exec-123";

        JobExecutionEntity execution = JobExecutionEntity.builder()
            .id(executionId)
            .jobId("job-123")
            .status(JobStatus.RUNNING)
            .build();

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.save(any(JobExecutionEntity.class))).thenReturn(execution);

        // When
        jobService.cancelExecution(executionId);

        // Then
        ArgumentCaptor<JobExecutionEntity> captor = ArgumentCaptor.forClass(JobExecutionEntity.class);
        verify(executionRepository).save(captor.capture());

        JobExecutionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    void updateExecutionStatus_ShouldUpdateSuccessfully() {
        // Given
        String executionId = "exec-123";
        JobExecutionEntity execution = JobExecutionEntity.builder()
            .id(executionId)
            .jobId("job-123")
            .status(JobStatus.RUNNING)
            .build();

        JobEntity job = JobEntity.builder()
            .id("job-123")
            .name("Test Job")
            .type(JobType.EMAIL_NOTIFICATION)
            .build();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(jobRepository.findById("job-123")).thenReturn(Optional.of(job));
        when(executionRepository.save(any(JobExecutionEntity.class))).thenReturn(execution);

        // When
        jobService.updateExecutionStatus(executionId, JobStatus.COMPLETED, "worker-1",
            result, null, null, 1000L);

        // Then
        ArgumentCaptor<JobExecutionEntity> captor = ArgumentCaptor.forClass(JobExecutionEntity.class);
        verify(executionRepository).save(captor.capture());

        JobExecutionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(saved.getWorkerId()).isEqualTo("worker-1");
        assertThat(saved.getExecutionTimeMs()).isEqualTo(1000L);
        verify(redisStateService).saveExecutionState(eq(executionId), any(JobExecutionDTO.class));
    }
}
